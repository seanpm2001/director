package com.advancedtelematic.deviceregistry.data

import java.time.Instant
import cats.Show
import com.advancedtelematic.libats.data.DataType.{CorrelationId, Namespace, ResultCode}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, Event}
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceMetricsObservation
import com.advancedtelematic.deviceregistry.data.CredentialsType.CredentialsType
import com.advancedtelematic.deviceregistry.data.DataType.IndexedEventType.IndexedEventType
import com.advancedtelematic.deviceregistry.data.Device.{DeviceOemId, DeviceType}
import com.advancedtelematic.deviceregistry.data.DeviceSortBy.DeviceSortBy
import com.advancedtelematic.deviceregistry.data.DeviceStatus.DeviceStatus
import com.advancedtelematic.deviceregistry.data.Group.GroupId
import com.advancedtelematic.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.deviceregistry.data.SortDirection.SortDirection
import io.circe.Json


object DataType {
  case class IndexedEvent(device: DeviceId, eventID: String, eventType: IndexedEventType, correlationId: Option[CorrelationId])

  case class InstallationStat(resultCode: ResultCode, total: Int, success: Boolean)

  object IndexedEventType extends Enumeration {
    type IndexedEventType = Value

    val DownloadComplete,
        EcuDownloadStarted,
        EcuDownloadCompleted,
        EcuInstallationStarted,
        EcuInstallationApplied,
        EcuInstallationCompleted,
        DevicePaused,
        DeviceResumed,
        CampaignAccepted,
        CampaignDeclined,
        CampaignPostponed,
        InstallationComplete = Value
  }

  object InstallationStatsLevel {
    sealed trait InstallationStatsLevel
    case object Device extends InstallationStatsLevel
    case object Ecu extends InstallationStatsLevel
  }

  final case class TaggedDevice(namespace: Namespace, deviceUuid: DeviceId, tagId: TagId, tagValue: String)
  final case class RenameTagId(tagId: TagId)
  final case class UpdateTagValue(tagId: TagId, tagValue: String)
  final case class TagInfo(tagId: TagId, isDelible: Boolean)

  final case class DeviceT(uuid: Option[DeviceId] = None,
                           deviceName: DeviceName,
                           deviceId: DeviceOemId,
                           deviceType: DeviceType = DeviceType.Other,
                           credentials: Option[String] = None,
                           credentialsType: Option[CredentialsType] = None,
                           hibernated: Option[Boolean] = Some(false) )

  final case class SetDevice(deviceName: DeviceName, notes: Option[String] = None)

  final case class UpdateDevice(deviceName: Option[DeviceName], notes: Option[String])

  final case class DeletedDevice(
    namespace: Namespace,
    uuid: DeviceId,
    deviceId: DeviceOemId)

  implicit val eventShow: Show[Event] = Show { event =>
    s"(device=${event.deviceUuid},eventId=${event.eventId},eventType=${event.eventType})"
  }

  final case class DeviceInstallationResult(correlationId: CorrelationId, resultCode: ResultCode, deviceId: DeviceId, success: Boolean, receivedAt: Instant, installationReport: Json)
  final case class EcuInstallationResult(correlationId: CorrelationId, resultCode: ResultCode, deviceId: DeviceId, ecuId: EcuIdentifier, success: Boolean)

  object SearchParams {
    def all(limit: Option[Long], offset: Option[Long]) = SearchParams(
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      Some(DeviceSortBy.CreatedAt),
      Some(SortDirection.Asc),
      offset,
      limit
    )
  }

  final case class SearchParams(oemId: Option[DeviceOemId],
                                grouped: Option[Boolean],
                                groupType: Option[GroupType],
                                groupId: Option[GroupId],
                                nameContains: Option[String],
                                notSeenSinceHours: Option[Int],
                                hibernated: Option[Boolean],
                                status: Option[DeviceStatus],
                                activatedAfter: Option[Instant],
                                activatedBefore: Option[Instant],
                                lastSeenStart: Option[Instant],
                                lastSeenEnd: Option[Instant],
                                createdAtStart: Option[Instant],
                                createdAtEnd: Option[Instant],
                                sortBy: Option[DeviceSortBy],
                                sortDirection: Option[SortDirection],
                                offset: Option[Long],
                                limit: Option[Long],
                               ) {
    if (oemId.isDefined) {
      require(groupId.isEmpty, "Invalid parameters: groupId must be empty when searching by deviceId.")
      require(nameContains.isEmpty, "Invalid parameters: nameContains must be empty when searching by deviceId.")
      require(notSeenSinceHours.isEmpty, "Invalid parameters: notSeenSinceHours must be empty when searching by deviceId.")
    }
  }

  case class PackageListItem(namespace: Namespace, packageId: PackageId, comment: String)
  case class PackageListItemCount(packageId: PackageId, deviceCount: Int)

  case class DeviceUuids(deviceUuids: Seq[DeviceId])

  case class DevicesQuery(oemIds: Option[List[DeviceOemId]], deviceUuids: Option[List[DeviceId]])

  type HibernationStatus = Boolean

  case class UpdateHibernationStatusRequest(status: HibernationStatus)

  case class ObservationPublishResult(publishedSuccessfully: Boolean, msg: DeviceMetricsObservation)
}
