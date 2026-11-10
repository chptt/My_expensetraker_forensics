package com.example.expensetracker.lab

import com.example.expensetracker.data.repository.SecurityLabRepositoryImpl
import com.example.expensetracker.net.LabApiResult
import com.example.expensetracker.net.LabCommandResponse
import com.example.expensetracker.net.SyntheticIngestResponse
import com.example.expensetracker.testing.FakeLabSettingsStore
import com.example.expensetracker.testing.FakeSecurityLabEventDao
import com.example.expensetracker.testing.RecordingLabNotifier
import com.example.expensetracker.testing.ScriptedLabApiClient
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LabSessionManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dao = FakeSecurityLabEventDao()
    private val repository = SecurityLabRepositoryImpl(dao)
    private val client = ScriptedLabApiClient()
    private val logger = RecordingLabLogger()
    private val notifier = RecordingLabNotifier()
    private lateinit var labDirectory: File
    private lateinit var eventLog: ForensicEventLog
    private var idCounter = 0

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

    private fun buildManager(
        initial: LabConfig = config(),
        notifier: LabResultNotifier = this.notifier,
    ) = LabSessionManager(
        settings = FakeLabSettingsStore(initial),
        repository = repository,
        eventLog = eventLog,
        markerDirectory = labDirectory,
        clientFactory = { client },
        appVersion = "1.0.0-test",
        logger = logger,
        sessionIdSupplier = { "lab-sess-${idCounter++}" },
        notifier = notifier,
    )

    private fun eventsOfType(type: String): List<com.example.expensetracker.data.model.SecurityLabEvent> =
        dao.stored.filter { it.eventType == type }

    // ---- Behaviour A: session identity -----------------------------------------------

    @Test
    fun `starting a session mints an id and records it locally`() = runTest {
        val manager = buildManager()

        val outcome = manager.startSession()

        assertTrue(outcome.summary, outcome.success)
        assertNotNull(outcome.sessionId)
        assertTrue(manager.isSessionActive())
        assertEquals(outcome.sessionId, manager.activeSessionId)

        val start = eventsOfType(LabEventTypes.SESSION_START)
        assertEquals(1, start.size)
        assertEquals("lab-sess-0", start.single().sessionId)
        assertEquals(LabDeliveryStatus.LOCAL_ONLY, start.single().deliveryStatus)
        assertTrue(start.single().payloadJson.contains("\"syntheticOnly\":true"))
        assertTrue(start.single().payloadJson.contains("1.0.0-test"))

        // Logcat surface carries the session id and event type for the forensic capture.
        assertTrue(logger.lines.any { it.contains(LabEventTypes.SESSION_START) && it.contains("lab-sess-0") })
        assertEquals(1, eventLog.readAll().size)
        assertEquals("lab-sess-0", eventLog.readAll().single().sessionId)
    }

    @Test
    fun `session ids are unique`() = runTest {
        val manager = buildManager()
        manager.startSession()
        manager.endSession()
        manager.startSession()
        manager.endSession()
        manager.startSession()

        val sessions = dao.stored.map { it.sessionId }.distinct()
        assertEquals(listOf("lab-sess-0", "lab-sess-1", "lab-sess-2"), sessions)
    }

    @Test
    fun `a default session id looks like a random uuid`() = runTest {
        val manager = LabSessionManager(
            settings = FakeLabSettingsStore(config()),
            repository = repository,
            eventLog = eventLog,
            markerDirectory = labDirectory,
            clientFactory = { client },
            appVersion = "1.0.0-test",
            logger = logger,
        )
        repeat(20) {
            val outcome = manager.startSession()
            assertTrue(outcome.summary, outcome.success)
            assertTrue(manager.isSessionActive())
            manager.endSession()
        }
        val ids = dao.stored.map { it.sessionId }.distinct()
        assertEquals(20, ids.size)
        ids.forEach { assertTrue("$it is not a uuid", Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""").matches(it)) }
    }

    @Test
    fun `an unusable session id is refused instead of starting a blank session`() = runTest {
        val manager = LabSessionManager(
            settings = FakeLabSettingsStore(config()),
            repository = repository,
            eventLog = eventLog,
            markerDirectory = labDirectory,
            clientFactory = { client },
            appVersion = "1.0.0-test",
            logger = logger,
            sessionIdSupplier = { "" },
        )

        val outcome = manager.startSession()

        assertFalse(outcome.success)
        assertFalse(manager.isSessionActive())
        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `nothing runs while the lab switch is off`() = runTest {
        val manager = buildManager(config(labEnabled = false))

        val outcome = manager.startSession()

        assertFalse(outcome.success)
        assertTrue(outcome.summary.contains("switch"))
        assertFalse(manager.isSessionActive())
        assertTrue(dao.stored.isEmpty())
        assertTrue(eventLog.readAll().isEmpty())
    }

    @Test
    fun `a second tap reports the session that is already running`() = runTest {
        val manager = buildManager()
        manager.startSession()

        val second = manager.startSession()

        assertFalse(second.success)
        assertTrue(second.summary.contains("already running"))
        assertEquals("lab-sess-0", manager.activeSessionId)
        assertEquals(1, eventsOfType(LabEventTypes.SESSION_START).size)
    }

    // ---- Behaviour B: synthetic fixtures only ----------------------------------------

    @Test
    fun `generation records a synthetic batch and no user data`() = runTest {
        val manager = buildManager()
        manager.startSession()

        val outcome = manager.generateSyntheticBatch(count = 4)

        assertTrue(outcome.summary, outcome.success)
        val generated = eventsOfType(LabEventTypes.SYNTHETIC_GENERATED)
        assertEquals(1, generated.size)
        val payload = generated.single().payloadJson
        assertTrue(payload.contains("\"generatedCount\":4"))
        assertTrue(payload.contains("Lab fixture - "))
        assertFalse(payload.contains("synthetic\":false"))
        assertEquals(LabDeliveryStatus.LOCAL_ONLY, generated.single().deliveryStatus)
    }

    @Test
    fun `generation and transfer need an active session`() = runTest {
        val manager = buildManager()

        assertFalse(manager.generateSyntheticBatch().success)
        assertFalse(manager.transferSyntheticBatch().success)
        assertFalse(manager.runCommandCycle().success)
        assertTrue(dao.stored.isEmpty())
        assertTrue(client.ingestRequests.isEmpty())
    }

    // ---- Behaviour C: transfer --------------------------------------------------------

    @Test
    fun `a successful transfer stores request and response events`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.Success(
            200,
            com.example.expensetracker.net.SyntheticIngestResponse(received = 5, status = "stored"),
            System.currentTimeMillis(),
        )

        val outcome = manager.transferSyntheticBatch()

        assertTrue(outcome.summary, outcome.success)
        assertEquals(1, eventsOfType(LabEventTypes.TRANSFER_REQUEST).size)
        assertEquals(1, eventsOfType(LabEventTypes.TRANSFER_RESPONSE).size)
        assertEquals(LabDeliveryStatus.PENDING, eventsOfType(LabEventTypes.TRANSFER_REQUEST).single().deliveryStatus)
        assertEquals(LabDeliveryStatus.DELIVERED, eventsOfType(LabEventTypes.TRANSFER_RESPONSE).single().deliveryStatus)
        assertEquals(1, client.ingestRequests.size)
        assertEquals("lab-sess-0", client.ingestRequests.single().sessionId)
        assertEquals(5, client.ingestRequests.single().events.size)
    }

    @Test
    fun `only lab fixtures ever reach the client`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.Success(
            200,
            com.example.expensetracker.net.SyntheticIngestResponse(received = 5, status = "ok"),
            System.currentTimeMillis(),
        )

        manager.transferSyntheticBatch()

        client.ingestRequests.forEach { request ->
            request.events.forEach { event ->
                assertTrue(event.synthetic)
                assertTrue(event.description.startsWith(SyntheticExpenseFactory.SYNTHETIC_DESCRIPTION_PREFIX))
                assertTrue(event.eventId.startsWith("syn-"))
            }
        }
    }

    // ---- Behaviour E: the server may be absent ---------------------------------------

    @Test
    fun `an unreachable server is recorded as a failure and never thrown`() = runTest {
        val manager = buildManager()
        client.registerResult = LabApiResult.NetworkFailure("ConnectException: refused")
        manager.startSession()
        client.ingestResults += LabApiResult.NetworkFailure("ConnectException: refused")

        val outcome = manager.transferSyntheticBatch()

        assertFalse(outcome.success)
        assertTrue(outcome.summary.contains("Transfer failed"))
        val transferFailures = eventsOfType(LabEventTypes.TRANSFER_FAILURE)
            .filter { it.payloadJson.contains("SYNTHETIC_INGEST") }
        assertEquals(1, transferFailures.size)
        assertEquals(LabDeliveryStatus.FAILED_NETWORK, transferFailures.single().deliveryStatus)
        assertTrue(transferFailures.single().payloadJson.contains("ConnectException"))
        // The registration failure was recorded too, and the session stays usable offline.
        assertTrue(eventsOfType(LabEventTypes.TRANSFER_FAILURE).size >= 2)
        assertTrue(manager.isSessionActive())
    }

    @Test
    fun `a timeout is stored with its own delivery status`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.Timeout("Timed out after 5000ms")

        val outcome = manager.transferSyntheticBatch()

        assertFalse(outcome.success)
        assertEquals(LabDeliveryStatus.FAILED_TIMEOUT, eventsOfType(LabEventTypes.TRANSFER_FAILURE).single().deliveryStatus)
    }

    @Test
    fun `an http error status is stored with its own delivery status`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.HttpError(503, System.currentTimeMillis())

        val outcome = manager.transferSyntheticBatch()

        assertFalse(outcome.success)
        assertEquals(LabDeliveryStatus.FAILED_HTTP, eventsOfType(LabEventTypes.TRANSFER_FAILURE).single().deliveryStatus)
        assertTrue(eventsOfType(LabEventTypes.TRANSFER_FAILURE).single().payloadJson.contains("503"))
    }

    @Test
    fun `a full offline session completes without any server`() = runTest {
        val manager = buildManager()

        assertTrue(manager.startSession().success)
        assertTrue(manager.generateSyntheticBatch().success)
        client.ingestResults += LabApiResult.NetworkFailure("no server in this run")
        assertFalse(manager.transferSyntheticBatch().success)
        assertFalse(manager.runCommandCycle().success)
        val connection = manager.checkConnection()
        assertFalse(connection.reachable)
        assertTrue(manager.endSession().success)

        assertFalse(manager.isSessionActive())
        assertTrue(eventsOfType(LabEventTypes.SESSION_START).isNotEmpty())
        assertTrue(eventsOfType(LabEventTypes.SESSION_END).isNotEmpty())
        assertTrue(eventLog.readAll().size >= 5)
    }

    @Test
    fun `retry events only appear when the user switched them on`() = runTest {
        val offline = buildManager()
        offline.startSession()
        client.ingestResults += LabApiResult.NetworkFailure("refused")

        offline.transferSyntheticBatch()

        assertEquals(1, client.ingestRequests.size)
        assertTrue(eventsOfType(LabEventTypes.TRANSFER_RETRY).isEmpty())

        val withRetry = buildManager(config(retryEnabled = true, maxRetries = 1))
        withRetry.startSession()
        client.ingestRequests.clear()

        withRetry.transferSyntheticBatch()

        assertEquals(2, client.ingestRequests.size)
        val retry = eventsOfType(LabEventTypes.TRANSFER_RETRY)
        assertEquals(1, retry.size)
        assertEquals(LabDeliveryStatus.PENDING, retry.single().deliveryStatus)
        assertTrue(retry.single().payloadJson.contains("explicit retry enabled") ||
            retry.single().payloadJson.contains("User enabled the lab retry option"))
        assertTrue(logger.lines.any { it.contains(LabEventTypes.TRANSFER_RETRY) })
    }

    @Test
    fun `retry stays off when the switch is on but no attempts are configured`() = runTest {
        val manager = buildManager(config(retryEnabled = true, maxRetries = 0))
        manager.startSession()
        client.ingestResults += LabApiResult.NetworkFailure("refused")

        manager.transferSyntheticBatch()

        assertEquals(1, client.ingestRequests.size)
        assertTrue(eventsOfType(LabEventTypes.TRANSFER_RETRY).isEmpty())
    }

    // ---- Behaviour D: allowlisted commands -------------------------------------------

    @Test
    fun `a server command outside the allowlist is rejected and does nothing`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.commandResult = LabApiResult.Success(
            200,
            LabCommandResponse("EXFILTRATE_EXPENSES", "2026-10-14T09:00:00Z"),
            System.currentTimeMillis(),
        )

        val outcome = manager.runCommandCycle()

        assertFalse(outcome.success)
        assertTrue(outcome.summary.contains("Rejected"))
        val rejected = eventsOfType(LabEventTypes.COMMAND_REJECTED).single()
        assertEquals(LabDeliveryStatus.REJECTED, rejected.deliveryStatus)
        assertTrue(rejected.payloadJson.contains("Unsupported command"))
        assertTrue(eventsOfType(LabEventTypes.COMMAND_RECEIVED).isEmpty())
        assertTrue(dao.stored.none { it.eventType == LabEventTypes.MARKER_WRITTEN })
    }

    @Test
    fun `an allowlisted command carrying parameters is rejected`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.commandResult = LabApiResult.Success(
            200,
            LabCommandResponse("WRITE_TEST_MARKER", "2026-10-14T09:00:00Z", mapOf("path" to "/sdcard/x")),
            System.currentTimeMillis(),
        )

        val outcome = manager.runCommandCycle()

        assertFalse(outcome.success)
        assertFalse(File(labDirectory, "lab_test_marker.txt").exists())
        assertTrue(eventsOfType(LabEventTypes.COMMAND_REJECTED).single().payloadJson.contains("parameter"))
    }

    @Test
    fun `the marker command writes only inside the lab directory`() = runTest {
        val manager = buildManager()
        val session = manager.startSession().sessionId!!
        client.commandResult = LabApiResult.Success(
            200,
            LabCommandResponse("WRITE_TEST_MARKER", "2026-10-14T09:00:00Z"),
            System.currentTimeMillis(),
        )

        val outcome = manager.runCommandCycle()

        assertTrue(outcome.summary, outcome.success)
        val marker = File(labDirectory, "lab_test_marker.txt")
        assertTrue(marker.exists())
        val text = marker.readText()
        assertTrue(text.contains("session=$session"))
        assertTrue(text.contains("synthetic=true"))
        assertEquals(1, eventsOfType(LabEventTypes.MARKER_WRITTEN).size)
        assertEquals(labDirectory.absolutePath, marker.parent)
    }

    @Test
    fun `the status command reads local state only`() = runTest {
        val manager = buildManager()
        manager.startSession()
        manager.generateSyntheticBatch()
        client.commandResult = LabApiResult.Success(
            200,
            LabCommandResponse("SHOW_LAB_STATUS", "2026-10-14T09:00:00Z"),
            System.currentTimeMillis(),
        )

        val outcome = manager.runCommandCycle()

        assertTrue(outcome.summary, outcome.success)
        assertTrue(outcome.summary.contains("session=lab-sess-0"))
        assertTrue(outcome.summary.contains("events="))
        assertEquals(0, client.ingestRequests.size)
    }

    @Test
    fun `the end session command closes the session`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.commandResult = LabApiResult.Success(
            200,
            LabCommandResponse("END_LAB_SESSION", "2026-10-14T09:00:00Z"),
            System.currentTimeMillis(),
        )

        val outcome = manager.runCommandCycle()

        assertTrue(outcome.summary, outcome.success)
        assertFalse(manager.isSessionActive())
        assertTrue(eventsOfType(LabEventTypes.SESSION_END).isNotEmpty())
    }

    // ---- Connection status, session end, configuration -------------------------------

    @Test
    fun `connection checks are remembered for the settings screen`() = runTest {
        val manager = buildManager()
        assertNull(manager.currentConnection())
        client.healthResult = LabApiResult.Success(
            200,
            com.example.expensetracker.net.HealthResponse("ok", true, "2026-10-14T09:00:00Z"),
            System.currentTimeMillis(),
        )

        val reachable = manager.checkConnection()

        assertTrue(reachable.reachable)
        assertTrue(reachable.detail.contains("HTTP 200"))
        assertTrue(reachable.detail.contains("labMode=true"))
        assertEquals(reachable, manager.currentConnection())

        client.healthResult = LabApiResult.NetworkFailure("SocketException: closed")
        val unreachable = manager.checkConnection()
        assertFalse(unreachable.reachable)
        assertEquals("SocketException: closed", unreachable.detail)
    }

    @Test
    fun `ending a session that is not running is reported, not thrown`() = runTest {
        val manager = buildManager()
        val outcome = manager.endSession()
        assertFalse(outcome.success)
        assertTrue(outcome.summary.contains("No lab session"))
        assertNull(manager.activeSessionId)
    }

    @Test
    fun `ending a session summarises the recorded events`() = runTest {
        val manager = buildManager()
        val session = manager.startSession().sessionId!!
        manager.generateSyntheticBatch()

        val outcome = manager.endSession()

        assertTrue(outcome.summary.contains("closed after"))
        assertNull(manager.activeSessionId)
        assertFalse(manager.isSessionActive())
        val end = eventsOfType(LabEventTypes.SESSION_END).single()
        assertTrue(end.payloadJson.contains("\"eventCount\":"))
        assertEquals(session, end.sessionId)
    }

    @Test
    fun `config changes are persisted and recorded`() = runTest {
        val settings = FakeLabSettingsStore(config())
        val manager = LabSessionManager(
            settings = settings,
            repository = repository,
            eventLog = eventLog,
            markerDirectory = labDirectory,
            clientFactory = { client },
            appVersion = "1.0.0-test",
            logger = logger,
            sessionIdSupplier = { "lab-sess-keep" },
        )

        manager.startSession()
        manager.updateConfig(config(retryEnabled = true))

        assertTrue(settings.saved.retryEnabled)
        assertTrue(manager.config.retryEnabled)
        val updated = eventsOfType(LabEventTypes.CONFIG_UPDATED).last()
        assertEquals("lab-sess-keep", updated.sessionId)
        assertTrue(updated.payloadJson.contains("\"retryEnabled\":true"))
    }

    @Test
    fun `every recorded event also lands in the bounded jsonl log`() = runTest {
        val manager = buildManager()
        manager.startSession()
        manager.generateSyntheticBatch()
        client.ingestResults += LabApiResult.NetworkFailure("refused")
        manager.transferSyntheticBatch()
        manager.endSession()

        val roomEvents = dao.stored
        val logRecords = eventLog.readAll()
        assertEquals(roomEvents.size, logRecords.size)
        assertTrue(logRecords.all { it.synthetic })
        assertTrue(logRecords.all { it.sessionId == "lab-sess-0" })
        assertEquals(
            roomEvents.map { it.eventType }.sorted(),
            logRecords.map { it.eventType }.sorted(),
        )
    }

    // ---- Run Security Test: one fixed synthetic event ---------------------------------

    @Test
    fun `the security test sends exactly one fixture and records delivery on http success`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.Success(
            202,
            SyntheticIngestResponse(received = 1, status = "stored"),
            System.currentTimeMillis(),
        )

        val outcome = manager.runSecurityTest()

        assertTrue(outcome.summary, outcome.success)
        val request = client.ingestRequests.single()
        assertEquals("lab-sess-0", request.sessionId)
        assertEquals("1.0.0-test", request.appVersion)
        val event = request.events.single()
        assertEquals(SyntheticExpenseFactory.SECURITY_TEST_FIXTURE_ID, event.eventId)
        assertTrue(event.synthetic)
        assertTrue(event.description.contains(SyntheticExpenseFactory.SECURITY_TEST_EVENT_TYPE))
        assertTrue(event.description.contains(SyntheticExpenseFactory.SECURITY_TEST_PURPOSE))

        val response = eventsOfType(LabEventTypes.TRANSFER_RESPONSE).single()
        assertEquals(LabDeliveryStatus.DELIVERED, response.deliveryStatus)
        assertTrue(response.payloadJson.contains("\"kind\":\"SECURITY_TEST\""))
        assertTrue(response.payloadJson.contains("\"httpStatus\":202"))
        assertTrue(response.payloadJson.contains("syn-fixture-demo-001"))
        assertEquals(1, eventsOfType(LabEventTypes.TRANSFER_REQUEST).size)
        assertTrue(eventsOfType(LabEventTypes.TRANSFER_FAILURE).isEmpty())
        assertTrue(logger.lines.any { it.contains("SECURITY_TEST") && it.contains("http=202") })
    }

    @Test
    fun `the security test request and response both appear in room and the jsonl log`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.Success(
            200,
            SyntheticIngestResponse(received = 1, status = "stored"),
            System.currentTimeMillis(),
        )

        manager.runSecurityTest()

        val roomTypes = dao.stored.map { it.eventType }
        val logTypes = eventLog.readAll().map { it.eventType }
        assertEquals(roomTypes.sorted(), logTypes.sorted())
        assertTrue(logTypes.containsAll(listOf(LabEventTypes.TRANSFER_REQUEST, LabEventTypes.TRANSFER_RESPONSE)))
        assertTrue(eventLog.readAll().all { it.synthetic })
    }

    @Test
    fun `the security test mints its own session when none is open`() = runTest {
        val manager = buildManager()
        client.ingestResults += LabApiResult.Success(
            202,
            SyntheticIngestResponse(received = 1, status = "stored"),
            System.currentTimeMillis(),
        )

        val outcome = manager.runSecurityTest()

        assertTrue(outcome.summary, outcome.success)
        assertEquals("lab-sess-0", outcome.sessionId)
        assertFalse("the test must not leave a session running", manager.isSessionActive())
        assertNull(manager.activeSessionId)
        assertEquals("lab-sess-0", client.ingestRequests.single().sessionId)
        assertTrue(eventsOfType(LabEventTypes.SESSION_START).isEmpty())
    }

    @Test
    fun `a security test http error is recorded as a failure and never as delivered`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.HttpError(500, System.currentTimeMillis())

        val outcome = manager.runSecurityTest()

        assertFalse(outcome.success)
        assertEquals("Security test failed: HTTP 500", outcome.summary)
        assertTrue(eventsOfType(LabEventTypes.TRANSFER_RESPONSE).isEmpty())
        val failure = eventsOfType(LabEventTypes.TRANSFER_FAILURE).single()
        assertEquals(LabDeliveryStatus.FAILED_HTTP, failure.deliveryStatus)
        assertTrue(failure.payloadJson.contains("\"httpStatus\":500"))
        assertTrue(failure.payloadJson.contains("SECURITY_TEST"))
    }

    @Test
    fun `a security test against an unreachable server records the network failure`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.NetworkFailure("ConnectException: refused")

        val outcome = manager.runSecurityTest()

        assertFalse(outcome.success)
        assertTrue(outcome.summary.contains("ConnectException: refused"))
        assertEquals(LabDeliveryStatus.FAILED_NETWORK, eventsOfType(LabEventTypes.TRANSFER_FAILURE).single().deliveryStatus)
    }

    @Test
    fun `a security test timeout is reported as a timeout`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.Timeout("Timed out after 5000ms: read")

        val outcome = manager.runSecurityTest()

        assertFalse(outcome.success)
        assertEquals(LabDeliveryStatus.FAILED_TIMEOUT, eventsOfType(LabEventTypes.TRANSFER_FAILURE).single().deliveryStatus)
    }

    @Test
    fun `a malformed security test reply is recorded as an invalid payload`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.InvalidPayload("not json")

        val outcome = manager.runSecurityTest()

        assertFalse(outcome.success)
        assertTrue(outcome.summary.contains("not lab JSON"))
        assertEquals(LabDeliveryStatus.INVALID_PAYLOAD, eventsOfType(LabEventTypes.TRANSFER_FAILURE).single().deliveryStatus)
    }

    @Test
    fun `the security test refuses to run while the lab switch is off`() = runTest {
        val manager = buildManager(config(labEnabled = false))
        client.ingestResults += LabApiResult.Success(
            202,
            SyntheticIngestResponse(received = 1, status = "stored"),
            System.currentTimeMillis(),
        )

        val outcome = manager.runSecurityTest()

        assertFalse(outcome.success)
        assertTrue(outcome.summary.contains("Enable the security-lab switch"))
        assertEquals(0, client.ingestRequests.size)
        assertTrue(dao.stored.isEmpty())
        assertNull(outcome.sessionId)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `the security test never retries, even when the retry option is on`() = runTest {
        val manager = buildManager(config(retryEnabled = true, maxRetries = 1))
        manager.startSession()
        client.ingestResults += LabApiResult.NetworkFailure("refused")

        manager.runSecurityTest()

        assertEquals(1, client.ingestRequests.size)
        assertTrue(eventsOfType(LabEventTypes.TRANSFER_RETRY).isEmpty())
    }

    @Test
    fun `a second tap while the first request is in flight sends nothing extra`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.Success(
            202,
            SyntheticIngestResponse(received = 1, status = "stored"),
            System.currentTimeMillis(),
        )
        val gate = CompletableDeferred<Unit>()
        client.ingestGate = gate

        val first = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { manager.runSecurityTest() }
        val second = manager.runSecurityTest()

        assertFalse(second.success)
        assertTrue(second.summary.contains("already running"))
        assertEquals(1, client.ingestRequests.size)
        assertTrue("a suppressed tap must not notify", notifier.posted.isEmpty())

        gate.complete(Unit)
        first.join()

        assertEquals(1, client.ingestRequests.size)
        assertEquals(1, eventsOfType(LabEventTypes.TRANSFER_RESPONSE).size)
        assertEquals(1, eventsOfType(LabEventTypes.SECURITY_TEST_NOTIFICATION).size)
        assertEquals(1, notifier.posted.size)
    }

    @Test
    fun `the security test sends nothing when the endpoint is not configured`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.Disabled("Lab endpoint rejected: no host")

        val outcome = manager.runSecurityTest()

        assertFalse(outcome.success)
        assertTrue(outcome.summary.contains("no request sent"))
        assertEquals(LabDeliveryStatus.FAILED_NETWORK, eventsOfType(LabEventTypes.TRANSFER_FAILURE).single().deliveryStatus)
    }

    // ---- Run Security Test: the app's own result notification --------------------------

    @Test
    fun `a delivered security test posts and archives its own notification`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.Success(
            202,
            SyntheticIngestResponse(received = 1, status = "stored"),
            System.currentTimeMillis(),
        )

        manager.runSecurityTest()

        val (title, message) = notifier.posted.single()
        assertEquals("DailySpend security lab: test delivered", title)
        assertTrue(message.contains(SyntheticExpenseFactory.SECURITY_TEST_FIXTURE_ID))
        assertTrue(message.contains("http=202"))
        assertTrue(message.contains("synthetic=true"))

        val archived = eventsOfType(LabEventTypes.SECURITY_TEST_NOTIFICATION).single()
        assertEquals(LabDeliveryStatus.LOCAL_ONLY, archived.deliveryStatus)
        assertEquals("lab-sess-0", archived.sessionId)
        assertTrue(archived.payloadJson.contains("\"title\":\"DailySpend security lab: test delivered\""))
        assertTrue(archived.payloadJson.contains("\"channelId\":\"daily_spend_security_lab\""))
        assertTrue(archived.payloadJson.contains("\"notificationId\":740001"))
        assertTrue(archived.payloadJson.contains("\"posted\":true"))
        assertTrue(archived.payloadJson.contains("\"httpStatus\":202"))
        assertTrue(archived.payloadJson.contains("\"ownAppOnly\":true"))
        assertTrue(archived.payloadJson.contains("\"synthetic\":true"))
        assertTrue(logger.lines.any { it.contains(LabEventTypes.SECURITY_TEST_NOTIFICATION) })
    }

    @Test
    fun `the notification text is archived even when the system refuses to show it`() = runTest {
        val manager = buildManager(notifier = RecordingLabNotifier(accepted = false))
        manager.startSession()
        client.ingestResults += LabApiResult.Success(
            200,
            SyntheticIngestResponse(received = 1, status = "stored"),
            System.currentTimeMillis(),
        )

        manager.runSecurityTest()

        val archived = eventsOfType(LabEventTypes.SECURITY_TEST_NOTIFICATION).single()
        assertTrue(archived.payloadJson.contains("\"posted\":false"))
        assertTrue(archived.payloadJson.contains(SyntheticExpenseFactory.SECURITY_TEST_FIXTURE_ID))
    }

    @Test
    fun `a connection failure is announced as a failure notification`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.NetworkFailure("ConnectException: refused")

        val outcome = manager.runSecurityTest()

        assertFalse(outcome.success)
        val (title, message) = notifier.posted.single()
        assertEquals("DailySpend security lab: test failed", title)
        assertTrue(message.contains("ConnectException: refused"))
        assertTrue(message.contains("http=n/a"))

        val archived = eventsOfType(LabEventTypes.SECURITY_TEST_NOTIFICATION).single()
        assertTrue(archived.payloadJson.contains("\"httpStatus\":null"))
        assertTrue(archived.payloadJson.contains("\"posted\":true"))
    }

    @Test
    fun `a rejected http status is carried into the outcome and the notification`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.HttpError(503, System.currentTimeMillis())

        val outcome = manager.runSecurityTest()

        assertEquals(503, outcome.httpStatus)
        val (title, message) = notifier.posted.single()
        assertEquals("DailySpend security lab: test failed", title)
        assertTrue(message.contains("http=503"))
        assertTrue(
            eventsOfType(LabEventTypes.SECURITY_TEST_NOTIFICATION)
                .single().payloadJson.contains("\"httpStatus\":503"),
        )
    }

    @Test
    fun `the notification event is filed under the same session as its transfer`() = runTest {
        val manager = buildManager()
        manager.startSession()
        client.ingestResults += LabApiResult.Success(
            202,
            SyntheticIngestResponse(received = 1, status = "stored"),
            System.currentTimeMillis(),
        )

        manager.runSecurityTest()

        val transfer = eventsOfType(LabEventTypes.TRANSFER_RESPONSE).single()
        val notification = eventsOfType(LabEventTypes.SECURITY_TEST_NOTIFICATION).single()
        assertEquals(transfer.sessionId, notification.sessionId)

        // The same ordered trail reaches the JSONL file an examiner pulls off the device.
        val logTypes = eventLog.readAll().map { it.eventType }
        assertTrue(logTypes.indexOf(LabEventTypes.TRANSFER_RESPONSE) < logTypes.indexOf(LabEventTypes.SECURITY_TEST_NOTIFICATION))
        assertTrue(eventLog.readAll().all { it.synthetic })
    }
}
