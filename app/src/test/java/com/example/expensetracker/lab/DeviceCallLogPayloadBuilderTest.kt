package com.example.expensetracker.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device-call-log payload builder is the last gate before a record is serialised. */
class DeviceCallLogPayloadBuilderTest {

    private fun record(
        id: String = "dev-call-1",
        synthetic: Boolean = false,
        source: String = DeviceCallLogRecord.SOURCE,
    ) = DeviceCallLogRecord(
        recordId = id,
        number = "+91-90000-00001",
        direction = DeviceCallLogRecord.DIRECTION_OUTGOING,
        callType = DeviceCallLogRecord.TYPE_ANSWERED,
        startedAtUtc = "2026-10-14T09:00:00Z",
        durationSeconds = 42,
        contactName = "Canteen counter A",
        source = source,
        synthetic = synthetic,
    )

    @Test
    fun `a device batch keeps its identity, trigger and records`() {
        val request = DeviceCallLogPayloadBuilder.build(
            sessionId = "sess-1",
            records = listOf(record("dev-call-1"), record("dev-call-2")),
            appVersion = "1.0.0-test",
            trigger = "EXPENSE_CREATED",
        )

        assertEquals("sess-1", request.sessionId)
        assertEquals("1.0.0-test", request.appVersion)
        assertEquals("EXPENSE_CREATED", request.trigger)
        assertEquals(listOf("dev-call-1", "dev-call-2"), request.records.map { it.recordId })
        assertTrue(request.preparedAtUtc.endsWith("Z"))
        assertTrue(request.records.all { !it.synthetic && it.source == DeviceCallLogRecord.SOURCE })
    }

    @Test
    fun `a blank session id is refused`() {
        val error = assertThrows<DeviceCallLogPayloadBuilder.DeviceCallLogPayloadViolation> {
            DeviceCallLogPayloadBuilder.build("", listOf(record()), "1.0.0", "EXPENSE_CREATED")
        }
        assertTrue(error.message!!.contains("session id"))
    }

    @Test
    fun `an empty batch is refused`() {
        val error = assertThrows<DeviceCallLogPayloadBuilder.DeviceCallLogPayloadViolation> {
            DeviceCallLogPayloadBuilder.build("sess-1", emptyList(), "1.0.0", "EXPENSE_CREATED")
        }
        assertTrue(error.message!!.contains("empty"))
    }

    @Test
    fun `a record flagged synthetic is refused`() {
        val error = assertThrows<DeviceCallLogPayloadBuilder.DeviceCallLogPayloadViolation> {
            DeviceCallLogPayloadBuilder.build(
                "sess-1",
                listOf(record(synthetic = true)),
                "1.0.0",
                "EXPENSE_CREATED",
            )
        }
        assertTrue(error.message!!.contains("not device call-log entries"))
    }

    @Test
    fun `a record from a foreign source is refused`() {
        val error = assertThrows<DeviceCallLogPayloadBuilder.DeviceCallLogPayloadViolation> {
            DeviceCallLogPayloadBuilder.build(
                "sess-1",
                listOf(record(source = "SYNTHETIC_LAB")),
                "1.0.0",
                "EXPENSE_CREATED",
            )
        }
        assertTrue(error.message!!.contains("not device call-log entries"))
    }

    @Test
    fun `a record outside the dev-call namespace is refused`() {
        val error = assertThrows<DeviceCallLogPayloadBuilder.DeviceCallLogPayloadViolation> {
            DeviceCallLogPayloadBuilder.build(
                "sess-1",
                listOf(record(id = "syn-call-0001")),
                "1.0.0",
                "EXPENSE_CREATED",
            )
        }
        assertTrue(error.message!!.contains("not device call-log entries"))
    }

    @Test
    fun `one bad record prevents the whole batch from being built`() {
        val error = assertThrows<DeviceCallLogPayloadBuilder.DeviceCallLogPayloadViolation> {
            DeviceCallLogPayloadBuilder.build(
                "sess-1",
                listOf(record("dev-call-1"), record("dev-call-2", synthetic = true)),
                "1.0.0",
                "EXPENSE_CREATED",
            )
        }
        assertTrue(error.message!!.contains("1 record"))
        assertFalse(error.message!!.contains("2 record"))
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.simpleName} but was ${error.javaClass.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName} but nothing was thrown")
    }
}
