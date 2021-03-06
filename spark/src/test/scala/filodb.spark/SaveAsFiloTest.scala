package filodb.spark

import java.sql.Timestamp
import org.apache.spark.{SparkContext, SparkException, SparkConf}
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.hive.HiveContext
import org.scalatest.time.{Millis, Seconds, Span}
import scala.concurrent.duration._

import filodb.core._
import filodb.core.metadata.{Column, DataColumn, Dataset}

object SaveAsFiloTest {
  case class TSData(machine: String, metric: Double, time: Timestamp)
  val timeseries = Seq(
    TSData("com.abc.def.foo", 1.1, new Timestamp(1000000L)),
    TSData("com.abc.def.bar", 1.2, new Timestamp(1000010L)),
    TSData("com.abc.def.baz", 1.3, new Timestamp(1000020L))
  )
}

/**
 * Test all ingestion modes and options, and reading as well / predicate pushdowns
 */
class SaveAsFiloTest extends SparkTestBase {

  // implicit val defaultPatience =
  //   PatienceConfig(timeout = Span(15, Seconds), interval = Span(250, Millis))

  // Setup SQLContext and a sample DataFrame
  val conf = (new SparkConf).setMaster("local[4]")
                            .setAppName("test")
                            .set("spark.filodb.cassandra.keyspace", "unittest")
                            .set("spark.filodb.cassandra.admin-keyspace", "unittest")
                            .set("spark.filodb.memtable.min-free-mb", "10")
                            .set("spark.ui.enabled", "false")
  val sc = new SparkContext(conf)
  val sql = new HiveContext(sc)

  val segCol = ":string 0"
  val partKeys = Seq(":string part0")
  val ds1 = Dataset("gdelt1", "id", segCol)
  val ds2 = Dataset("gdelt2", "id", segCol)
  val ds3 = Dataset("gdelt3", "id", segCol)
  val test1 = Dataset("test1", "id", segCol)
  val testProjections = Seq(ds1, ds2, ds3, test1).map(_.projections.head)
  val ingestOptions = IngestionOptions(writeTimeout = 2.minutes)

  // This is the same code that the Spark stuff uses.  Make sure we use exact same environment as real code
  // so we don't have two copies of metaStore that could be configured differently.
  val filoConfig = FiloDriver.initAndGetConfig(sc)

  // Sample data.  Note how we must create a partitioning column.
  val jsonRows = Seq(
    """{"id":0,"sqlDate":"2015/03/15T15:00Z","monthYear":32015,"year":2015}""",
    """{"id":1,"sqlDate":"2015/03/15T16:00Z","monthYear":42015}""",
    """{"id":2,"sqlDate":"2015/03/15T17:00Z",                  "year":2015}"""
  )
  val dataDF = sql.read.json(sc.parallelize(jsonRows, 1))

  import org.apache.spark.sql.functions._

  it("should create missing columns and partitions and write table") {
    sql.saveAsFilo(dataDF, "gdelt1", Seq("id"), segCol, partKeys,
                   options = ingestOptions)

    // Now read stuff back and ensure it got written
    val df = sql.filoDataset("gdelt1")
    df.select(count("id")).collect().head(0) should equal (3)
    df.agg(sum("year")).collect().head(0) should equal (4030)
    val row = df.select("id", "sqlDate", "monthYear").limit(1).collect.head
    row(0) should equal (0)
    row(1) should equal ("2015/03/15T15:00Z")
    row(2) should equal (32015)
  }

  it("should throw ColumnTypeMismatch if existing columns are not same type") {
    metaStore.newDataset(ds2).futureValue should equal (Success)
    val idStrCol = DataColumn(0, "id", "gdelt2", 0, Column.ColumnType.StringColumn)
    metaStore.newColumn(idStrCol, DatasetRef("gdelt2")).futureValue should equal (Success)

    intercept[ColumnTypeMismatch] {
      sql.saveAsFilo(dataDF, "gdelt2", Seq("id"), segCol, partKeys)
    }
  }

