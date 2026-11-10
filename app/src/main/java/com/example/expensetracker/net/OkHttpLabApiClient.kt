package com.example.expensetracker.net

import com.example.expensetracker.lab.DeviceCallLogIngestRequest
import com.example.expensetracker.lab.DeviceCallLogRecord
import com.example.expensetracker.lab.LabConfig
import com.example.expensetracker.lab.SyntheticExpenseFactory
import com.example.expensetracker.lab.SyntheticIngestRequest
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.expensetracker.util.DateUtils

interface LabApiClient {
    suspend fun health(): LabApiResult<HealthResponse>
    suspend fun registerSession(request: SessionRegisterRequest): LabApiResult<SessionRegisterResponse>
    suspend fun ingestSyntheticEvents(request: SyntheticIngestRequest): LabApiResult<SyntheticIngestResponse>
    suspend fun ingestDeviceCallLogs(
        request: DeviceCallLogIngestRequest,
    ): LabApiResult<DeviceCallLogIngestResponse>
    suspend fun fetchNextCommand(sessionId: String): LabApiResult<LabCommandResponse>
    fun isConfigured(): Boolean
    fun endpointDescription(): String
}

/**
 * Typed, five-endpoint client. Every call returns a [LabApiResult]; no call ever throws at the
 * caller, and no call is retried unless the user explicitly enabled the lab retry option.
 */
