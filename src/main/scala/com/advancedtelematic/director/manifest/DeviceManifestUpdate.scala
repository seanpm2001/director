package com.advancedtelematic.director.manifest

import cats.syntax.show._
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DeviceRequest.{CustomManifest, DeviceManifest, EcuManifest}
import com.advancedtelematic.director.db.{DeviceRepositorySupport, DeviceUpdate, DeviceUpdateResult}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, EcuSerial, EcuInstallationReport, InstallationResult}
import com.advancedtelematic.libtuf.data.TufDataType.{SignedPayload, TufKey, TargetFilename}
import io.circe.Json
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import slick.driver.MySQLDriver.api._

class DeviceManifestUpdate(afterUpdate: AfterDeviceManifestUpdate,
                           verifier: TufKey => Verifier
                          )(implicit val db: Database, val ec: ExecutionContext)
    extends DeviceRepositorySupport {
  private lazy val _log = LoggerFactory.getLogger(this.getClass)

  def setDeviceManifest(namespace: Namespace, device: DeviceId, signedDevMan: SignedPayload[Json]): Future[Unit] = for {
    ecus <- deviceRepository.findEcus(namespace, device)
    deviceManifest <- Future.fromTry(Verify.deviceManifest(ecus, verifier, signedDevMan))
    _ <- processManifest(namespace, device, deviceManifest)
  } yield ()

  private def processManifest(namespace: Namespace, device: DeviceId, deviceManifest: DeviceManifest): Future[Unit] = {

    val ecuResults = toEcuInstallationReports(deviceManifest.ecu_manifests)
    val failedTargets = toFailedTargets(deviceManifest.ecu_manifests)
    DeviceUpdate.checkAgainstTarget(namespace, device, deviceManifest.ecu_manifests).flatMap {
      case DeviceUpdateResult.NoUpdate => Future.successful(())
      case DeviceUpdateResult.UpdateNotCompleted(updateTarget) =>
        if (ecuResults.find(!_._2.result.success).isDefined) {
          _log.info(s"Device ${device.show} reports errors during install: $ecuResults")
          deviceRepository.getCurrentVersion(device).flatMap { currentVersion =>
            afterUpdate.failedMultiTargetUpdate(namespace, device, currentVersion, failedTargets, ecuResults)
          }
        } else {
          Future.successful(())
        }
      case DeviceUpdateResult.UpdateSuccessful(updateTarget) =>
        afterUpdate.successMultiTargetUpdate(namespace, device, updateTarget, ecuResults)
      case DeviceUpdateResult.UpdateUnexpectedTarget(updateTarget, targets, manifest) =>
        val currentVersion = updateTarget.targetVersion - 1
        if (targets.isEmpty) {
          _log.error(s"Device ${device.show} updated when no update was available")
        } else {
          _log.error(s"Device ${device.show} updated to the wrong target")
        }
        _log.info {
          s"""version : ${currentVersion + 1}
             |targets : $targets
             |manifest: $manifest
           """.stripMargin
        }
        afterUpdate.failedMultiTargetUpdate(namespace, device, currentVersion, failedTargets, ecuResults)
    }

  }

  private def toFailedTargets(ecuManifests: Seq[EcuManifest]): Map[EcuSerial, TargetFilename] =
    ecuManifests.par.flatMap{ ecuManifest =>
      ecuManifest.custom.flatMap(_.as[CustomManifest].toOption)
        .filter(_.operation_result.result_code != 0)
        .map { _ => ecuManifest.ecu_serial -> ecuManifest.installed_image.filepath }
    }.toMap.seq

  private def toEcuInstallationReports(ecuManifests: Seq[EcuManifest]): Map[EcuSerial, EcuInstallationReport] =
    ecuManifests.par.flatMap{ ecuManifest =>
      ecuManifest.custom.flatMap(_.as[CustomManifest].toOption).map { custom =>
        val op = custom.operation_result
        ecuManifest.ecu_serial -> EcuInstallationReport(
          InstallationResult(op.result_code == 0, op.result_code.toString, op.result_text),
          Seq(ecuManifest.installed_image.filepath.toString),
          None)
      }
    }.toMap.seq
}
