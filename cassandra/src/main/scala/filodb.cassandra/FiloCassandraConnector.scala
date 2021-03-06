package filodb.cassandra

import com.datastax.driver.core._
import com.typesafe.config.Config
import com.websudos.phantom.connectors.{ContactPoints, KeySpace}
import net.ceedubs.ficus.Ficus._
import scala.concurrent.duration.FiniteDuration

import filodb.core.DatasetRef

trait FiloCassandraConnector {
  private[this] lazy val connector =
    ContactPoints(config.as[Seq[String]]("hosts"), config.getInt("port"))
      .withClusterBuilder(_.withAuthProvider(authProvider)
                           .withSocketOptions(socketOptions)
                           .withQueryOptions(queryOptions))
      .keySpace(keyspace)

  // Cassandra config with following keys:  keyspace, hosts, port, username, password
  def config: Config

  private[this] lazy val authEnabled = config.hasPath("username") && config.hasPath("password")

  private[this] lazy val authProvider =
    if (authEnabled) {
      new PlainTextAuthProvider(config.getString("username"), config.getString("password"))
    } else {
      AuthProvider.NONE
    }

  private[this] lazy val socketOptions = {
    val opts = new SocketOptions
    config.as[Option[FiniteDuration]]("read-timeout").foreach { to =>
      opts.setReadTimeoutMillis(to.toMillis.toInt) }
    config.as[Option[FiniteDuration]]("connect-timeout").foreach { to =>
      opts.setConnectTimeoutMillis(to.toMillis.toInt) }
    opts
  }

  private[this] lazy val queryOptions = {
    val opts = new QueryOptions()
    config.as[Option[String]]("default-consistency-level").foreach { cslevel =>
      opts.setConsistencyLevel(ConsistencyLevel.valueOf(cslevel))
    }
    opts
  }

  implicit lazy val session: Session = connector.session

  def keyspace: String
  lazy val defaultKeySpace = config.getString("keyspace")

  def keySpaceName(ref: DatasetRef): String = ref.database.getOrElse(defaultKeySpace)

  // This is really intended for unit tests.  Production users should create keyspaces manually,
  // probably with something like NetworkTopologyStrategy.
  def createKeyspace(keyspace: String, replicationFactor: Int = 1): Unit = {
    session.execute(s"CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = " +
                    s"{'class': 'SimpleStrategy', 'replication_factor' : $replicationFactor};")
  }

  def cassandraVersion: VersionNumber =  connector.cassandraVersion

  def cassandraVersions: Set[VersionNumber] =  connector.cassandraVersions

  def shutdown(): Unit = {
    com.websudos.phantom.Manager.shutdown()
    session.getCluster.close()
  }
}
