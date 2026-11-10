package com.example.expensetracker.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire contract for the Python Flask lab server. Five endpoints: health, session registration,
 * synthetic-expense ingestion, allowlisted command retrieval and device call-log ingestion. No endpoint
 * accepts or returns anything the client executes.
 */
object LabEndpoints {
    const val HEALTH = "/api/health"
    const val SESSION_REGISTER = "/api/lab/sessions"
    const val EVENT_INGEST = "/api/lab/synthetic-events"
    const val COMMAND_FETCH = "/api/lab/command"
    const val DEVICE_CALL_LOG_INGEST = "/api/lab/device-call-logs"
}

@Serializable
data class HealthResponse(
    @SerialName("status") val status: String = "",
    @SerialName("lab_mode") val labMode: Boolean = false,
    @SerialName("server_time_utc") val serverTimeUtc: String? = null,
)

@Serializable
data class SessionRegisterRequest(
    @SerialName("session_id") val sessionId: String,
    @SerialName("started_at_utc") val startedAtUtc: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("lab_build") val labBuild: Boolean,
)

@Serializable
data class SessionRegisterResponse(
    @SerialName("accepted") val accepted: Boolean = false,
    @SerialName("lab_run_id") val labRunId: String? = null,
)

@Serializable
data class SyntheticEventPayload(
    @SerialName("event_id") val eventId: String,
    @SerialName("synthetic") val synthetic: Boolean,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("currency") val currencyCode: String,
    @SerialName("category") val categoryKey: String,
    @SerialName("description") val description: String,
    @SerialName("occurred_at_utc") val occurredAtUtc: String,
)

@Serializable
data class SyntheticIngestRequestDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("prepared_at_utc") val preparedAtUtc: String,
    @SerialName("events") val events: List<SyntheticEventPayload>,
)

@Serializable
data class SyntheticIngestResponse(
    @SerialName("received") val received: Int = 0,
    @SerialName("status") val status: String = "",
)

/** One real device call-log entry on the wire. [synthetic] is always false. */
@Serializable
data class DeviceCallLogPayload(
    @SerialName("record_id") val recordId: String,
    @SerialName("number") val number: String,
    @SerialName("direction") val direction: String,
    @SerialName("call_type") val callType: String,
    @SerialName("started_at_utc") val startedAtUtc: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("contact_name") val contactName: String? = null,
    @SerialName("source") val source: String,
    @SerialName("synthetic") val synthetic: Boolean,
)

@Serializable
data class DeviceCallLogIngestRequestDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("prepared_at_utc") val preparedAtUtc: String,
    @SerialName("trigger") val trigger: String,
    @SerialName("records") val records: List<DeviceCallLogPayload>,
)

@Serializable
data class DeviceCallLogIngestResponse(
    @SerialName("accepted") val accepted: Int = 0,
    @SerialName("duplicates") val duplicates: Int = 0,
    @SerialName("status") val status: String = "",
)

@Serializable
data class LabCommandRequest(
    @SerialName("session_id") val sessionId: String,
)

/**
 * Server response for the command channel. [parameters] is intentionally ignored by the client: the
 * allowlisted actions take no arguments, which is what keeps this from becoming a remote command
 * channel.
 */
@Serializable
data class LabCommandResponse(
    @SerialName("command") val command: String? = null,
    @SerialName("issued_at_utc") val issuedAtUtc: String? = null,
    @SerialName("parameters") val parameters: Map<String, String> = emptyMap(),
)

/** Uniform outcome of a lab API call so callers never have to catch network exceptions. */
sealed interface LabApiResult<out T> {
    data class Success<T>(val httpStatus: Int, val value: T, val completedAtUtcMillis: Long) : LabApiResult<T>
    data class HttpError(val httpStatus: Int, val completedAtUtcMillis: Long) : LabApiResult<Nothing>
    data class NetworkFailure(val reason: String) : LabApiResult<Nothing>
    data class Timeout(val reason: String) : LabApiResult<Nothing>
    data class InvalidPayload(val reason: String) : LabApiResult<Nothing>
    data class Disabled(val reason: String) : LabApiResult<Nothing>
}

/** Snapshot of the last attempted call, surfaced on the Settings screen. */
data class LabConnectionSnapshot(
    val reachable: Boolean,
    val detail: String,
    val lastAttemptUtcMillis: Long?,
    val endpoint: String,
)
