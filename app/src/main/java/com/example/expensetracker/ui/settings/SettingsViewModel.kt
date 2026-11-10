package com.example.expensetracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.BuildConfig
import com.example.expensetracker.data.model.SecurityLabEvent
import com.example.expensetracker.data.repository.ExpenseStoreStats
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.lab.LabConfig
import com.example.expensetracker.lab.LabSessionManager
import com.example.expensetracker.net.LabConnectionSnapshot
import com.example.expensetracker.net.LabEndpointPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val appVersion: String = BuildConfig.VERSION_NAME,
    val buildType: String = BuildConfig.BUILD_TYPE,
    val storeStats: ExpenseStoreStats = ExpenseStoreStats(0, null, null),
    val labEnabled: Boolean = false,
    val endpointDraft: String = "",
    val endpointError: String? = null,
    val retryEnabled: Boolean = false,
    val sessionActive: Boolean = false,
    val sessionId: String? = null,
    val connection: LabConnectionSnapshot? = null,
    val busy: Boolean = false,
    val lastOutcome: String = "No lab action run yet.",
    val events: List<SecurityLabEvent> = emptyList(),
    val forensicPath: String = "",
    val forensicBytes: Long = 0L,
    val forensicRecords: Int = 0,
    val notificationsBlocked: Boolean = false,
)

