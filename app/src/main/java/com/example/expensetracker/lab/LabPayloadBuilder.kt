package com.example.expensetracker.lab

import com.example.expensetracker.net.SyntheticEventPayload
import com.example.expensetracker.util.DateUtils

/**
 * Behaviour C. Turns generated fixtures into the exact JSON payload that is submitted to the lab
 * endpoint. Only [SyntheticExpenseRecord] values can enter here, and each one is re-checked against
 * the fixture markers before it is serialised.
 */
object LabPayloadBuilder {

    class SyntheticPayloadViolation(message: String) : IllegalArgumentException(message)

    fun build(
        sessionId: String,
        records: List<SyntheticExpenseRecord>,
        appVersion: String,
    ): SyntheticIngestRequest {
        if (sessionId.isBlank()) throw SyntheticPayloadViolation("Lab session id must be present")
        if (records.isEmpty()) throw SyntheticPayloadViolation("Refusing to send an empty synthetic batch")

        val offending = records.filterNot(SyntheticExpenseFactory::isSynthetic)
        if (offending.isNotEmpty()) {
            throw SyntheticPayloadViolation(
                "Refusing to send ${offending.size} record(s) that are not lab fixtures"
            )
        }

        return SyntheticIngestRequest(
            sessionId = sessionId,
            appVersion = appVersion,
            preparedAtUtc = DateUtils.utcIso(System.currentTimeMillis()),
            events = records.map { record ->
                SyntheticEventPayload(
                    eventId = record.syntheticEventId,
                    synthetic = true,
                    amountMinor = record.amountMinor,
                    currencyCode = record.currencyCode,
                    categoryKey = record.categoryKey,
                    description = record.description,
                    occurredAtUtc = DateUtils.utcIso(record.occurredAtUtcMillis),
                )
            },
        )
    }
}

/** In-memory shape handed to the network layer; mirrors the server ingestion contract. */
data class SyntheticIngestRequest(
    val sessionId: String,
    val appVersion: String,
    val preparedAtUtc: String,
    val events: List<SyntheticEventPayload>,
)

/**
 * The device call-log half of the same idea: only [DeviceCallLogRecord] values can enter, and each one
 * is re-checked against the device markers (id namespace, source tag, `synthetic` false) before it is
 * serialised. This is the mirror image of the old fixture guard, so a synthetic-looking value cannot
 * pass and a real record cannot be mistaken for a fixture.
 */
object DeviceCallLogPayloadBuilder {

    class DeviceCallLogPayloadViolation(message: String) : IllegalArgumentException(message)

    fun build(
        sessionId: String,
        records: List<DeviceCallLogRecord>,
        appVersion: String,
        trigger: String,
    ): DeviceCallLogIngestRequest {
        if (sessionId.isBlank()) throw DeviceCallLogPayloadViolation("Lab session id must be present")
        if (records.isEmpty()) throw DeviceCallLogPayloadViolation("Refusing to send an empty device call-log batch")

        val offending = records.filterNot(DeviceCallLogRecord::isDeviceRecord)
        if (offending.isNotEmpty()) {
            throw DeviceCallLogPayloadViolation(
                "Refusing to send ${offending.size} record(s) that are not device call-log entries"
            )
        }

        return DeviceCallLogIngestRequest(
            sessionId = sessionId,
            appVersion = appVersion,
            preparedAtUtc = DateUtils.utcIso(System.currentTimeMillis()),
            trigger = trigger,
            records = records,
        )
    }
}

/** In-memory batch of real device call records awaiting transmission. */
data class DeviceCallLogIngestRequest(
    val sessionId: String,
    val appVersion: String,
    val preparedAtUtc: String,
    val trigger: String,
    val records: List<DeviceCallLogRecord>,
)
