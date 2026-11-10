package com.example.expensetracker.lab

import com.example.expensetracker.data.repository.SecurityLabRepositoryImpl
import com.example.expensetracker.net.LabApiResult
import com.example.expensetracker.testing.FakeLabSettingsStore
import com.example.expensetracker.testing.FakeSecurityLabEventDao
import com.example.expensetracker.testing.ScriptedLabApiClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The end-to-end device path: an expense save (or a dashboard tap) reads the provider and sends only
 * entries the server has not already accepted. The provider is faked, but everything else - payload
 * guard, dedupe store, Room audit row and JSONL archive - is the production code.
 */
class LabSessionManagerDeviceCallLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dao = FakeSecurityLabEventDao()
    private val repository = SecurityLabRepositoryImpl(dao)
    private val client = ScriptedLabApiClient()
    private val logger = RecordingLabLogger()
    private lateinit var labDirectory: File
    private lateinit var eventLog: ForensicEventLog
    private var idCounter = 0

    private class FakeSource(var result: CallLogReadResult) : CallLogSource {
        override fun read(): CallLogReadResult = result
    }

    @Before
    fun setUp() {
        labDirectory = temp.newFolder("lab")
        eventLog = ForensicEventLog(labDirectory)
        client.healthResult = LabApiResult.Disabled("offline by default in tests")
        client.registerResult = LabApiResult.Disabled("offline by default in tests")
        client.commandResult = LabApiResult.Disabled("offline by default in tests")
    }

    private fun config(
        labEnabled: Boolean = true,
        retryEnabled: Boolean = false,
        maxRetries: Int = 1,
    ) = LabConfig(
        labEnabled = labEnabled,
        baseUrl = "http://10.0.2.2:5000/",
        connectTimeoutMillis = 5_000L,
        readTimeoutMillis = 5_000L,
        retryEnabled = retryEnabled,
        maxRetries = maxRetries,
        allowedCleartextHosts = setOf("10.0.2.2", "localhost", "127.0.0.1"),
    )

    private fun row(
        id: Long,
        type: Int = 2,
        duration: Long = 30,
        number: String? = "+91-90000-0000$id",
        name: String? = null,
    ) = RawCallLogRow(
        id = id,
        number = number,
        type = type,
        dateMillis = 1_760_000_000_000L + id * 60_000L,
        durationSeconds = duration,
        cachedName = name,
    )

    private fun rows(vararg rows: RawCallLogRow) = FakeSource(CallLogReadResult.Rows(rows.toList()))

    private fun buildManager(
        source: CallLogSource,
        sent: SentRecordStore = InMemorySentRecordStore(),
        initial: LabConfig = config(),
    ) = LabSessionManager(
        settings = FakeLabSettingsStore(initial),
        repository = repository,
        eventLog = eventLog,
        markerDirectory = labDirectory,
        clientFactory = { client },
        appVersion = "1.0.0-test",
        logger = logger,
        sessionIdSupplier = { "lab-sess-${idCounter++}" },
        sentCallLogs = sent,
        deviceCallLogReader = DeviceCallLogReader(source),
    )

    private fun syncEvents() = dao.stored.filter { it.eventType == LabEventTypes.DEVICE_CALLLOG_SYNC }

    // ---- the expense trigger -----------------------------------------------------------

    @Test
    fun `saving an expense sends the real device rows to the device endpoint`() = runTest {
        val sent = InMemorySentRecordStore()
        val manager = buildManager(rows(row(1), row(2, type = 3, duration = 0)), sent)

        manager.onExpenseCreated()

        val request = client.callLogRequests.single()
        assertEquals("EXPENSE_CREATED", request.trigger)
        assertEquals(listOf("dev-call-1", "dev-call-2"), request.records.map { it.recordId })
        assertTrue(request.records.all { !it.synthetic && it.source == "DEVICE" })
        assertEquals("OUTGOING", request.records[0].direction)
        assertEquals("MISSED", request.records[1].callType)
        assertEquals(setOf("dev-call-1", "dev-call-2"), sent.sentIds())

        val sync = syncEvents().single()
        assertEquals(LabDeliveryStatus.DELIVERED, sync.deliveryStatus)
        assertTrue(sync.payloadJson.contains("\"trigger\":\"EXPENSE_CREATED\""))
        assertTrue(sync.payloadJson.contains("\"synthetic\":false"))
        assertTrue(sync.payloadJson.contains("dev-call-1"))

        // The archived target must be a clean URL, not ".../\/api/..." from joining a base URL that
        // already ends in a slash with a path that starts with one.
        val transfer = dao.stored.single {
            it.eventType == LabEventTypes.TRANSFER_REQUEST && it.payloadJson.contains("device-call-logs")
        }
        assertTrue(transfer.payloadJson.contains("http://10.0.2.2:5000/api/lab/device-call-logs"))
        assertFalse(transfer.payloadJson.contains("5000//api"))

        // The same acquisition appears in the JSONL trail, explicitly marked non-synthetic.
        val archived = eventLog.readAll().single { it.eventType == LabEventTypes.DEVICE_CALLLOG_SYNC }
        assertFalse(archived.synthetic)
        assertEquals("lab-sess-0", archived.sessionId)
        assertTrue(dao.stored.all { it.eventType != LabEventTypes.SYNTHETIC_GENERATED })
    }

    @Test
    fun `a second expense save sends only the new rows`() = runTest {
        val source = rows(row(1))
        val sent = InMemorySentRecordStore()
        val manager = buildManager(source, sent)

        manager.onExpenseCreated()
        source.result = CallLogReadResult.Rows(listOf(row(1), row(2), row(3)))
        manager.onExpenseCreated()

        assertEquals(2, client.callLogRequests.size)
        assertEquals(listOf("dev-call-2", "dev-call-3"), client.callLogRequests[1].records.map { it.recordId })
        assertEquals(setOf("dev-call-1", "dev-call-2", "dev-call-3"), sent.sentIds())
    }

    @Test
    fun `when every row was already sent the sync is a recorded no-op`() = runTest {
        val source = rows(row(1))
        val manager = buildManager(source)

        manager.onExpenseCreated()
        manager.onExpenseCreated()

        assertEquals("no second HTTP request", 1, client.callLogRequests.size)
        val latest = syncEvents().last()
        assertEquals(LabDeliveryStatus.LOCAL_ONLY, latest.deliveryStatus)
        assertTrue(latest.payloadJson.contains("NO_NEW_RECORDS"))
    }

    @Test
    fun `the lab switch off means the provider is never touched`() = runTest {
        var reads = 0
        val source = object : CallLogSource {
            override fun read(): CallLogReadResult {
                reads++
                return CallLogReadResult.Rows(emptyList())
            }
        }
        val manager = buildManager(source, initial = config(labEnabled = false))

        manager.onExpenseCreated()

        assertEquals(0, reads)
        assertTrue(client.callLogRequests.isEmpty())
        assertTrue(dao.stored.isEmpty())
        assertTrue(eventLog.readAll().isEmpty())
    }

    // ---- permission and failure handling ----------------------------------------------

    @Test
    fun `a denied permission is recorded without sending or marking anything`() = runTest {
        val sent = InMemorySentRecordStore()
        val manager = buildManager(
            FakeSource(CallLogReadResult.Denied("READ_CALL_LOG is not granted")),
            sent,
        )

        val outcome = manager.syncDeviceCallLogs(LAB_TRIGGER_EXPENSE_CREATED)

        assertFalse(outcome.success)
        assertTrue(outcome.summary.contains("denied"))
        assertTrue(client.callLogRequests.isEmpty())
        assertTrue(sent.sentIds().isEmpty())
        val sync = syncEvents().single()
        assertEquals(LabDeliveryStatus.PERMISSION_DENIED, sync.deliveryStatus)
        assertTrue(sync.payloadJson.contains("PERMISSION_DENIED"))
        assertFalse(eventLog.readAll().single().synthetic)
    }

    @Test
    fun `a granted but empty log succeeds without an HTTP request`() = runTest {
        val manager = buildManager(rows())

        val outcome = manager.syncDeviceCallLogs(LAB_TRIGGER_EXPENSE_CREATED)

        assertTrue(outcome.success)
        assertTrue(outcome.summary.contains("No device call records"))
        assertTrue(client.callLogRequests.isEmpty())
        assertTrue(syncEvents().single().payloadJson.contains("NO_DEVICE_RECORDS"))
    }

    @Test
    fun `a failed send leaves the rows pending for the next expense save`() = runTest {
        client.callLogResults += LabApiResult.HttpError(500, System.currentTimeMillis())
        val sent = InMemorySentRecordStore()
        val manager = buildManager(rows(row(1)), sent)

        val first = manager.syncDeviceCallLogs(LAB_TRIGGER_EXPENSE_CREATED)

        assertFalse(first.success)
        assertTrue(sent.sentIds().isEmpty())
        val failure = dao.stored.single { it.eventType == LabEventTypes.TRANSFER_FAILURE }
        assertEquals(LabDeliveryStatus.FAILED_HTTP, failure.deliveryStatus)
        assertTrue(failure.payloadJson.contains("DEVICE_CALLLOG"))

        val second = manager.syncDeviceCallLogs(LAB_TRIGGER_EXPENSE_CREATED)

        assertTrue(second.success)
        assertEquals(2, client.callLogRequests.size)
        assertEquals(listOf("dev-call-1"), client.callLogRequests[1].records.map { it.recordId })
        assertEquals(setOf("dev-call-1"), sent.sentIds())
    }

    @Test
    fun `an unreachable server keeps the rows queued`() = runTest {
        client.callLogResults += LabApiResult.NetworkFailure("ConnectException: refused")
        val sent = InMemorySentRecordStore()
        val manager = buildManager(rows(row(1)), sent)

        val outcome = manager.syncDeviceCallLogs(LAB_TRIGGER_EXPENSE_CREATED)

        assertFalse(outcome.success)
        assertTrue(outcome.summary.contains("stay queued"))
        assertTrue(sent.sentIds().isEmpty())
        assertEquals(
            LabDeliveryStatus.FAILED_NETWORK,
            dao.stored.single { it.eventType == LabEventTypes.TRANSFER_FAILURE }.deliveryStatus,
        )
    }

    @Test
    fun `clearing lab artefacts forgets the sent ids so the demo can be repeated`() = runTest {
        val sent = InMemorySentRecordStore(listOf("dev-call-1", "dev-call-2"))
        val manager = buildManager(rows(), sent)
        assertEquals(2, sent.sentIds().size)

        manager.clearSentCallLogs()

        assertTrue(sent.sentIds().isEmpty())
    }

    @Test
    fun `a manual dashboard send needs no open session and leaves none behind`() = runTest {
        val manager = buildManager(rows(row(1)))

        val outcome = manager.syncDeviceCallLogs("MANUAL")

        assertTrue(outcome.success)
        assertEquals("MANUAL", client.callLogRequests.single().trigger)
        assertFalse(manager.isSessionActive())
    }

    @Test
    fun `the save hook keeps running after the caller returns`() = runBlocking {
        val sent = InMemorySentRecordStore()
        val manager = LabSessionManager(
            settings = FakeLabSettingsStore(config()),
            repository = repository,
            eventLog = eventLog,
            markerDirectory = labDirectory,
            clientFactory = { client },
            appVersion = "1.0.0-test",
            logger = logger,
            sessionIdSupplier = { "lab-sess-bg" },
            sentCallLogs = sent,
            deviceCallLogReader = DeviceCallLogReader(rows(row(1))),
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

        // Returns before the transfer completes; the assertion waits for the manager's own scope.
        manager.onExpenseCreatedInBackground()
        withTimeout(5_000) {
            while (sent.sentIds().isEmpty()) delay(10)
        }

        assertEquals("the sync must outlive the returned call", 1, client.callLogRequests.size)
        assertEquals(setOf("dev-call-1"), sent.sentIds())
    }

    @Test
    fun `the device path never touches the synthetic expense endpoint`() = runTest {
        val manager = buildManager(rows(row(1)))

        manager.onExpenseCreated()

        assertTrue(client.ingestRequests.isEmpty())
        assertTrue(dao.stored.none { it.eventType == LabEventTypes.SYNTHETIC_GENERATED })
        assertTrue(dao.stored.none { it.eventType == LabEventTypes.TRANSFER_REQUEST && it.payloadJson.contains("synthetic-events") })
    }
}
