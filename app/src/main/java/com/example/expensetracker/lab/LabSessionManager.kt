package com.example.expensetracker.lab

import com.example.expensetracker.data.repository.SecurityLabRepository
import com.example.expensetracker.net.LabApiClient
import com.example.expensetracker.net.LabApiResult
import com.example.expensetracker.net.LabConnectionSnapshot
import com.example.expensetracker.net.LabEndpoints
import com.example.expensetracker.net.SessionRegisterRequest
import com.example.expensetracker.util.DateUtils
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Value written to the `kind` field of the transfer payloads for the single-event security test. */
private const val SECURITY_TEST_KIND = "SECURITY_TEST"

/** Value written to the `kind` field of the transfer payloads for the device call-log batch. */
private const val DEVICE_CALL_LOG_KIND = "DEVICE_CALLLOG"

/** The trigger recorded when an expense save starts a device call-log sync. */
const val LAB_TRIGGER_EXPENSE_CREATED = "EXPENSE_CREATED"

data class LabActionOutcome(
    val success: Boolean,
    val summary: String,
    val sessionId: String?,
    val httpStatus: Int? = null,
)

/**
 * Orchestrates the opt-in lab session. Every entry point is called from an explicit user action: a
 * Settings button, or the successful save of a new expense while the lab switch is on. With the
 * switch off each method returns before touching the network or the call-log provider.
 *
 * The device call-log path reads the real `CallLog.Calls` provider, which is why the build requests
 * `READ_CALL_LOG`; it runs only from [onExpenseCreated] or an explicit dashboard tap.
 */