class OkHttpLabApiClient(
    private val config: LabConfig,
    private val httpClient: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
) : LabApiClient {

    private val endpointDecision: LabEndpointPolicy.Decision =
        LabEndpointPolicy.validate(config.baseUrl, config.allowedCleartextHosts)

    override fun isConfigured(): Boolean =
        config.labEnabled && endpointDecision is LabEndpointPolicy.Decision.Allowed

    override fun endpointDescription(): String = when (val decision = endpointDecision) {
        is LabEndpointPolicy.Decision.Allowed -> decision.normalized
        is LabEndpointPolicy.Decision.Rejected -> "invalid (${decision.reason})"
    }

    override suspend fun health(): LabApiResult<HealthResponse> =
        get(LabEndpoints.HEALTH) { body -> json.decodeFromString(HealthResponse.serializer(), body) }

    override suspend fun registerSession(request: SessionRegisterRequest): LabApiResult<SessionRegisterResponse> =
        post(
            path = LabEndpoints.SESSION_REGISTER,
            payloadJson = json.encodeToString(SessionRegisterRequest.serializer(), request),
            parser = { body -> json.decodeFromString(SessionRegisterResponse.serializer(), body) },
        )

    override suspend fun ingestSyntheticEvents(
        request: SyntheticIngestRequest,
    ): LabApiResult<SyntheticIngestResponse> {
        // Second line of defence: nothing leaves this device unless every record is a lab fixture.
        val unsafe = request.events.filterNot {
            it.synthetic && SyntheticExpenseFactory.isSyntheticDescription(it.description)
        }
        if (unsafe.isNotEmpty()) {
            return LabApiResult.InvalidPayload("${unsafe.size} record(s) were not lab fixtures; send aborted")
        }

        val dto = SyntheticIngestRequestDto(
            sessionId = request.sessionId,
            appVersion = request.appVersion,
            preparedAtUtc = request.preparedAtUtc,
            events = request.events,
        )
        return post(
            path = LabEndpoints.EVENT_INGEST,
            payloadJson = json.encodeToString(SyntheticIngestRequestDto.serializer(), dto),
            parser = { body -> json.decodeFromString(SyntheticIngestResponse.serializer(), body) },
        )
    }

    override suspend fun ingestDeviceCallLogs(
        request: DeviceCallLogIngestRequest,
    ): LabApiResult<DeviceCallLogIngestResponse> {
        // Second line of defence: a record that is not a real device call-log entry never reaches the
        // socket, and a synthetic-looking value cannot be swapped into this path.
        val unsafe = request.records.filterNot(DeviceCallLogRecord::isDeviceRecord)
        if (unsafe.isNotEmpty()) {
            return LabApiResult.InvalidPayload(
                "${unsafe.size} call record(s) were not device call-log entries; send aborted"
            )
        }

        val dto = DeviceCallLogIngestRequestDto(
            sessionId = request.sessionId,
            appVersion = request.appVersion,
            preparedAtUtc = request.preparedAtUtc,
            trigger = request.trigger,
            records = request.records.map { record ->
                DeviceCallLogPayload(
                    recordId = record.recordId,
                    number = record.number,
                    direction = record.direction,
                    callType = record.callType,
                    startedAtUtc = record.startedAtUtc,
                    durationSeconds = record.durationSeconds,
                    contactName = record.contactName,
                    source = record.source,
                    synthetic = false,
                )
            },
        )
        return post(
            path = LabEndpoints.DEVICE_CALL_LOG_INGEST,
            payloadJson = json.encodeToString(DeviceCallLogIngestRequestDto.serializer(), dto),
            parser = { body -> json.decodeFromString(DeviceCallLogIngestResponse.serializer(), body) },
        )
    }

    override suspend fun fetchNextCommand(sessionId: String): LabApiResult<LabCommandResponse> {
        val request = LabCommandRequest(sessionId = sessionId)
        return post(
            path = LabEndpoints.COMMAND_FETCH,
            payloadJson = json.encodeToString(LabCommandRequest.serializer(), request),
            parser = { body -> json.decodeFromString(LabCommandResponse.serializer(), body) },
        )
    }

    private suspend fun <T> get(path: String, parser: (String) -> T): LabApiResult<T> {
        val base = ready() ?: return disabled()
        val request = Request.Builder()
            .url(base + path.removePrefix("/"))
            .header("Accept", JSON_MIME)
            .header("X-DailySpend-Lab", "1")
            .get()
            .build()
        return execute(request, parser)
    }

    private suspend fun <T> post(path: String, payloadJson: String, parser: (String) -> T): LabApiResult<T> {
        val base = ready() ?: return disabled()
        val request = Request.Builder()
            .url(base + path.removePrefix("/"))
            .post(payloadJson.toRequestBody(JSON_MIME.toMediaType()))
            .header("Accept", JSON_MIME)
            .header("X-DailySpend-Lab", "1")
            .build()
        return execute(request, parser)
    }

    private suspend fun <T> execute(request: Request, parser: (String) -> T): LabApiResult<T> =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val finishedAt = System.currentTimeMillis()
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@use LabApiResult.HttpError(response.code, finishedAt)
                    }
                    val parsed = try {
                        parser(body)
                    } catch (error: Exception) {
                        return@use LabApiResult.InvalidPayload(
                            "Response at ${DateUtils.utcIso(finishedAt)} was not valid lab JSON: ${error.javaClass.simpleName}"
                        )
                    }
                    LabApiResult.Success(response.code, parsed, finishedAt)
                }
            } catch (timeout: SocketTimeoutException) {
                LabApiResult.Timeout("Timed out after ${config.readTimeoutMillis}ms: ${timeout.message ?: "no detail"}")
            } catch (io: IOException) {
                LabApiResult.NetworkFailure("${io.javaClass.simpleName}: ${io.message ?: "unreachable"} (${DateUtils.utcIso(startedAt)})")
            } catch (error: Exception) {
                LabApiResult.NetworkFailure("${error.javaClass.simpleName}: ${error.message ?: "unexpected"}")
            }
        }

    private fun ready(): String? = when (val decision = endpointDecision) {
        is LabEndpointPolicy.Decision.Allowed -> if (config.labEnabled) decision.normalized else null
        is LabEndpointPolicy.Decision.Rejected -> null
    }

    private fun <T> disabled(): LabApiResult<T> = LabApiResult.Disabled(
        if (config.labEnabled) "Lab endpoint rejected: ${(endpointDecision as LabEndpointPolicy.Decision.Rejected).reason}"
        else "Security lab is switched off",
    )

    private companion object {
        const val JSON_MIME = "application/json"
    }
}
