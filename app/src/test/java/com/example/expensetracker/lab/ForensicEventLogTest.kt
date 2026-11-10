package com.example.expensetracker.lab

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ForensicEventLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var directory: File
    private var clock = 1_760_000_000_000L
    private var ids = 0

    private fun newLog(maxRecords: Int = 500, maxBytes: Long = 256_000L) = ForensicEventLog(
        directory = directory,
        clock = { clock },
        idSupplier = { "evt-${ids++}" },
        maxRecords = maxRecords,
        maxBytes = maxBytes,
    )

    @Before
    fun setUp() {
        directory = temp.newFolder("lab_events")
        ids = 0
    }

    @Test
    fun `an empty log reads back as no records and no bytes`() {
        val log = newLog()
        assertTrue(log.readAll().isEmpty())
        assertEquals(0L, log.sizeBytes())
        assertFalse(log.file.exists())
    }

    @Test
    fun `appends are structured json lines with the required fields`() {
        val log = newLog()
        log.append(
            sessionId = "sess-1",
            eventType = LabEventTypes.SESSION_START,
            synthetic = true,
            requestStatus = LabDeliveryStatus.LOCAL_ONLY,
            detail = "version=1.0.0",
        )
        clock += 60_000L
        log.append(
            sessionId = "sess-1",
            eventType = LabEventTypes.TRANSFER_FAILURE,
            synthetic = true,
            requestStatus = LabDeliveryStatus.FAILED_NETWORK,
        )

        val lines = log.file.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        lines.forEach { assertTrue(it.startsWith("{") && it.endsWith("}")) }

        val first = lines.first()
        assertTrue(first, first.contains("\"eventId\":\"evt-0\""))
        assertTrue(first, first.contains("\"sessionId\":\"sess-1\""))
        assertTrue(first, first.contains("\"eventType\":\"LAB_SESSION_START\""))
        assertTrue(first, first.contains("\"timestampUtc\":\"2025-10-09T08:53:20Z\""))
        assertTrue(first, first.contains("\"synthetic\":true"))
        assertTrue(first, first.contains("\"requestStatus\":\"LOCAL_ONLY\""))
        assertTrue(first, first.contains("\"detail\":\"version=1.0.0\""))
        assertEquals(LabEventTypes.TRANSFER_FAILURE, log.readAll()[1].eventType)
        assertEquals(LabDeliveryStatus.FAILED_NETWORK, log.readAll()[1].requestStatus)
    }

    @Test
    fun `records are capped so a burst cannot grow the file without bound`() {
        val log = newLog(maxRecords = 10)
        repeat(25) { index ->
            clock += 1_000L
            log.append("sess-cap", "EVENT_$index", synthetic = true)
        }

        val records = log.readAll()
        assertEquals(10, records.size)
        // The newest records survive trimming; the oldest ones are dropped.
        assertEquals("EVENT_15", records.first().eventType)
        assertEquals("EVENT_24", records.last().eventType)
    }

    @Test
    fun `the byte cap rotates the file once and keeps writing`() {
        val log = newLog(maxRecords = 1_000, maxBytes = 400L)
        repeat(12) { index ->
            clock += 1_000L
            log.append("sess-rot", "ROTATE_ME_$index", synthetic = true, detail = "x".repeat(40))
        }

        val rotated = File(directory, ForensicEventLog.ROTATED_FILE_NAME)
        assertTrue("expected a rotation file at ${rotated.path}", rotated.exists())
        val active = log.readAll()
        assertTrue(active.isNotEmpty())
        assertTrue(active.all { it.sessionId == "sess-rot" })
        assertTrue(log.sizeBytes() < 100_000L)
    }

    @Test
    fun `long details are truncated so one row cannot dominate the log`() {
        val log = newLog()
        val record = log.append(
            sessionId = "sess-1",
            eventType = "DETAIL_TEST",
            synthetic = true,
            detail = "z".repeat(10_000),
        )
        assertEquals(ForensicEventLog.MAX_DETAIL_LENGTH, record.detail?.length)
        assertEquals(ForensicEventLog.MAX_DETAIL_LENGTH, log.readAll().single().detail?.length)
    }

    @Test
    fun `clearing removes both the active and rotated files`() {
        val log = newLog(maxRecords = 2, maxBytes = 120L)
        repeat(8) {
            clock += 1_000L
            log.append("sess-clear", "EVENT", synthetic = true, detail = "y".repeat(30))
        }
        assertTrue(log.readAll().isNotEmpty())
        assertTrue(File(directory, ForensicEventLog.ROTATED_FILE_NAME).exists())

        log.clear()

        assertTrue(log.readAll().isEmpty())
        assertEquals(0L, log.sizeBytes())
        assertFalse(log.file.exists())
        assertFalse(File(directory, ForensicEventLog.ROTATED_FILE_NAME).exists())
    }

    @Test
    fun `timestamps are recorded in utc and reuse the injected clock`() {
        val log = newLog()
        clock = 1_700_000_000_000L
        val record = log.append("sess-utc", "STAMP", synthetic = true)
        assertEquals("2023-11-14T22:13:20Z", record.timestampUtc)
        assertEquals(record.timestampUtc, log.readAll().single().timestampUtc)
    }

    @Test
    fun `a malformed line is skipped instead of failing the whole read`() {
        val log = newLog()
        log.append("sess-1", "GOOD", synthetic = true)
        log.file.appendText("this is not json\n")
        log.append("sess-1", "GOOD_2", synthetic = true)

        assertEquals(listOf("GOOD", "GOOD_2"), log.readAll().map { it.eventType })
    }
}