  it("should throw BadSchemaError if illegal computed column specification or bad schema") {
    // year is a Long column, <none> cannot be parsed to a Long
    intercept[BadSchemaError] {
      dataDF.write.format("filodb.spark").
                   option("dataset", "test1").
                   option("row_keys", "id").
                   option("segment_key", segCol).
                   option("partition_keys", ":getOrElse year <none>").
                   mode(SaveMode.Overwrite).
                   save()
    }

    intercept[BadSchemaError] {
      dataDF.write.format("filodb.spark").
                   option("dataset", "test1").
                   option("row_keys", "not_a_col").
                   option("segment_key", segCol).
                   option("partition_keys", ":fooMucnhkin 123").
                   mode(SaveMode.Overwrite).
                   save()
    }
  }

  it("should not delete original metadata if overwrite with bad schema definition") {
    sql.saveAsFilo(dataDF, "gdelt1", Seq("id"), segCol, partKeys, options = ingestOptions)

    intercept[BadSchemaError] {
      dataDF.write.format("filodb.spark").
                   option("dataset", "gdelt1").
                   option("row_keys", "not_a_col").
                   option("segment_key", segCol).
                   option("partition_keys", ":fooMucnhkin 123").
                   option("reset_schema", "true").
                   mode(SaveMode.Overwrite).
                   save()
    }

    FiloDriver.metaStore.getDataset("gdelt1").futureValue should equal (
      ds1.copy(partitionColumns = partKeys).withDatabase("unittest"))
  }

  it("should write table if there are existing matching columns") {
    metaStore.newDataset(ds3).futureValue should equal (Success)
    val idStrCol = DataColumn(0, "id", "gdelt1", 0, Column.ColumnType.LongColumn)
    metaStore.newColumn(idStrCol, DatasetRef("gdelt1")).futureValue should equal (Success)

    sql.saveAsFilo(dataDF, "gdelt1", Seq("id"), segCol, partKeys, options = ingestOptions)

    // Now read stuff back and ensure it got written
    val df = sql.filoDataset("gdelt1")
    df.select(count("id")).collect().head(0) should equal (3)
  }

  it("should throw error in ErrorIfExists mode if dataset already exists") {
    sql.saveAsFilo(dataDF, "gdelt2", Seq("id"), segCol, partKeys, options = ingestOptions)

    intercept[RuntimeException] {
      // The default mode is ErrorIfExists
      dataDF.write.format("filodb.spark").
                   option("dataset", "gdelt2").
                   option("row_keys", "id").
                   option("segment_key", segCol).
                   save()
    }
  }

  it("should write and read using DF write() and read() APIs") {
    dataDF.write.format("filodb.spark").
                 option("dataset", "test1").
                 option("row_keys", "id").
                 option("segment_key", segCol).
                 mode(SaveMode.Overwrite).
                 save()
    val df = sql.read.format("filodb.spark").option("dataset", "test1").load()
    df.agg(sum("year")).collect().head(0) should equal (4030)
    df.select("id", "year").limit(2).collect()   // Just to make sure row copy works
  }

  it("should write and read to another keyspace using DF write() and read() APIs") {
    dataDF.write.format("filodb.spark").
                 option("dataset", "test1").
                 option("database", "unittest2").
                 option("row_keys", "id").
                 option("segment_key", segCol).
                 mode(SaveMode.Overwrite).
                 save()
    val df = sql.read.format("filodb.spark").option("dataset", "test1").
                      option("database", "unittest2").load()
    df.agg(sum("year")).collect().head(0) should equal (4030)
    df.select("id", "year").limit(2).collect()   // Just to make sure row copy works

    intercept[NotFoundError] {
      val df2 = sql.read.format("filodb.spark").option("dataset", "test1").load()
    }
  }

  val jsonRows2 = Seq(
    """{"id":3,"sqlDate":"2015/03/15T18:00Z","monthYear":32015,"year":2016}""",
    """{"id":4,"sqlDate":"2015/03/15T19:00Z","monthYear":42015}""",
    """{"id":5,"sqlDate":"2015/03/15T19:30Z",                  "year":2016}"""
  )
  val dataDF2 = sql.read.json(sc.parallelize(jsonRows2, 1))

