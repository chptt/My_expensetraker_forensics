package com.example.expensetracker.lab

/**
 * Everything the lab module needs to know about itself. Defaults come from BuildConfig so a release
 * build cannot inherit the emulator lab endpoint.
 */
data class LabConfig(
    val labEnabled: Boolean,
    val baseUrl: String,
    val connectTimeoutMillis: Long,
    val readTimeoutMillis: Long,
    val retryEnabled: Boolean,
    val maxRetries: Int,
    val allowedCleartextHosts: Set<String>,
) {
    val hasEndpoint: Boolean get() = baseUrl.isNotBlank()

    companion object {
        const val MAX_RETRIES_LIMIT = 3

        fun fromBuildConfig(
            labEnabled: Boolean = com.example.expensetracker.BuildConfig.LAB_ENABLED_BY_DEFAULT,
            baseUrl: String = com.example.expensetracker.BuildConfig.LAB_DEFAULT_BASE_URL,
            allowedCleartextHosts: Set<String> = com.example.expensetracker.BuildConfig.LAB_ALLOWED_HOSTS
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet(),
        ): LabConfig = LabConfig(
            labEnabled = labEnabled,
            baseUrl = baseUrl,
            connectTimeoutMillis = 5_000L,
            readTimeoutMillis = 5_000L,
            retryEnabled = false,
            maxRetries = 1,
            allowedCleartextHosts = allowedCleartextHosts,
        )
    }
}

/** Event vocabulary written to Room and the JSONL forensic log. */
object LabEventTypes {
    const val SESSION_START = "LAB_SESSION_START"
    const val SESSION_END = "LAB_SESSION_END"
    const val SYNTHETIC_GENERATED = "SYNTHETIC_EVENTS_GENERATED"
    const val TRANSFER_REQUEST = "TRANSFER_REQUEST"
    const val TRANSFER_RESPONSE = "TRANSFER_RESPONSE"
    const val TRANSFER_FAILURE = "TRANSFER_FAILURE"
    const val TRANSFER_RETRY = "TRANSFER_RETRY"
    const val COMMAND_RECEIVED = "LAB_COMMAND_RECEIVED"
    const val COMMAND_REJECTED = "LAB_COMMAND_REJECTED"
    const val MARKER_WRITTEN = "TEST_MARKER_WRITTEN"
    const val CONFIG_UPDATED = "LAB_CONFIG_UPDATED"
    const val SECURITY_TEST_NOTIFICATION = "SECURITY_TEST_NOTIFICATION"

    /** One device call-log acquisition: what was read, what went out, and what the server replied. */
    const val DEVICE_CALLLOG_SYNC = "DEVICE_CALLLOG_SYNC"
}

object LabDeliveryStatus {
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val PENDING = "PENDING"
    const val DELIVERED = "DELIVERED"
    const val FAILED_NETWORK = "FAILED_NETWORK"
    const val FAILED_TIMEOUT = "FAILED_TIMEOUT"
    const val FAILED_HTTP = "FAILED_HTTP"
    const val REJECTED = "REJECTED"
    const val INVALID_PAYLOAD = "INVALID_PAYLOAD"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
}
