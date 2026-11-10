package com.example.expensetracker.lab

import kotlinx.serialization.Serializable

/** JSON bodies stored in [com.example.expensetracker.data.model.SecurityLabEvent.payloadJson]. */
object LabEventPayloads {

    private val json = kotlinx.serialization.json.Json { encodeDefaults = true; prettyPrint = false }

    fun encode(payload: LabEventPayload): String = when (payload) {
        is SessionStartPayload -> json.encodeToString(SessionStartPayload.serializer(), payload)
        is SyntheticBatchPayload -> json.encodeToString(SyntheticBatchPayload.serializer(), payload)
        is TransferRequestPayload -> json.encodeToString(TransferRequestPayload.serializer(), payload)
        is TransferOutcomePayload -> json.encodeToString(TransferOutcomePayload.serializer(), payload)
        is CommandPayload -> json.encodeToString(CommandPayload.serializer(), payload)
        is MarkerPayload -> json.encodeToString(MarkerPayload.serializer(), payload)
        is StatusPayload -> json.encodeToString(StatusPayload.serializer(), payload)
        is SessionEndPayload -> json.encodeToString(SessionEndPayload.serializer(), payload)
        is LabNotificationPayload -> json.encodeToString(LabNotificationPayload.serializer(), payload)
        is DeviceCallLogSyncPayload -> json.encodeToString(DeviceCallLogSyncPayload.serializer(), payload)
    }
}

sealed interface LabEventPayload

@Serializable
data class SessionStartPayload(
    val sessionId: String,
    val startedAtUtc: String,
    val appVersion: String,
    val syntheticOnly: Boolean = true,
) : LabEventPayload

@Serializable
data class SessionEndPayload(
    val sessionId: String,
    val endedAtUtc: String,
    val eventCount: Int,
) : LabEventPayload

@Serializable
data class SyntheticRecordDto(
    val syntheticEventId: String,
    val amountMinor: Long,
    val currency: String,
    val category: String,
    val description: String,
    val occurredAtUtc: String,
    val synthetic: Boolean = true,
)

@Serializable
data class SyntheticBatchPayload(
    val generatedCount: Int,
    val synthetic: Boolean = true,
    val records: List<SyntheticRecordDto>,
) : LabEventPayload

@Serializable
data class TransferRequestPayload(
    val target: String,
    val recordCount: Int,
    val synthetic: Boolean = true,
) : LabEventPayload

@Serializable
data class TransferOutcomePayload(
    val kind: String,
    val httpStatus: Int? = null,
    val respondedAtUtc: String,
    val reason: String? = null,
    val attempt: Int = 1,
    val synthetic: Boolean = true,
) : LabEventPayload

@Serializable
data class CommandPayload(
    val wireName: String,
    val outcome: String,
    val reason: String? = null,
    val synthetic: Boolean = true,
) : LabEventPayload

@Serializable
data class MarkerPayload(
    val path: String,
    val writtenAtUtc: String,
    val synthetic: Boolean = true,
) : LabEventPayload

@Serializable
data class StatusPayload(
    val activeSession: String? = null,
    val endpoint: String,
    val labEnabled: Boolean,
    val retryEnabled: Boolean,
    val message: String,
    val synthetic: Boolean = true,
) : LabEventPayload

/**
 * Archived copy of the notification DailySpend posted about its own security test. `posted` records
 * whether the operating system accepted it, so a missing tray entry is still explainable evidence.
 */
@Serializable
data class LabNotificationPayload(
    val title: String,
    val message: String,
    val channelId: String,
    val notificationId: Int,
    val postedAtUtc: String,
    val posted: Boolean,
    val httpStatus: Int? = null,
    val ownAppOnly: Boolean = true,
    val synthetic: Boolean = true,
) : LabEventPayload

/**
 * Written whenever the device call log is read for transmission. The record ids are the provider
 * ids only; the row shows which device entries went out, which were skipped as already sent, and what
 * the server replied.
 */
@Serializable
data class DeviceCallLogSyncPayload(
    val trigger: String,
    val recordCount: Int,
    val alreadySentCount: Int,
    val sentRecordIds: List<String>,
    val accepted: Int? = null,
    val duplicates: Int? = null,
    val outcome: String? = null,
    val source: String = "DEVICE",
    val synthetic: Boolean = false,
) : LabEventPayload
