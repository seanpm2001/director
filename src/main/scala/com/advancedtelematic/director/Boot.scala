package com.advancedtelematic.director


import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.{Directives, Route}
import com.advancedtelematic.diff_service.client.DiffServiceDirectorClient
import com.advancedtelematic.director.client.CoreHttpClient
import com.advancedtelematic.director.http.DirectorRoutes
import com.advancedtelematic.director.manifest.SignatureVerification
import com.advancedtelematic.director.roles.{Roles, RolesGeneration}
import com.advancedtelematic.libats.http.BootApp
import com.advancedtelematic.libats.http.LogDirectives.logResponseMetrics
import com.advancedtelematic.libats.http.VersionDirectives.versionHeaders
import com.advancedtelematic.libats.http.monitoring.{MetricsSupport, ServiceHealthCheck}
import com.advancedtelematic.libats.messaging.MessageBus
import com.advancedtelematic.libats.slick.db.DatabaseConfig
import com.advancedtelematic.libats.slick.monitoring.{DatabaseMetrics, DbHealthResource}
import com.advancedtelematic.libtuf_server.keyserver.KeyserverHttpClient
import com.advancedtelematic.metrics._
import com.typesafe.config.{Config, ConfigFactory}
import java.security.Security

import org.bouncycastle.jce.provider.BouncyCastleProvider

trait Settings {
  private def mkUri(config: Config, key: String): Uri = {
    val uri = Uri(config.getString(key))
    if (!uri.isAbsolute) {
      throw new IllegalArgumentException(s"$key is not an absolute uri")
    }
    uri
  }

  private lazy val _config = ConfigFactory.load()

  val host = _config.getString("server.host")
  val port = _config.getInt("server.port")

  val tufUri = mkUri(_config, "keyserver.uri")
  val coreUri = mkUri(_config, "core.uri")
  val tufBinaryUri = mkUri(_config, "tuf.binary.uri")

}

object Boot extends BootApp
  with Directives
  with Settings
  with VersionInfo
  with DatabaseConfig
  with MetricsSupport
  with DatabaseMetrics
  with InfluxdbMetricsReporterSupport {

  implicit val _db = db

  log.info(s"Starting $version on http://$host:$port")

  val coreClient = new CoreHttpClient(coreUri)
  val tuf = KeyserverHttpClient(tufUri)
  implicit val msgPublisher = MessageBus.publisher(system, config).fold(throw _, identity)
  val diffService = new DiffServiceDirectorClient(tufBinaryUri)

  val rolesGeneration = new RolesGeneration(tuf, diffService)
  val roles = new Roles(rolesGeneration)

  Security.addProvider(new BouncyCastleProvider())

  val routes: Route =
    DbHealthResource(versionMap, dependencies = Seq(new ServiceHealthCheck(tufUri))).route ~
    (versionHeaders(version) & logResponseMetrics(projectName)) {
      new DirectorRoutes(SignatureVerification.verify, coreClient, tuf, roles, diffService).routes
    }

  Http().bindAndHandle(routes, host, port)
}
