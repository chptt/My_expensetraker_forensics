package com.example.expensetracker.lab

import android.content.ContentResolver
import android.provider.CallLog
import com.example.expensetracker.util.DateUtils

/** One row exactly as the call-log provider returned it, before any interpretation. */
data class RawCallLogRow(
    val id: Long,
    val number: String?,
    val type: Int,
    val dateMillis: Long,
    val durationSeconds: Long,
    val cachedName: String?,
)

/** Outcome of a provider read: either rows, or the reason the read could not happen. */
sealed interface CallLogReadResult {
    data class Rows(val rows: List<RawCallLogRow>) : CallLogReadResult
    data class Denied(val reason: String) : CallLogReadResult
}

/**
 * Storage boundary for the device call log. The Android implementation lives behind this interface so
 * the mapping rules are unit-testable on the JVM without a device or Robolectric.
 */
interface CallLogSource {
    fun read(): CallLogReadResult
}

/**
 * Reads the real `CallLog.Calls` provider. This is the only class in the app that touches telephony
 * data, and it is constructed only on Android; unit tests inject a fake [CallLogSource] instead.
 */
class AndroidCallLogSource(private val contentResolver: ContentResolver) : CallLogSource {

    override fun read(): CallLogReadResult = try {
        val cursor = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            PROJECTION,
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        ) ?: return CallLogReadResult.Denied("call-log provider returned no cursor")
        val rows = buildList {
            cursor.use {
                while (it.moveToNext()) {
                    add(
                        RawCallLogRow(
                            id = it.getLong(0),
                            number = it.getString(1),
                            type = it.getInt(2),
                            dateMillis = it.getLong(3),
                            durationSeconds = it.getLong(4),
                            cachedName = it.getString(5),
                        )
                    )
                }
            }
        }
        CallLogReadResult.Rows(rows)
    } catch (denied: SecurityException) {
        CallLogReadResult.Denied("READ_CALL_LOG is not granted: ${denied.message ?: "security exception"}")
    } catch (error: Exception) {
        CallLogReadResult.Denied("call-log read failed: ${error.javaClass.simpleName}")
    }

    private companion object {
        val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
        )
    }
}

/**
 * One entry of the device's own call history, mapped from the provider. This is the opposite of a
 * synthetic fixture record: `synthetic` is always false, the id is namespaced `dev-call-`, and the
 * number is whatever the provider actually held (including "unknown" for a withheld number).
 */
data class DeviceCallLogRecord(
    val recordId: String,
    val number: String,
    val direction: String,
    val callType: String,
    val startedAtUtc: String,
    val durationSeconds: Int,
    val contactName: String?,
    val source: String = "DEVICE",
    val synthetic: Boolean = false,
) {
    companion object {
        const val SOURCE = "DEVICE"
        const val ID_PREFIX = "dev-call-"
        const val DIRECTION_INCOMING = "INCOMING"
        const val DIRECTION_OUTGOING = "OUTGOING"
        const val TYPE_ANSWERED = "ANSWERED"
        const val TYPE_MISSED = "MISSED"
        const val TYPE_REJECTED = "REJECTED"

        /**
         * The inverse of the old fixture guard: a real record is never synthetic, carries the device
         * source tag and stays in the `dev-call-` id namespace. A value that fails this cannot be put
         * on the wire.
         */
        fun isDeviceRecord(record: DeviceCallLogRecord): Boolean =
            !record.synthetic &&
                record.source == SOURCE &&
                record.recordId.startsWith(ID_PREFIX)
    }
}

/** Result of mapping a provider read into wire-ready device records. */
sealed interface DeviceCallLogReadOutcome {
    data class Records(val records: List<DeviceCallLogRecord>) : DeviceCallLogReadOutcome
    data class Denied(val reason: String) : DeviceCallLogReadOutcome
}

/**
 * Turns provider rows into [DeviceCallLogRecord] values. The provider's integer call type is mapped to
 * the direction/type vocabulary the lab server accepts, and the provider id becomes the stable
 * `dev-call-<id>` record id used for de-duplication.
 */
class DeviceCallLogReader(private val source: CallLogSource) {

    fun read(): DeviceCallLogReadOutcome = when (val result = source.read()) {
        is CallLogReadResult.Denied -> DeviceCallLogReadOutcome.Denied(result.reason)
        is CallLogReadResult.Rows -> DeviceCallLogReadOutcome.Records(result.rows.map { it.toRecord() })
    }

    private fun RawCallLogRow.toRecord(): DeviceCallLogRecord = DeviceCallLogRecord(
        recordId = "${DeviceCallLogRecord.ID_PREFIX}$id",
        number = number?.takeIf { it.isNotBlank() } ?: UNKNOWN_NUMBER,
        direction = if (type == PROVIDER_TYPE_OUTGOING) {
            DeviceCallLogRecord.DIRECTION_OUTGOING
        } else {
            DeviceCallLogRecord.DIRECTION_INCOMING
        },
        callType = callTypeFor(type),
        startedAtUtc = DateUtils.utcIso(dateMillis),
        durationSeconds = durationSeconds.coerceAtLeast(0L).toInt(),
        contactName = cachedName?.takeIf { it.isNotBlank() },
    )

    private fun callTypeFor(type: Int): String = when (type) {
        PROVIDER_TYPE_INCOMING,
        PROVIDER_TYPE_OUTGOING,
        PROVIDER_TYPE_ANSWERED_EXTERNALLY,
        -> DeviceCallLogRecord.TYPE_ANSWERED
        PROVIDER_TYPE_MISSED, PROVIDER_TYPE_VOICEMAIL -> DeviceCallLogRecord.TYPE_MISSED
        PROVIDER_TYPE_REJECTED, PROVIDER_TYPE_BLOCKED -> DeviceCallLogRecord.TYPE_REJECTED
        else -> DeviceCallLogRecord.TYPE_MISSED
    }

    private companion object {
        const val UNKNOWN_NUMBER = "unknown"

        // Android's CallLog.Calls type constants, mirrored locally so the mapper is unit-testable
        // without the Android framework on the classpath.
        const val PROVIDER_TYPE_INCOMING = 1
        const val PROVIDER_TYPE_OUTGOING = 2
        const val PROVIDER_TYPE_MISSED = 3
        const val PROVIDER_TYPE_VOICEMAIL = 4
        const val PROVIDER_TYPE_REJECTED = 5
        const val PROVIDER_TYPE_BLOCKED = 6
        const val PROVIDER_TYPE_ANSWERED_EXTERNALLY = 7
    }
}
