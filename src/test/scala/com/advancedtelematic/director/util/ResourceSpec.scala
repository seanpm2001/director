package com.advancedtelematic.director.util

import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.advancedtelematic.director.data.DataType.Crypto
import com.advancedtelematic.director.http.DirectorRoutes
import com.advancedtelematic.director.manifest.Verifier
import com.advancedtelematic.libtuf.repo_store.RoleKeyStoreClient
import org.genivi.sota.core.DatabaseSpec
import org.scalatest.Suite

object FakeRoleStore extends RoleKeyStoreClient {
  import akka.http.scaladsl.util.FastFuture
  import cats.syntax.show._
  import com.advancedtelematic.libtuf.crypt.RsaKeyPair
  import com.advancedtelematic.libtuf.crypt.RsaKeyPair._
  import com.advancedtelematic.libtuf.data.ClientDataType.{ClientKey, RoleKeys, RootRole}
  import com.advancedtelematic.libtuf.data.ClientCodecs._
  import com.advancedtelematic.libtuf.data.TufDataType._
  import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
  import io.circe.{Encoder, Json}
  import io.circe.syntax._
  import java.security.{KeyPair, PublicKey}
  import java.time.Instant
  import java.util.concurrent.ConcurrentHashMap
  import scala.collection.JavaConverters._
  import scala.concurrent.Future
  import scala.util.Try

  def publicKey(repoId: RepoId): PublicKey =
    keys.asScala(repoId).getPublic

  private def keyPair(repoId: RepoId): KeyPair =
    keys.asScala(repoId)

  private val keys = new ConcurrentHashMap[RepoId, KeyPair]()

  def rootRole(repoId: RepoId) = {
    val rootKey = keys.asScala(repoId)
    val clientKeys = Map(rootKey.id -> ClientKey(KeyType.RSA, rootKey.getPublic))

    val roles = RoleType.ALL.map { role =>
      role.show -> RoleKeys(List(rootKey.id), threshold = 1)
    }.toMap

    RootRole(clientKeys, roles, expires = Instant.now.plusSeconds(3600), version = 1)
  }

  def generateKey(repoId: RepoId): KeyPair = {
    val rootKey = RsaKeyPair.generate(1024)
    keys.put(repoId, rootKey)
  }

  override def createRoot(repoId: RepoId): Future[Json] = {
    if (keys.contains(repoId)) {
      FastFuture.failed(RootRoleConflict)
    } else {
      val _ = generateKey(repoId)
      FastFuture.successful(Json.obj())
    }
  }

  override def sign[T: Encoder](repoId: RepoId, roleType: RoleType, payload: T): Future[SignedPayload[Json]] = {
    val signature = signWithRoot(repoId, payload)
    FastFuture.successful(SignedPayload(List(signature), payload.asJson))
  }

  override def fetchRootRole(repoId: RepoId): Future[SignedPayload[Json]] = {
    Future.fromTry {
      Try {
        val role = rootRole(repoId)
        val signature = signWithRoot(repoId, role)
        SignedPayload(List(signature), role.asJson)
      }.recover {
        case ex: NoSuchElementException =>
          throw RootRoleNotFound
      }
    }
  }

  private def signWithRoot[T : Encoder](repoId: RepoId, payload: T): ClientSignature = {
    val key = keyPair(repoId)
    RsaKeyPair
      .sign(key.getPrivate, payload.asJson.noSpaces.getBytes) // WRONG!!! should use CanonicalJson
      .toClient(key.id)
  }
}

trait ResourceSpec extends ScalatestRouteTest with DatabaseSpec {
  self: Suite =>

  def apiUri(path: String): String = "/api/v1/" + path

  def routesWithVerifier(verifier: Crypto => Verifier.Verifier) = new DirectorRoutes(verifier, FakeRoleStore).routes

  lazy val routes = routesWithVerifier(_ => Verifier.alwaysAccept)
}