  it("should overwrite existing data if mode=Overwrite") {
    dataDF.sort("id").write.format("filodb.spark").
                 option("dataset", "gdelt1").
                 option("row_keys", "id").
                 option("segment_key", segCol).
                 save()

    // Data is different, should not append, should overwrite
    // Also try changing one of the keys.  If no reset_schema, then seg key not changed.
    val newSegCol = ":string AA"
    dataDF2.write.format("filodb.spark").
                 option("dataset", "gdelt1").
                 option("row_keys", "id").
                 option("segment_key", newSegCol).
                 mode(SaveMode.Overwrite).
                 save()

    val df = sql.read.format("filodb.spark").option("dataset", "gdelt1").load()
    df.agg(sum("year")).collect().head(0) should equal (4032)

    val dsObj = metaStore.getDataset(DatasetRef("gdelt1")).futureValue
    dsObj.projections.head.segmentColId should equal (segCol)

    dataDF2.write.format("filodb.spark").
                 option("dataset", "gdelt1").
                 option("row_keys", "id").
                 option("segment_key", newSegCol).
                 option("reset_schema", "true").
                 mode(SaveMode.Overwrite).
                 save()

    val dsObj2 = metaStore.getDataset(DatasetRef("gdelt1")).futureValue
    dsObj2.projections.head.segmentColId should equal (newSegCol)

    // Also try overwriting with insert API
    sql.insertIntoFilo(dataDF2, "gdelt1", overwrite = true)
    df.agg(sum("year")).collect().head(0) should equal (4032)
  }

  it("should append data in Append mode") {
    dataDF.write.format("filodb.spark").
                 option("dataset", "gdelt2").
                 option("row_keys", "id").
                 option("segment_key", segCol).
                 mode(SaveMode.Append).
                 save()

    sql.insertIntoFilo(dataDF2, "gdelt2")

    val df = sql.read.format("filodb.spark").option("dataset", "gdelt2").load()
    df.agg(sum("year")).collect().head(0) should equal (8062)
  }

  it("should append data using SQL INSERT INTO statements") {
    sql.saveAsFilo(dataDF2, "gdelt1", Seq("id"), segCol, partKeys, options = ingestOptions)
    sql.saveAsFilo(dataDF, "gdelt2", Seq("id"), segCol, partKeys, options = ingestOptions)

    val gdelt1 = sql.filoDataset("gdelt1")
    val gdelt2 = sql.filoDataset("gdelt2")
    gdelt1.registerTempTable("gdelt1")
    gdelt2.registerTempTable("gdelt2")
    sql.sql("INSERT INTO table gdelt2 SELECT * FROM gdelt1").count()
    gdelt2.agg(sum("year")).collect().head(0) should equal (8062)
  }

  // Also test that we can read back from just one partition
  it("should be able to write with a user-specified partitioning column") {
    dataDF.write.format("filodb.spark").
                 option("dataset", "test1").
                 option("row_keys", "id").
                 option("segment_key", segCol).
                 option("partition_keys", ":getOrElse year 9999").
                 mode(SaveMode.Overwrite).
                 save()
    val df = sql.read.format("filodb.spark").option("dataset", "test1").load()
    df.agg(sum("id")).collect().head(0) should equal (3)
    df.registerTempTable("test1")
    sql.sql("SELECT sum(id) FROM test1 WHERE year = 2015").collect.head(0) should equal (2)
    sql.sql("SELECT count(*) FROM test1").collect.head(0) should equal (3)
  }

  it("should be able to write with multi-column partition keys") {
    import sql.implicits._

    val gdeltDF = sc.parallelize(GdeltTestData.records.toSeq).toDF()
    gdeltDF.write.format("filodb.spark").
                 option("dataset", "gdelt3").
                 option("row_keys", "eventId").
                 option("segment_key", segCol).
                 option("partition_keys", ":getOrElse actor2Code --,:getOrElse year -1").
                 mode(SaveMode.Overwrite).
                 save()
    val df = sql.read.format("filodb.spark").option("dataset", "gdelt3").load()
    df.agg(sum("numArticles")).collect().head(0) should equal (492)
  }