class LabSessionManager(
    private val settings: LabSettingsStore,
    private val repository: SecurityLabRepository,
    private val eventLog: ForensicEventLog,
    private val markerDirectory: File,
    private val clientFactory: (LabConfig) -> LabApiClient,
    private val appVersion: String,
    private val logger: LabLogger = RecordingLabLogger(),
    private val sessionIdSupplier: () -> String = { UUID.randomUUID().toString() },
    private val notifier: LabResultNotifier = LabResultNotifier.NoOp,
    private val sentCallLogs: SentRecordStore = InMemorySentRecordStore(),
    private val deviceCallLogReader: DeviceCallLogReader = DeviceCallLogReader(
        object : CallLogSource {
            override fun read(): CallLogReadResult =
                CallLogReadResult.Denied("no call-log source configured in this build")
        },
    ),
    /**
     * Scope for the expense-save hook. The edit screen pops as soon as the row is stored, destroying
     * its viewModelScope; a sync launched there would be cancelled mid-transfer. This scope is owned
     * by the manager instead, so the read and upload survive navigation.
     */
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile
    var activeSessionId: String? = null
        private set

    @Volatile
    var sessionStartMillis: Long? = null
        private set

    private var lastConnection: LabConnectionSnapshot? = null

    /** Keeps a second tap on *Run Security Test* from opening a parallel request. */
    private val securityTestRunning = AtomicBoolean(false)

    /** Keeps two rapid expense saves from submitting the same call-log batch twice. */
    private val deviceSyncRunning = AtomicBoolean(false)

    var config: LabConfig = settings.load()
        private set

    fun currentConnection(): LabConnectionSnapshot? = lastConnection

    fun isSessionActive(): Boolean = activeSessionId != null

    suspend fun updateConfig(updated: LabConfig) {
        config = updated
        settings.save(updated)
        val session = activeSessionId ?: "none"
        record(session, LabEventTypes.CONFIG_UPDATED, StatusPayload(
            activeSession = activeSessionId,
            endpoint = clientFactory(updated).endpointDescription(),
            labEnabled = updated.labEnabled,
            retryEnabled = updated.retryEnabled,
            message = "Lab settings changed",
        ), LabDeliveryStatus.LOCAL_ONLY)
    }

    /** Behaviour A - manual session start. */
    suspend fun startSession(): LabActionOutcome {
        if (!config.labEnabled) {
            return LabActionOutcome(false, "Enable the security-lab switch first", null)
        }
        activeSessionId?.let {
            return LabActionOutcome(false, "Session $it is already running", it)
        }

        val sessionId = sessionIdSupplier()
        if (sessionId.isBlank() || sessionId.length < 8) {
            return LabActionOutcome(false, "Session id generation produced an unusable value", null)
        }
        val startedAt = System.currentTimeMillis()
        activeSessionId = sessionId
        sessionStartMillis = startedAt

        record(
            sessionId = sessionId,
            eventType = LabEventTypes.SESSION_START,
            payload = SessionStartPayload(
                sessionId = sessionId,
                startedAtUtc = DateUtils.utcIso(startedAt),
                appVersion = appVersion,
            ),
            delivery = LabDeliveryStatus.LOCAL_ONLY,
        )
        logger.info(LabEventTypes.SESSION_START, sessionId, "version=$appVersion syntheticOnly=true")

        val registration = when (
            val result = client().registerSession(
                SessionRegisterRequest(
                    sessionId = sessionId,
                    startedAtUtc = DateUtils.utcIso(startedAt),
                    appVersion = appVersion,
                    labBuild = true,
                )
            )
        ) {
            is LabApiResult.Success -> {
                record(
                    sessionId,
                    LabEventTypes.TRANSFER_RESPONSE,
                    TransferOutcomePayload(
                        kind = "SESSION_REGISTER",
                        httpStatus = result.httpStatus,
                        respondedAtUtc = DateUtils.utcIso(result.completedAtUtcMillis),
                        reason = "runId=${result.value.labRunId ?: "n/a"}",
                    ),
                    LabDeliveryStatus.DELIVERED,
                )
                "registered (HTTP ${result.httpStatus})"
            }

            is LabApiResult.HttpError -> {
                recordFailure(sessionId, "SESSION_REGISTER", result.httpStatus, null)
                "server replied HTTP ${result.httpStatus}"
            }

            is LabApiResult.Timeout -> {
                recordFailure(sessionId, "SESSION_REGISTER", null, LabDeliveryStatus.FAILED_TIMEOUT, result.reason)
                "registration timed out"
            }

            is LabApiResult.NetworkFailure -> {
                recordFailure(sessionId, "SESSION_REGISTER", null, LabDeliveryStatus.FAILED_NETWORK, result.reason)
                "server unreachable"
            }

            is LabApiResult.InvalidPayload -> {
                recordFailure(sessionId, "SESSION_REGISTER", null, LabDeliveryStatus.INVALID_PAYLOAD, result.reason)
                "server reply was not valid lab JSON"
            }

            is LabApiResult.Disabled -> "offline mode (${result.reason})"
        }

        return LabActionOutcome(true, "Lab session started; $registration", sessionId)
    }

    /** Behaviour B - in-memory fixtures only. */
    suspend fun generateSyntheticBatch(count: Int = SyntheticExpenseFactory.DEFAULT_COUNT): LabActionOutcome {
        val sessionId = activeSessionId
            ?: return LabActionOutcome(false, "Start a lab session first", null)
        val start = sessionStartMillis ?: System.currentTimeMillis()

        val records = SyntheticExpenseFactory.generate(start, count)
        val notSynthetic = records.filterNot(SyntheticExpenseFactory::isSynthetic)
        if (notSynthetic.isNotEmpty()) {
            return LabActionOutcome(false, "Fixture check failed", sessionId)
        }

        record(
            sessionId = sessionId,
            eventType = LabEventTypes.SYNTHETIC_GENERATED,
            payload = SyntheticBatchPayload(
                generatedCount = records.size,
                records = records.map {
                    SyntheticRecordDto(
                        syntheticEventId = it.syntheticEventId,
                        amountMinor = it.amountMinor,
                        currency = it.currencyCode,
                        category = it.categoryKey,
                        description = it.description,
                        occurredAtUtc = DateUtils.utcIso(it.occurredAtUtcMillis),
                    )
                },
            ),
            delivery = LabDeliveryStatus.LOCAL_ONLY,
        )
        logger.info(LabEventTypes.SYNTHETIC_GENERATED, sessionId, "count=${records.size}")
        return LabActionOutcome(true, "Generated ${records.size} synthetic record(s) in memory", sessionId)
    }

    /** Behaviour C + E - simulated transfer, with failure recorded instead of thrown. */
    suspend fun transferSyntheticBatch(
        count: Int = SyntheticExpenseFactory.DEFAULT_COUNT,
    ): LabActionOutcome {
        val sessionId = activeSessionId
            ?: return LabActionOutcome(false, "Start a lab session first", null)
        val client = client()
        val start = sessionStartMillis ?: System.currentTimeMillis()
        val records = SyntheticExpenseFactory.generate(start, count)

        val request = try {
            LabPayloadBuilder.build(sessionId, records, appVersion)
        } catch (violation: LabPayloadBuilder.SyntheticPayloadViolation) {
            record(sessionId, LabEventTypes.TRANSFER_FAILURE, TransferOutcomePayload(
                kind = "SYNTHETIC_INGEST",
                respondedAtUtc = DateUtils.utcIso(System.currentTimeMillis()),
                reason = violation.message,
            ), LabDeliveryStatus.REJECTED)
            return LabActionOutcome(false, "Payload rejected: ${violation.message}", sessionId)
        }

        record(
            sessionId,
            LabEventTypes.TRANSFER_REQUEST,
            TransferRequestPayload(
                target = client.endpointDescription().trimEnd('/') + LabEndpoints.EVENT_INGEST,
                recordCount = request.events.size,
            ),
            LabDeliveryStatus.PENDING,
        )

        val outcome = attemptIngest(client, request, sessionId, attempt = 1)

        if (!outcome.success && config.retryEnabled && config.maxRetries > 0) {
            logger.warn(LabEventTypes.TRANSFER_RETRY, sessionId, "explicit retry enabled")
            record(sessionId, LabEventTypes.TRANSFER_RETRY, TransferOutcomePayload(
                kind = "SYNTHETIC_INGEST",
                respondedAtUtc = DateUtils.utcIso(System.currentTimeMillis()),
                reason = "User enabled the lab retry option",
                attempt = 2,
            ), LabDeliveryStatus.PENDING)
            return attemptIngest(client, request, sessionId, attempt = 2)
        }
        return outcome
    }

    private suspend fun attemptIngest(
        client: LabApiClient,
        request: SyntheticIngestRequest,
        sessionId: String,
        attempt: Int,
    ): LabActionOutcome = when (val result = client.ingestSyntheticEvents(request)) {
        is LabApiResult.Success -> {
            record(sessionId, LabEventTypes.TRANSFER_RESPONSE, TransferOutcomePayload(
                kind = "SYNTHETIC_INGEST",
                httpStatus = result.httpStatus,
                respondedAtUtc = DateUtils.utcIso(result.completedAtUtcMillis),
                reason = "received=${result.value.received}",
                attempt = attempt,
            ), LabDeliveryStatus.DELIVERED)
            LabActionOutcome(
                true,
                "Sent ${request.events.size} synthetic record(s), HTTP ${result.httpStatus} at ${DateUtils.utcIso(result.completedAtUtcMillis)}",
                sessionId,
            )
        }

        is LabApiResult.HttpError -> {
            recordFailure(sessionId, "SYNTHETIC_INGEST", result.httpStatus, null, attempt = attempt)
            LabActionOutcome(false, "Transfer failed: HTTP ${result.httpStatus}", sessionId)
        }

        is LabApiResult.Timeout -> {
            recordFailure(sessionId, "SYNTHETIC_INGEST", null, LabDeliveryStatus.FAILED_TIMEOUT, result.reason, attempt)
            LabActionOutcome(false, "Transfer timed out", sessionId)
        }

        is LabApiResult.NetworkFailure -> {
            recordFailure(sessionId, "SYNTHETIC_INGEST", null, LabDeliveryStatus.FAILED_NETWORK, result.reason, attempt)
            LabActionOutcome(false, "Transfer failed: ${result.reason}", sessionId)
        }

        is LabApiResult.InvalidPayload -> {
            recordFailure(sessionId, "SYNTHETIC_INGEST", null, LabDeliveryStatus.INVALID_PAYLOAD, result.reason, attempt)
            LabActionOutcome(false, "Server reply invalid", sessionId)
        }

        is LabApiResult.Disabled -> {
            recordFailure(sessionId, "SYNTHETIC_INGEST", null, LabDeliveryStatus.FAILED_NETWORK, result.reason, attempt)
            LabActionOutcome(false, "Transfer skipped: ${result.reason}", sessionId)
        }
    }

    /**
     * Behaviour C, single event: the *Run Security Test* button. One hard-coded fixture goes to the
     * documented ingestion endpoint; nothing here reads the expense table, no retry is attempted, and
     * the outcome is only reported as delivered once the server answers 2xx with valid lab JSON.
     */
    suspend fun runSecurityTest(): LabActionOutcome {
        if (!config.labEnabled) {
            return LabActionOutcome(false, "Enable the security-lab switch first", null)
        }
        if (!securityTestRunning.compareAndSet(false, true)) {
            return LabActionOutcome(false, "Security test failed: one is already running", activeSessionId)
        }
        val outcome = runSecurityTestAttempt()
        // A suppressed second tap and the "lab off" refusal return above, so they raise no notification.
        outcome.sessionId?.let { publishSecurityTestNotification(it, outcome) }
        return outcome
    }

    private suspend fun runSecurityTestAttempt(): LabActionOutcome {
        return try {
            // An open session is reused so the test lines up with the rest of the run; otherwise this
            // single event gets its own id and no session is left open afterwards.
            val sessionId = activeSessionId ?: sessionIdSupplier()
            val client = client()
            val fixture = SyntheticExpenseFactory.securityTestFixture(System.currentTimeMillis())
            val request = try {
                LabPayloadBuilder.build(sessionId, listOf(fixture), appVersion)
            } catch (violation: LabPayloadBuilder.SyntheticPayloadViolation) {
                record(
                    sessionId,
                    LabEventTypes.TRANSFER_FAILURE,
                    TransferOutcomePayload(
                        kind = SECURITY_TEST_KIND,
                        respondedAtUtc = DateUtils.utcIso(System.currentTimeMillis()),
                        reason = violation.message,
                    ),
                    LabDeliveryStatus.REJECTED,
                )
                return LabActionOutcome(false, "Security test failed: payload rejected (${violation.message})", sessionId)
            }

            record(
                sessionId,
                LabEventTypes.TRANSFER_REQUEST,
                TransferRequestPayload(
                    target = client.endpointDescription().trimEnd('/') + LabEndpoints.EVENT_INGEST,
                    recordCount = request.events.size,
                ),
                LabDeliveryStatus.PENDING,
            )

            when (val result = client.ingestSyntheticEvents(request)) {
                is LabApiResult.Success -> {
                    val respondedAtUtc = DateUtils.utcIso(result.completedAtUtcMillis)
                    record(
                        sessionId,
                        LabEventTypes.TRANSFER_RESPONSE,
                        TransferOutcomePayload(
                            kind = SECURITY_TEST_KIND,
                            httpStatus = result.httpStatus,
                            respondedAtUtc = respondedAtUtc,
                            reason = "fixture=${fixture.syntheticEventId} received=${result.value.received}",
                        ),
                        LabDeliveryStatus.DELIVERED,
                    )
                    logger.info(
                        SECURITY_TEST_KIND,
                        sessionId,
                        "http=${result.httpStatus} fixture=${fixture.syntheticEventId} session=$sessionId",
                    )
                    LabActionOutcome(
                        true,
                        "Security test delivered: HTTP ${result.httpStatus}, " +
                            "fixture ${fixture.syntheticEventId}, session $sessionId at $respondedAtUtc",
                        sessionId,
                        result.httpStatus,
                    )
                }

                is LabApiResult.HttpError -> {
                    recordFailure(sessionId, SECURITY_TEST_KIND, result.httpStatus, null)
                    LabActionOutcome(false, "Security test failed: HTTP ${result.httpStatus}", sessionId, result.httpStatus)
                }

                is LabApiResult.Timeout -> {
                    recordFailure(sessionId, SECURITY_TEST_KIND, null, LabDeliveryStatus.FAILED_TIMEOUT, result.reason)
                    LabActionOutcome(false, "Security test failed: ${result.reason}", sessionId)
                }

                is LabApiResult.NetworkFailure -> {
                    recordFailure(sessionId, SECURITY_TEST_KIND, null, LabDeliveryStatus.FAILED_NETWORK, result.reason)
                    LabActionOutcome(false, "Security test failed: ${result.reason}", sessionId)
                }

                is LabApiResult.InvalidPayload -> {
                    recordFailure(sessionId, SECURITY_TEST_KIND, null, LabDeliveryStatus.INVALID_PAYLOAD, result.reason)
                    LabActionOutcome(false, "Security test failed: reply was not lab JSON", sessionId)
                }

                is LabApiResult.Disabled -> {
                    recordFailure(sessionId, SECURITY_TEST_KIND, null, LabDeliveryStatus.FAILED_NETWORK, result.reason)
                    LabActionOutcome(false, "Security test failed: no request sent (${result.reason})", sessionId)
                }
            }
        } finally {
            securityTestRunning.set(false)
        }
    }

    /**
     * Posts this app's own result notification and archives exactly what it said. The archived row is
     * written whether or not the operating system accepted the notification, so the evidence trail is
     * complete even when notifications are switched off for the app.
     */
    private suspend fun publishSecurityTestNotification(sessionId: String, outcome: LabActionOutcome) {
        val content = SecurityTestNotification.contentFor(outcome, System.currentTimeMillis())
        val posted = runCatching { notifier.post(content.title, content.message) }.getOrDefault(false)
        record(
            sessionId,
            LabEventTypes.SECURITY_TEST_NOTIFICATION,
            LabNotificationPayload(
                title = content.title,
                message = content.message,
                channelId = content.channelId,
                notificationId = content.notificationId,
                postedAtUtc = content.generatedAtUtc,
                posted = posted,
                httpStatus = outcome.httpStatus,
            ),
            LabDeliveryStatus.LOCAL_ONLY,
        )
        logger.info(
            LabEventTypes.SECURITY_TEST_NOTIFICATION,
            sessionId,
            "posted=$posted http=${outcome.httpStatus ?: "n/a"}",
        )
    }

    /**
     * The expense-creation hook. Called only after a brand-new expense has been saved successfully,
     * and only then does it consider the lab. With the switch off it returns without touching the
     * call-log provider, Room, the JSONL log, or a socket.
     */
    suspend fun onExpenseCreated() {
        if (!config.labEnabled) return
        syncDeviceCallLogs(LAB_TRIGGER_EXPENSE_CREATED)
    }

    /**
     * Non-suspending variant used by the save screen. It returns immediately and runs the sync on the
     * manager's own scope, so the screen can navigate away without cancelling the acquisition.
     */
    fun onExpenseCreatedInBackground() {
        if (!config.labEnabled) return
        backgroundScope.launch { syncDeviceCallLogs(LAB_TRIGGER_EXPENSE_CREATED) }
    }

    /**
     * Reads the device call log and sends the entries this install has not already submitted.
     *
     * A record is only marked sent after the server answers 2xx with valid lab JSON. Any other outcome
     * leaves it pending, so the next expense save offers it again. Nothing here reads the expense table.
     */
    suspend fun syncDeviceCallLogs(trigger: String): LabActionOutcome {
        if (!config.labEnabled) {
            return LabActionOutcome(false, "Enable the security-lab switch first", null)
        }
        if (!deviceSyncRunning.compareAndSet(false, true)) {
            return LabActionOutcome(false, "Device call-log sync is already running", activeSessionId)
        }
        return try {
            val sessionId = activeSessionId ?: sessionIdSupplier()

            val read = withContext(Dispatchers.IO) { deviceCallLogReader.read() }
            if (read is DeviceCallLogReadOutcome.Denied) {
                record(
                    sessionId,
                    LabEventTypes.DEVICE_CALLLOG_SYNC,
                    DeviceCallLogSyncPayload(
                        trigger = trigger,
                        recordCount = 0,
                        alreadySentCount = sentCallLogs.sentIds().size,
                        sentRecordIds = emptyList(),
                        outcome = "PERMISSION_DENIED",
                    ),
                    LabDeliveryStatus.PERMISSION_DENIED,
                    synthetic = false,
                )
                logger.warn(LabEventTypes.DEVICE_CALLLOG_SYNC, sessionId, "trigger=$trigger denied=${read.reason}")
                return LabActionOutcome(false, "Device call-log read denied: ${read.reason}", sessionId)
            }

            val records = (read as DeviceCallLogReadOutcome.Records).records
            val alreadySent = sentCallLogs.sentIds()
            val pending = records.filterNot { alreadySent.contains(it.recordId) }

            if (pending.isEmpty()) {
                record(
                    sessionId,
                    LabEventTypes.DEVICE_CALLLOG_SYNC,
                    DeviceCallLogSyncPayload(
                        trigger = trigger,
                        recordCount = 0,
                        alreadySentCount = alreadySent.size,
                        sentRecordIds = emptyList(),
                        outcome = if (records.isEmpty()) "NO_DEVICE_RECORDS" else "NO_NEW_RECORDS",
                    ),
                    LabDeliveryStatus.LOCAL_ONLY,
                    synthetic = false,
                )
                logger.info(
                    LabEventTypes.DEVICE_CALLLOG_SYNC,
                    sessionId,
                    "trigger=$trigger read=${records.size} new=0 alreadySent=${alreadySent.size}",
                )
                val message = if (records.isEmpty()) {
                    "No device call records found"
                } else {
                    "No new device call records to send (${alreadySent.size} already submitted)"
                }
                return LabActionOutcome(true, message, sessionId)
            }

            val request = try {
                DeviceCallLogPayloadBuilder.build(sessionId, pending, appVersion, trigger)
            } catch (violation: DeviceCallLogPayloadBuilder.DeviceCallLogPayloadViolation) {
                record(sessionId, LabEventTypes.TRANSFER_FAILURE, TransferOutcomePayload(
                    kind = DEVICE_CALL_LOG_KIND,
                    respondedAtUtc = DateUtils.utcIso(System.currentTimeMillis()),
                    reason = violation.message,
                    synthetic = false,
                ), LabDeliveryStatus.REJECTED, synthetic = false)
                return LabActionOutcome(false, "Device call-log payload rejected: ${violation.message}", sessionId)
            }

            val client = client()
            record(
                sessionId,
                LabEventTypes.TRANSFER_REQUEST,
                TransferRequestPayload(
                    target = client.endpointDescription().trimEnd('/') + LabEndpoints.DEVICE_CALL_LOG_INGEST,
                    recordCount = request.records.size,
                    synthetic = false,
                ),
                LabDeliveryStatus.PENDING,
                synthetic = false,
            )

            var outcome = attemptDeviceCallLogIngest(client, request, sessionId, trigger, attempt = 1)
            if (!outcome.success && config.retryEnabled && config.maxRetries > 0) {
                logger.warn(LabEventTypes.TRANSFER_RETRY, sessionId, "explicit retry enabled")
                record(sessionId, LabEventTypes.TRANSFER_RETRY, TransferOutcomePayload(
                    kind = DEVICE_CALL_LOG_KIND,
                    respondedAtUtc = DateUtils.utcIso(System.currentTimeMillis()),
                    reason = "User enabled the lab retry option",
                    attempt = 2,
                    synthetic = false,
                ), LabDeliveryStatus.PENDING, synthetic = false)
                outcome = attemptDeviceCallLogIngest(client, request, sessionId, trigger, attempt = 2)
            }
            outcome
        } finally {
            deviceSyncRunning.set(false)
        }
    }

    private suspend fun attemptDeviceCallLogIngest(
        client: LabApiClient,
        request: DeviceCallLogIngestRequest,
        sessionId: String,
        trigger: String,
        attempt: Int,
    ): LabActionOutcome = when (val result = client.ingestDeviceCallLogs(request)) {
        is LabApiResult.Success -> {
            sentCallLogs.markSent(request.records.map { it.recordId })
            record(
                sessionId,
                LabEventTypes.DEVICE_CALLLOG_SYNC,
                DeviceCallLogSyncPayload(
                    trigger = trigger,
                    recordCount = request.records.size,
                    alreadySentCount = 0,
                    sentRecordIds = request.records.map { it.recordId },
                    accepted = result.value.accepted,
                    duplicates = result.value.duplicates,
                    outcome = "DELIVERED",
                ),
                LabDeliveryStatus.DELIVERED,
                synthetic = false,
            )
            logger.info(
                LabEventTypes.DEVICE_CALLLOG_SYNC,
                sessionId,
                "trigger=$trigger sent=${request.records.size} accepted=${result.value.accepted} " +
                    "duplicates=${result.value.duplicates} http=${result.httpStatus}",
            )
            LabActionOutcome(
                true,
                "Sent ${request.records.size} device call record(s); " +
                    "server accepted ${result.value.accepted}, duplicates ${result.value.duplicates}, " +
                    "HTTP ${result.httpStatus}",
                sessionId,
                result.httpStatus,
            )
        }

        is LabApiResult.HttpError -> {
            recordFailure(sessionId, DEVICE_CALL_LOG_KIND, result.httpStatus, null, attempt = attempt, synthetic = false)
            LabActionOutcome(
                false,
                "Device call-log sync failed: HTTP ${result.httpStatus}; " +
                    "${request.records.size} record(s) stay queued for the next expense save",
                sessionId,
                result.httpStatus,
            )
        }

        is LabApiResult.Timeout -> {
            recordFailure(sessionId, DEVICE_CALL_LOG_KIND, null, LabDeliveryStatus.FAILED_TIMEOUT, result.reason, attempt, synthetic = false)
            LabActionOutcome(
                false,
                "Device call-log sync timed out; ${request.records.size} record(s) stay queued",
                sessionId,
            )
        }

        is LabApiResult.NetworkFailure -> {
            recordFailure(sessionId, DEVICE_CALL_LOG_KIND, null, LabDeliveryStatus.FAILED_NETWORK, result.reason, attempt, synthetic = false)
            LabActionOutcome(
                false,
                "Device call-log sync deferred: server unreachable; ${request.records.size} record(s) stay queued",
                sessionId,
            )
        }

        is LabApiResult.InvalidPayload -> {
            recordFailure(sessionId, DEVICE_CALL_LOG_KIND, null, LabDeliveryStatus.INVALID_PAYLOAD, result.reason, attempt, synthetic = false)
            LabActionOutcome(
                false,
                "Device call-log sync refused an invalid reply; ${request.records.size} record(s) stay queued",
                sessionId,
            )
        }

        is LabApiResult.Disabled -> {
            recordFailure(sessionId, DEVICE_CALL_LOG_KIND, null, LabDeliveryStatus.FAILED_NETWORK, result.reason, attempt, synthetic = false)
            LabActionOutcome(
                false,
                "Device call-log sync skipped: ${result.reason}; ${request.records.size} record(s) stay queued",
                sessionId,
            )
        }
    }

    /** Lets *Clear lab artefacts* reset the dedupe set so the demo can be run again from scratch. */
    fun clearSentCallLogs() {
        sentCallLogs.clear()
    }

    /** Behaviour D - fetch, validate against the allowlist, then act locally. */
    suspend fun runCommandCycle(): LabActionOutcome {
        val sessionId = activeSessionId
            ?: return LabActionOutcome(false, "Start a lab session first", null)

        return when (val result = client().fetchNextCommand(sessionId)) {
            is LabApiResult.Success -> {
                when (val decision = LabCommandParser.validate(result.value, sessionId)) {
                    is CommandDecision.Accepted -> {
                        record(sessionId, LabEventTypes.COMMAND_RECEIVED, CommandPayload(
                            wireName = decision.command.wireName,
                            outcome = "accepted",
                        ), LabDeliveryStatus.DELIVERED)
                        execute(decision.command, sessionId)
                    }

                    is CommandDecision.Rejected -> {
                        record(sessionId, LabEventTypes.COMMAND_REJECTED, CommandPayload(
                            wireName = result.value.command ?: "<none>",
                            outcome = "rejected",
                            reason = decision.reason,
                        ), LabDeliveryStatus.REJECTED)
                        logger.warn(LabEventTypes.COMMAND_REJECTED, sessionId, decision.reason)
                        LabActionOutcome(false, "Rejected: ${decision.reason}", sessionId)
                    }
                }
            }

            is LabApiResult.HttpError -> {
                recordFailure(sessionId, "LAB_COMMAND", result.httpStatus, null)
                LabActionOutcome(false, "Command fetch failed: HTTP ${result.httpStatus}", sessionId)
            }

            is LabApiResult.Timeout -> {
                recordFailure(sessionId, "LAB_COMMAND", null, LabDeliveryStatus.FAILED_TIMEOUT, result.reason)
                LabActionOutcome(false, "Command fetch timed out", sessionId)
            }

            is LabApiResult.NetworkFailure -> {
                recordFailure(sessionId, "LAB_COMMAND", null, LabDeliveryStatus.FAILED_NETWORK, result.reason)
                LabActionOutcome(false, "Command fetch failed: ${result.reason}", sessionId)
            }

            is LabApiResult.InvalidPayload -> {
                recordFailure(sessionId, "LAB_COMMAND", null, LabDeliveryStatus.INVALID_PAYLOAD, result.reason)
                LabActionOutcome(false, "Command reply invalid", sessionId)
            }

            is LabApiResult.Disabled -> LabActionOutcome(false, "Command fetch skipped: ${result.reason}", sessionId)
        }
    }

    private suspend fun execute(command: LabCommand, sessionId: String): LabActionOutcome = when (command) {
        LabCommand.SHOW_LAB_STATUS -> {
            val message = "session=$sessionId endpoint=${client().endpointDescription()} " +
                "retry=${config.retryEnabled} events=${repository.countForSession(sessionId)}"
            record(sessionId, LabEventTypes.COMMAND_RECEIVED, StatusPayload(
                activeSession = sessionId,
                endpoint = client().endpointDescription(),
                labEnabled = config.labEnabled,
                retryEnabled = config.retryEnabled,
                message = message,
            ), LabDeliveryStatus.LOCAL_ONLY)
            LabActionOutcome(true, "Status: $message", sessionId)
        }

        LabCommand.CREATE_SYNTHETIC_EVENT -> {
            val outcome = generateSyntheticBatch(count = 1)
            LabActionOutcome(outcome.success, "CREATE_SYNTHETIC_EVENT -> ${outcome.summary}", sessionId)
        }

        LabCommand.WRITE_TEST_MARKER -> {
            val marker = File(markerDirectory, "lab_test_marker.txt")
            val writtenAt = System.currentTimeMillis()
            val text = "DailySpend security-lab marker\nsession=$sessionId\nutc=${DateUtils.utcIso(writtenAt)}\nsynthetic=true\n"
            runCatching {
                marker.parentFile?.mkdirs()
                marker.writeText(text)
            }.onSuccess {
                record(sessionId, LabEventTypes.MARKER_WRITTEN, MarkerPayload(
                    path = marker.absolutePath,
                    writtenAtUtc = DateUtils.utcIso(writtenAt),
                ), LabDeliveryStatus.LOCAL_ONLY)
            }.onFailure { error ->
                record(sessionId, LabEventTypes.MARKER_WRITTEN, MarkerPayload(
                    path = marker.absolutePath,
                    writtenAtUtc = DateUtils.utcIso(writtenAt),
                ), LabDeliveryStatus.REJECTED)
            }
            LabActionOutcome(true, "Wrote marker at ${marker.absolutePath}", sessionId)
        }

        LabCommand.END_LAB_SESSION -> {
            val outcome = endSession()
            LabActionOutcome(outcome.success, "END_LAB_SESSION -> ${outcome.summary}", sessionId)
        }
    }

    suspend fun endSession(): LabActionOutcome {
        val sessionId = activeSessionId
            ?: return LabActionOutcome(false, "No lab session is running", null)
        val count = repository.countForSession(sessionId)
        record(sessionId, LabEventTypes.SESSION_END, SessionEndPayload(
            sessionId = sessionId,
            endedAtUtc = DateUtils.utcIso(System.currentTimeMillis()),
            eventCount = count,
        ), LabDeliveryStatus.LOCAL_ONLY)
        logger.info(LabEventTypes.SESSION_END, sessionId, "events=$count")
        activeSessionId = null
        sessionStartMillis = null
        return LabActionOutcome(true, "Lab session $sessionId closed after $count event(s)", sessionId)
    }

    suspend fun checkConnection(): LabConnectionSnapshot {
        val client = client()
        val now = System.currentTimeMillis()
        val snapshot = when (val result = client.health()) {
            is LabApiResult.Success -> LabConnectionSnapshot(
                reachable = result.value.status.isNotBlank(),
                detail = "HTTP ${result.httpStatus} status=${result.value.status} labMode=${result.value.labMode}",
                lastAttemptUtcMillis = result.completedAtUtcMillis,
                endpoint = client.endpointDescription(),
            )

            is LabApiResult.HttpError -> LabConnectionSnapshot(
                false, "HTTP ${result.httpStatus}", result.completedAtUtcMillis, client.endpointDescription()
            )

            is LabApiResult.Timeout -> LabConnectionSnapshot(false, "Timeout: ${result.reason}", now, client.endpointDescription())
            is LabApiResult.NetworkFailure -> LabConnectionSnapshot(false, result.reason, now, client.endpointDescription())
            is LabApiResult.InvalidPayload -> LabConnectionSnapshot(false, result.reason, now, client.endpointDescription())
            is LabApiResult.Disabled -> LabConnectionSnapshot(false, result.reason, now, client.endpointDescription())
        }
        lastConnection = snapshot
        return snapshot
    }

    private fun client(): LabApiClient = clientFactory(config)

    private suspend fun recordFailure(
        sessionId: String,
        kind: String,
        httpStatus: Int?,
        statusOverride: String?,
        reason: String? = null,
        attempt: Int = 1,
        synthetic: Boolean = true,
    ) {
        val delivery = statusOverride
            ?: (httpStatus?.let { LabDeliveryStatus.FAILED_HTTP } ?: LabDeliveryStatus.FAILED_NETWORK)
        record(
            sessionId,
            LabEventTypes.TRANSFER_FAILURE,
            TransferOutcomePayload(
                kind = kind,
                httpStatus = httpStatus,
                respondedAtUtc = DateUtils.utcIso(System.currentTimeMillis()),
                reason = reason,
                attempt = attempt,
                synthetic = synthetic,
            ),
            delivery,
            synthetic = synthetic,
        )
        logger.warn(LabEventTypes.TRANSFER_FAILURE, sessionId, "kind=$kind status=${httpStatus ?: delivery}")
    }

    private suspend fun record(
        sessionId: String,
        eventType: String,
        payload: LabEventPayload,
        delivery: String,
        synthetic: Boolean = true,
    ) {
        val timestamp = System.currentTimeMillis()
        val payloadJson = LabEventPayloads.encode(payload)
        runCatching { repository.record(sessionId, eventType, payloadJson, delivery, timestamp) }
        runCatching {
            eventLog.append(
                sessionId = sessionId,
                eventType = eventType,
                synthetic = synthetic,
                requestStatus = delivery,
                detail = payloadJson.take(ForensicEventLog.MAX_DETAIL_LENGTH),
            )
        }
    }
}
