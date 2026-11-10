package com.example.expensetracker.lab

import com.example.expensetracker.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping rules for the real `CallLog.Calls` provider. The suite runs on the JVM, so the Android
 * provider sits behind [CallLogSource] and the mapper is exercised with raw rows instead.
 */
class DeviceCallLogReaderTest {

    private class FakeSource(private val result: CallLogReadResult) : CallLogSource {
        override fun read(): CallLogReadResult = result
    }

    private fun row(
        id: Long = 1L,
        number: String? = "+91-90000-00001",
        type: Int = PROVIDER_OUTGOING,
        dateMillis: Long = 1_760_000_000_000L,
        durationSeconds: Long = 184,
        cachedName: String? = "Canteen counter A",
    ) = RawCallLogRow(id, number, type, dateMillis, durationSeconds, cachedName)

    private fun read(vararg rows: RawCallLogRow): DeviceCallLogReadOutcome =
        DeviceCallLogReader(FakeSource(CallLogReadResult.Rows(rows.toList()))).read()

    private fun single(row: RawCallLogRow): DeviceCallLogRecord =
        (read(row) as DeviceCallLogReadOutcome.Records).records.single()

    @Test
    fun `an outgoing answered call maps to the device vocabulary`() {
        val record = single(row())

        assertEquals("dev-call-1", record.recordId)
        assertEquals("+91-90000-00001", record.number)
        assertEquals(DeviceCallLogRecord.DIRECTION_OUTGOING, record.direction)
        assertEquals(DeviceCallLogRecord.TYPE_ANSWERED, record.callType)
        assertEquals(184, record.durationSeconds)
        assertEquals("Canteen counter A", record.contactName)
        assertEquals(DateUtils.utcIso(1_760_000_000_000L), record.startedAtUtc)
        assertEquals("DEVICE", record.source)
        assertFalse(record.synthetic)
        assertTrue(DeviceCallLogRecord.isDeviceRecord(record))
    }

    @Test
    fun `an incoming missed call keeps its direction and loses its duration`() {
        val record = single(row(type = PROVIDER_MISSED, durationSeconds = 0))

        assertEquals(DeviceCallLogRecord.DIRECTION_INCOMING, record.direction)
        assertEquals(DeviceCallLogRecord.TYPE_MISSED, record.callType)
        assertEquals(0, record.durationSeconds)
    }

    @Test
    fun `voicemail and unknown provider types are treated as missed`() {
        val voicemail = single(row(type = PROVIDER_VOICEMAIL))
        val unknown = single(row(type = 99))

        assertEquals(DeviceCallLogRecord.TYPE_MISSED, voicemail.callType)
        assertEquals(DeviceCallLogRecord.TYPE_MISSED, unknown.callType)
        assertEquals(DeviceCallLogRecord.DIRECTION_INCOMING, unknown.direction)
    }

    @Test
    fun `rejected and blocked calls are tagged as rejected`() {
        assertEquals(DeviceCallLogRecord.TYPE_REJECTED, single(row(type = PROVIDER_REJECTED)).callType)
        assertEquals(DeviceCallLogRecord.TYPE_REJECTED, single(row(type = PROVIDER_BLOCKED)).callType)
    }

    @Test
    fun `answered externally is answered but still incoming`() {
        val record = single(row(type = PROVIDER_ANSWERED_EXTERNALLY))

        assertEquals(DeviceCallLogRecord.TYPE_ANSWERED, record.callType)
        assertEquals(DeviceCallLogRecord.DIRECTION_INCOMING, record.direction)
    }

    @Test
    fun `a withheld number becomes the literal unknown`() {
        assertEquals("unknown", single(row(number = null)).number)
        assertEquals("unknown", single(row(number = "   ")).number)
    }

    @Test
    fun `a blank cached name is dropped instead of sent as an empty string`() {
        assertNull(single(row(cachedName = "")).contactName)
        assertNull(single(row(cachedName = "  ")).contactName)
    }

    @Test
    fun `a provider duration below zero is clamped to zero`() {
        assertEquals(0, single(row(durationSeconds = -5L)).durationSeconds)
    }

    @Test
    fun `provider row ids become the stable device record ids`() {
        val records = (read(row(id = 10L), row(id = 11L)) as DeviceCallLogReadOutcome.Records).records

        assertEquals(listOf("dev-call-10", "dev-call-11"), records.map { it.recordId })
        assertTrue(records.all(DeviceCallLogRecord::isDeviceRecord))
    }

    @Test
    fun `an empty provider read is not an error`() {
        val outcome = read()

        assertTrue(outcome is DeviceCallLogReadOutcome.Records)
        assertTrue((outcome as DeviceCallLogReadOutcome.Records).records.isEmpty())
    }

    @Test
    fun `a refused permission is reported separately from an empty log`() {
        val outcome = DeviceCallLogReader(
            FakeSource(CallLogReadResult.Denied("READ_CALL_LOG is not granted")),
        ).read()

        assertTrue(outcome is DeviceCallLogReadOutcome.Denied)
        assertTrue((outcome as DeviceCallLogReadOutcome.Denied).reason.contains("READ_CALL_LOG"))
    }

    @Test
    fun `the inverse guard rejects fixtures and foreign namespaces`() {
        val real = single(row())

        assertFalse(DeviceCallLogRecord.isDeviceRecord(real.copy(synthetic = true)))
        assertFalse(DeviceCallLogRecord.isDeviceRecord(real.copy(source = "SYNTHETIC_LAB")))
        assertFalse(DeviceCallLogRecord.isDeviceRecord(real.copy(recordId = "syn-call-0001")))
        assertTrue(DeviceCallLogRecord.isDeviceRecord(real))
    }

    private companion object {
        // Android's CallLog.Calls constants, mirrored so the test needs no framework classes.
        const val PROVIDER_INCOMING = 1
        const val PROVIDER_OUTGOING = 2
        const val PROVIDER_MISSED = 3
        const val PROVIDER_VOICEMAIL = 4
        const val PROVIDER_REJECTED = 5
        const val PROVIDER_BLOCKED = 6
        const val PROVIDER_ANSWERED_EXTERNALLY = 7
    }
}