  it("should be able to parse and use partition filters in queries") {
    import sql.implicits._

    val gdeltDF = sc.parallelize(GdeltTestData.records.toSeq).toDF()
    gdeltDF.write.format("filodb.spark").
                 option("dataset", "gdelt3").
                 option("row_keys", "eventId").
                 option("segment_key", segCol).
                 option("partition_keys", ":getOrElse actor2Code --,:getOrElse year -1").
                 mode(SaveMode.Overwrite).
                 save()
    val df = sql.read.format("filodb.spark").option("dataset", "gdelt3").load()
    df.registerTempTable("gdelt")
    sql.sql("select sum(numArticles) from gdelt where actor2Code in ('JPN', 'KHM')").collect().
      head(0) should equal (30)
    sql.sql("select sum(numArticles) from gdelt where actor2Code = 'JPN' AND year = 1979").collect().
      head(0) should equal (10)
  }

  it("should be able to parse and use partition filters even if partition has computed column") {
    import sql.implicits._

    val gdeltDF = sc.parallelize(GdeltTestData.records.toSeq).toDF()
    gdeltDF.write.format("filodb.spark").
                 option("dataset", "gdelt3").
                 option("row_keys", "eventId").
                 option("segment_key", segCol).
                 option("partition_keys", ":stringPrefix actor2Code 1,:getOrElse year -1").
                 mode(SaveMode.Overwrite).
                 save()
    val df = sql.read.format("filodb.spark").option("dataset", "gdelt3").load()
    df.registerTempTable("gdelt")
    sql.sql("select sum(numArticles) from gdelt where actor2Code in ('JPN', 'KHM')").collect().
      head(0) should equal (30)
    sql.sql("select sum(numArticles) from gdelt where actor2Code = 'JPN' AND year = 1979").collect().
      head(0) should equal (10)
  }

  it("should be able to write with multi-column row keys and filter by segment key") {
    import sql.implicits._

    val gdeltDF = sc.parallelize(GdeltTestData.records.toSeq).toDF()
    gdeltDF.write.format("filodb.spark").
                 option("dataset", "gdelt3").
                 option("row_keys", ":getOrElse actor2Code NONE,eventId").
                 option("segment_key", ":round eventId 50").
                 mode(SaveMode.Overwrite).
                 save()
    val df = sql.read.format("filodb.spark").option("dataset", "gdelt3").load()
    df.agg(sum("numArticles")).collect().head(0) should equal (492)

    df.registerTempTable("gdelt")
    sql.sql("select sum(numArticles) from gdelt where eventId >= 78 AND eventId <= 85").collect().
      head(0) should equal (15)
  }

  it("should be able to write with multi-column row keys and filter by segment key equals") {
    import sql.implicits._

    val gdeltDF = sc.parallelize(GdeltTestData.records.toSeq).toDF()
    gdeltDF.write.format("filodb.spark").
      option("dataset", "gdelt3").
      option("row_keys", ":getOrElse actor2Code NONE,eventId").
      option("segment_key", ":round eventId 50").
      mode(SaveMode.Overwrite).
      save()
    val df = sql.read.format("filodb.spark").option("dataset", "gdelt3").load()
    df.agg(sum("numArticles")).collect().head(0) should equal (492)

    df.registerTempTable("gdelt")
    sql.sql("select sum(numArticles) from gdelt where eventId = 21").collect().
      head(0) should equal (10)
  }

  it("should be able to ingest Spark Timestamp columns and query them") {
    import sql.implicits._
    val tsDF = sc.parallelize(SaveAsFiloTest.timeseries).toDF()
    tsDF.write.format("filodb.spark").
               option("dataset", "test1").
               option("row_keys", "time").
               option("segment_key", ":string 0").
               mode(SaveMode.Overwrite).save()
    val df = sql.read.format("filodb.spark").option("dataset", "test1").load()
    val selectedRow = df.select("metric", "time").limit(1).collect.head
    selectedRow(0) should equal (1.1)
    selectedRow(1) should equal (new Timestamp(1000000L))
    df.agg(max("time")).collect().head(0) should equal (new Timestamp(1000020L))
  }
}