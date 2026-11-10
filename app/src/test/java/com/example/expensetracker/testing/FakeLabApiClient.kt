package com.example.expensetracker.testing

import com.example.expensetracker.lab.DeviceCallLogIngestRequest
import com.example.expensetracker.lab.LabConfig
import com.example.expensetracker.lab.LabResultNotifier
import com.example.expensetracker.lab.SyntheticIngestRequest
import com.example.expensetracker.net.DeviceCallLogIngestResponse
import com.example.expensetracker.net.HealthResponse
import com.example.expensetracker.net.LabApiClient
import com.example.expensetracker.net.LabApiResult
import com.example.expensetracker.net.LabCommandResponse
import com.example.expensetracker.net.SessionRegisterRequest
import com.example.expensetracker.net.SessionRegisterResponse
import com.example.expensetracker.net.SyntheticIngestResponse
import kotlinx.coroutines.CompletableDeferred

/** Records every call the manager makes and replays scripted results. */
class ScriptedLabApiClient : LabApiClient {

    val ingestRequests: MutableList<SyntheticIngestRequest> = mutableListOf()
    val registeredSessions: MutableList<SessionRegisterRequest> = mutableListOf()
    val commandRequests: MutableList<String> = mutableListOf()
    val callLogRequests: MutableList<DeviceCallLogIngestRequest> = mutableListOf()

    var healthResult: LabApiResult<HealthResponse> = LabApiResult.Disabled("scripted")
    var registerResult: LabApiResult<SessionRegisterResponse> = LabApiResult.Disabled("scripted")
    var ingestResults: MutableList<LabApiResult<SyntheticIngestResponse>> = mutableListOf()
    var commandResult: LabApiResult<LabCommandResponse> = LabApiResult.Disabled("scripted")
    var callLogResults: MutableList<LabApiResult<DeviceCallLogIngestResponse>> = mutableListOf()

    /** When set, the next ingest call suspends here - used to prove concurrent taps are refused. */
    var ingestGate: CompletableDeferred<Unit>? = null

    /** Same idea for the call-log endpoint, kept separate so a test can hold one queue only. */
    var callLogGate: CompletableDeferred<Unit>? = null

    override suspend fun health(): LabApiResult<HealthResponse> = healthResult

    override suspend fun registerSession(
        request: SessionRegisterRequest,
    ): LabApiResult<SessionRegisterResponse> {
        registeredSessions += request
        return registerResult
    }

    override suspend fun ingestSyntheticEvents(
        request: SyntheticIngestRequest,
    ): LabApiResult<SyntheticIngestResponse> {
        ingestRequests += request
        ingestGate?.await()
        return ingestResults.removeAt(0).also {
            if (ingestResults.isEmpty()) ingestResults += it
        }
    }

    override suspend fun ingestDeviceCallLogs(
        request: DeviceCallLogIngestRequest,
    ): LabApiResult<DeviceCallLogIngestResponse> {
        callLogRequests += request
        callLogGate?.await()
        // Scripted results are consumed in order and never replayed, so a test can stage a failure
        // and then the success that proves the same device records were retried rather than dropped.
        if (callLogResults.isNotEmpty()) return callLogResults.removeAt(0)
        return LabApiResult.Success(
            httpStatus = 201,
            value = DeviceCallLogIngestResponse(
                accepted = request.records.size,
                duplicates = 0,
                status = "stored",
            ),
            completedAtUtcMillis = System.currentTimeMillis(),
        )
    }

    override suspend fun fetchNextCommand(sessionId: String): LabApiResult<LabCommandResponse> {
        commandRequests += sessionId
        return commandResult
    }

    override fun isConfigured(): Boolean = true

    override fun endpointDescription(): String = "http://10.0.2.2:5000/"
}

class FakeLabSettingsStore(initial: LabConfig) : com.example.expensetracker.lab.LabSettingsStore {
    var saved: LabConfig = initial
        private set

    override fun load(): LabConfig = saved

    override fun save(config: LabConfig) {
        saved = config
    }
}

/** Captures the notification text the manager tried to post, and reports whether the OS accepted it. */
class RecordingLabNotifier(private val accepted: Boolean = true) : LabResultNotifier {
    private val captured = mutableListOf<Pair<String, String>>()
    val posted: List<Pair<String, String>> get() = captured

    override fun post(title: String, message: String): Boolean {
        captured += title to message
        return accepted
    }
}