/**
 * The only screen that can drive the lab module. Every action behind it needs an explicit tap and the
 * switches default to off; there is no timer, worker or receiver in this code path.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val manager: LabSessionManager = container.labManager
    private val initialConfig: LabConfig = manager.config

    /** Screen-owned values that do not come from the database. */
    private val local = MutableStateFlow(
        LocalState(
            labEnabled = initialConfig.labEnabled,
            endpointDraft = initialConfig.baseUrl,
            retryEnabled = initialConfig.retryEnabled,
        )
    )

    private val labEvents = container.securityLabRepository.observeRecent(EVENT_LIMIT)

    val uiState: StateFlow<SettingsUiState> =
        combine(labEvents, local) { events, state ->
            SettingsUiState(
                storeStats = state.stats,
                labEnabled = state.labEnabled,
                endpointDraft = state.endpointDraft,
                endpointError = state.endpointError,
                retryEnabled = state.retryEnabled,
                sessionActive = manager.isSessionActive(),
                sessionId = manager.activeSessionId,
                connection = manager.currentConnection(),
                busy = state.busy,
                lastOutcome = state.lastOutcome,
                events = events,
                forensicPath = state.forensicPath,
                forensicBytes = state.forensicBytes,
                forensicRecords = state.forensicRecords,
                notificationsBlocked = state.notificationsBlocked,
            )
        }
            .catch { error ->
                emit(SettingsUiState(lastOutcome = "Settings read failed: ${error.javaClass.simpleName}"))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshFacts()
    }

    fun onEndpointChange(value: String) {
        local.update { it.copy(endpointDraft = value, endpointError = null) }
    }

    fun saveEndpoint() {
        when (val decision = LabEndpointPolicy.validate(
            local.value.endpointDraft,
            manager.config.allowedCleartextHosts,
        )) {
            is LabEndpointPolicy.Decision.Allowed -> {
                val normalized = decision.normalized
                local.update { it.copy(endpointDraft = normalized, endpointError = null) }
                runLabAction("Saving endpoint...") { mgr ->
                    mgr.updateConfig(mgr.config.copy(baseUrl = normalized))
                    val snapshot = mgr.checkConnection()
                    if (snapshot.reachable) {
                        "Endpoint saved: $normalized - reachable, ${snapshot.detail}"
                    } else {
                        "Endpoint saved: $normalized - not reachable, ${snapshot.detail}"
                    }
                }
            }

            is LabEndpointPolicy.Decision.Rejected -> local.update {
                it.copy(
                    endpointError = decision.reason,
                    lastOutcome = "Endpoint rejected: ${decision.reason}",
                )
            }
        }
    }

    fun setLabEnabled(enabled: Boolean) {
        local.update { it.copy(labEnabled = enabled) }
        runLabAction(if (enabled) "Enabling lab module..." else "Disabling lab module...") { mgr ->
            mgr.updateConfig(mgr.config.copy(labEnabled = enabled))
            if (enabled) "Security lab enabled on this device" else "Security lab disabled"
        }
    }

    fun setRetryEnabled(enabled: Boolean) {
        local.update { it.copy(retryEnabled = enabled) }
        runLabAction(if (enabled) "Allowing one retry..." else "Disabling retries...") { mgr ->
            mgr.updateConfig(mgr.config.copy(retryEnabled = enabled))
            if (enabled) {
                "One explicit retry is allowed after a failed transfer"
            } else {
                "Retries disabled"
            }
        }
    }

    fun runConnectionCheck() = runLabAction("Checking lab connection...") { mgr ->
        val snapshot = mgr.checkConnection()
        if (snapshot.reachable) "Reachable: ${snapshot.detail}" else "Not reachable: ${snapshot.detail}"
    }

    fun startSession() = runLabAction("Starting lab session...") { it.startSession().summary }

    fun stopSession() = runLabAction("Closing lab session...") { it.endSession().summary }

    fun generateSynthetic() = runLabAction("Generating synthetic fixtures...") {
        it.generateSyntheticBatch().summary
    }

    fun transferSynthetic() = runLabAction("Transferring synthetic fixtures...") {
        it.transferSyntheticBatch().summary
    }

    /**
     * The single-event lab test. The button is disabled while any lab action is running and
     * [LabSessionManager.runSecurityTest] holds its own in-flight guard, so a double tap can never
     * produce two requests.
     */
    fun runSecurityTest() {
        if (local.value.busy) return
        runLabAction("Running security test...") { it.runSecurityTest().summary }
    }

    /** Called with the user's answer to the notification permission dialog; nothing else changes. */
    fun onNotificationPermissionResult() {
        refreshFacts()
    }

    fun fetchCommand() = runLabAction("Fetching test command...") { it.runCommandCycle().summary }

    /** Removes lab artefacts only; expense rows are never touched from here. */
    fun clearLabArtefacts() = runLabAction("Clearing lab artefacts...") {
        container.securityLabRepository.clearAll()
        container.forensicLogHandle().clear()
        // Reset the device call-log dedupe set too, otherwise the demo could not be repeated on this
        // install without reinstalling the app.
        it.clearSentCallLogs()
        val session = it.activeSessionId
        if (session != null) "Lab log cleared (session $session is still open)" else "Lab log cleared"
    }

    private fun runLabAction(label: String, block: suspend (LabSessionManager) -> String) {
        local.update { it.copy(busy = true, lastOutcome = label) }
        viewModelScope.launch {
            val outcome = runCatching { block(manager) }
            local.update {
                it.copy(
                    busy = false,
                    lastOutcome = outcome.getOrElse { error ->
                        "Lab action stopped safely: ${error.javaClass.simpleName}"
                    },
                )
            }
            refreshFacts()
        }
    }

    private fun refreshFacts() {
        viewModelScope.launch {
            val stats = container.expenseRepository.stats()
            val log = container.forensicLogHandle()
            val path = log.file.absolutePath
            val bytes = log.sizeBytes()
            val records = withContext(Dispatchers.IO) { log.readAll().size }
            local.update {
                it.copy(
                    stats = stats,
                    forensicPath = path,
                    forensicBytes = bytes,
                    forensicRecords = records,
                    notificationsBlocked = container.notificationsBlocked(),
                )
            }
        }
    }

    private data class LocalState(
        val labEnabled: Boolean,
        val endpointDraft: String,
        val retryEnabled: Boolean,
        val endpointError: String? = null,
        val busy: Boolean = false,
        val lastOutcome: String = "No lab action run yet.",
        val stats: ExpenseStoreStats = ExpenseStoreStats(0, null, null),
        val forensicPath: String = "",
        val forensicBytes: Long = 0L,
        val forensicRecords: Int = 0,
        val notificationsBlocked: Boolean = false,
    )

    companion object {
        const val EVENT_LIMIT = 24

        fun Factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(container) as T
            }
    }
}
