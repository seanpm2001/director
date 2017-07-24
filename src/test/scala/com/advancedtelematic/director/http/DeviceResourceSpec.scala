package com.advancedtelematic.director.http

import java.util.concurrent.ConcurrentHashMap
import java.security.PublicKey

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri
import cats.syntax.show._
import com.advancedtelematic.director.data.AdminRequest._
import com.advancedtelematic.director.data.DataType._
import com.advancedtelematic.director.data.DeviceRequest.{CustomManifest, OperationResult}
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.db.{DeviceRepositorySupport, FileCacheDB, SetTargets}
import com.advancedtelematic.director.manifest.Verifier
import com.advancedtelematic.director.util.{DefaultPatience, DirectorSpec, ResourceSpec}
import com.advancedtelematic.director.data.Codecs.{encoderEcuManifest, encoderCustomManifest}
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libtuf.data.TufDataType.TufKey
import io.circe.syntax._

class DeviceResourceSpec extends DirectorSpec with DefaultPatience with DeviceRepositorySupport
    with FileCacheDB with ResourceSpec with NamespacedRequests {

  def schedule(device: DeviceId, targets: SetTarget, updateId: UpdateId): Unit = {
    SetTargets.setTargets(defaultNs, Seq(device -> targets), Some(updateId)).futureValue
    pretendToGenerate().futureValue
  }

  def deviceVersion(deviceId: DeviceId): Option[Int] = {
    deviceRepository.getCurrentVersion(deviceId).map(Some.apply).recover{case _ => None}.futureValue
  }

  testWithNamespace("Can register device") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = GenRegisterEcu.atMost(5).generate ++ (primEcuReg :: GenRegisterEcu.atMost(5).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)
  }

  testWithNamespace("Can't register device with primary ECU not in `ecus`") { implicit ns =>
    val device = DeviceId.generate()
    val primEcu = GenEcuSerial.generate
    val ecus = GenRegisterEcu.atMost(5).generate.filter(_.ecu_serial != primEcu)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceExpected(regDev, StatusCodes.BadRequest)
  }

  testWithNamespace("Device can update a registered device") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = GenRegisterEcu.atMost(5).generate ++ (primEcuReg :: GenRegisterEcu.atMost(5).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }

    val deviceManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifestOk(device, deviceManifest)
  }

  testWithNamespace("Device can update a registered device (legacy device manifest)") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = GenRegisterEcu.atMost(5).generate ++ (primEcuReg :: GenRegisterEcu.atMost(5).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }

    val deviceManifest = GenSignedLegacyDeviceManifest(primEcu, ecuManifests).generate

    updateLegacyManifestOk(device, deviceManifest)
  }

  testWithNamespace("Device must have the ecu given as primary") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val fakePrimEcu = GenEcuSerial.generate
    val ecus = GenRegisterEcu.atMost(5).generate ++
      (primEcuReg :: GenRegisterEcu.atMost(5).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }

    val deviceManifest = GenSignedDeviceManifest(fakePrimEcu, ecuManifests).generate

    updateManifestExpect(device, deviceManifest, StatusCodes.NotFound)
  }

  testWithNamespace("Device need to have the correct primary") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val fakePrimEcuReg = GenRegisterEcu.generate
    val fakePrimEcu = fakePrimEcuReg.ecu_serial
    val ecus = GenRegisterEcu.atMost(5).generate ++
      (primEcuReg :: fakePrimEcuReg :: GenRegisterEcu.atMost(5).generate)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }

    val deviceManifest = GenSignedDeviceManifest(fakePrimEcu, ecuManifests).generate

    updateManifestExpect(device, deviceManifest, StatusCodes.BadRequest)
  }

  testWithNamespace("Device update will only update correct ecus") { implicit ns =>
    val taintedKeys = new ConcurrentHashMap[PublicKey, Unit]() // this is like a set
    def testVerifier(c: TufKey): Verifier.Verifier =
      if (taintedKeys.contains(c.keyval)) {
        Verifier.alwaysReject
      } else {
        Verifier.alwaysAccept
      }

    val verifyRoutes = routesWithVerifier(testVerifier)


    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecusWork = GenRegisterEcu.atMost(5).generate ++ (primEcuReg :: GenRegisterEcu.atMost(5).generate)
    val ecusFail = GenEcuSerial.nonEmptyAtMost(5).generate.map{ecu =>
      val regEcu = GenRegisterEcu.generate
      taintedKeys.put(regEcu.clientKey.keyval, Unit)
      regEcu
    }
    val ecus = ecusWork ++ ecusFail

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOkWith(regDev, verifyRoutes)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }

    val deviceManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifestOkWith(device, deviceManifest, verifyRoutes)

    val images = getInstalledImagesOkWith(device, verifyRoutes)

    val mImages = {
      val start = images.groupBy(_._1).mapValues(_.map(_._2))
      start.values.foreach { x =>
        x.length shouldBe 1
      }

      start.mapValues(_.head)
    }

    ecus.zip(ecuManifests.map(_.signed)).foreach { case (regEcu, ecuMan) =>
      if (regEcu.clientKey.keyval.getFormat() == "REJECT ME") {
        mImages.get(regEcu.ecu_serial) shouldBe None
        } else {
        mImages.get(regEcu.ecu_serial) shouldBe Some(ecuMan.installed_image)
      }
    }
  }

  testWithNamespace("Can set target for device") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = List(primEcuReg)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val targets = SetTarget(Map(primEcu -> GenCustomImage.generate))

    setTargetsOk(device, targets)
  }

  testWithNamespace("Device can update to set target") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = List(primEcuReg)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val targetImage = GenCustomImage.generate
    val targets = SetTarget(Map(primEcu -> targetImage))

    setTargetsOk(device, targets)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }
    val deviceManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifestOk(device, deviceManifest)

    val ecuManifestsTarget = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }.map { sig =>
      sig.copy(signed = sig.signed.copy(installed_image = targetImage.image))
    }
    val deviceManifestTarget = GenSignedDeviceManifest(primEcu, ecuManifestsTarget).generate

    updateManifestOk(device, deviceManifestTarget)
  }

  testWithNamespace("Device can report current current") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = List(primEcuReg)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }
    val deviceManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifestOk(device, deviceManifest)

    val targetImage = GenCustomImage.generate
    val targets = SetTarget(Map(primEcu -> targetImage))

    setTargetsOk(device, targets)

    updateManifestOk(device, deviceManifest)
  }

  testWithNamespace("Successful campaign update is reported to core") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = List(primEcuReg)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }
    val deviceManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifestOk(device, deviceManifest)

    val targetImage = GenCustomImage.generate
    val targets = SetTarget(Map(primEcu -> targetImage))

    val updateId = UpdateId.generate

    schedule(device, targets, updateId)

    val operation = OperationResult(updateId.show, 0, "Yeah that worked")
    val custom = CustomManifest(operation)
    val ecuManifestsTarget = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial, Some(custom)).generate }.map { sig =>
      sig.copy(signed = sig.signed.copy(installed_image = targetImage.image))
    }
    val deviceManifestTarget = GenSignedDeviceManifest(primEcu, ecuManifestsTarget).generate

    updateManifestOk(device, deviceManifestTarget)

    coreClient.getReport(updateId) shouldBe Seq(operation)
  }

  testWithNamespace("Failed campaign update is reported to core and cancels remaing") { implicit ns =>

    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = List(primEcuReg)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }
    val deviceManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifestOk(device, deviceManifest)

    val targetImage = GenCustomImage.generate
    val targets = SetTarget(Map(primEcu -> targetImage))
    val updateId = UpdateId.generate

    schedule(device, targets, updateId)

    val operation = OperationResult(updateId.show, 4, "sad face")
    val custom = CustomManifest(operation)

    val ecuManifestsTarget = ecuManifests.map { secu =>
      secu.copy(signed = secu.signed.copy(custom = Some(custom.asJson)))
    }

    val deviceManifestTarget = GenSignedDeviceManifest(primEcu, ecuManifestsTarget).generate

    updateManifestOk(device, deviceManifestTarget)

    coreClient.getReport(updateId) shouldBe Seq(operation)
  }

  testWithNamespace("Device update to target counts as failed campaign") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = List(primEcuReg)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }
    val deviceManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifestOk(device, deviceManifest)

    val targetImage = GenCustomImage.generate
    val targets = SetTarget(Map(primEcu -> targetImage))
    val updateId = UpdateId.generate

    schedule(device, targets, updateId)

    val operation = OperationResult("update", 0, "this looks like success")
    val custom = CustomManifest(operation)

    val ecuManifestsTarget = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }.map { secu =>
      secu.copy(signed = secu.signed.copy(custom = Some(custom.asJson)))
    }

    val deviceManifestTarget = GenSignedDeviceManifest(primEcu, ecuManifestsTarget).generate

    updateManifestOk(device, deviceManifestTarget)

    coreClient.getReport(updateId).map(_.result_code) shouldBe Seq(4)
  }

  testWithNamespace("Update where the device is already") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = List(primEcuReg)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifest(regEcu.ecu_serial).generate }
    val deviceManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifestOk(device, deviceManifest)

    val targetImage = GenCustomImage.generate
    val targets = SetTarget(Map(primEcu -> CustomImage(ecuManifests.head.signed.installed_image, Uri(), None)))
    val updateId = UpdateId.generate

    schedule(device, targets, updateId)
    updateManifestOk(device, deviceManifest)

    deviceVersion(device) shouldBe Some(1)
  }

  testWithNamespace("First Device can also update") { implicit ns =>
    val device = DeviceId.generate()
    val primEcuReg = GenRegisterEcu.generate
    val primEcu = primEcuReg.ecu_serial
    val ecus = List(primEcuReg)

    val regDev = RegisterDevice(device, primEcu, ecus)

    registerDeviceOk(regDev)

    val cimage = GenCustomImage.generate

    val targetImage = GenCustomImage.generate
    val targets = SetTarget(Map(primEcu -> cimage))
    val updateId = UpdateId.generate

    schedule(device, targets, updateId)

    val ecuManifests = ecus.map { regEcu => GenSignedEcuManifestWithImage(regEcu.ecu_serial, cimage.image).generate }
    val deviceManifest = GenSignedDeviceManifest(primEcu, ecuManifests).generate

    updateManifestOk(device, deviceManifest)

    deviceVersion(device) shouldBe Some(1)
  }
}
