package com.example.expensetracker.ui.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.lab.LabSessionManager
import com.example.expensetracker.util.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Value used as the trigger label for a manual dashboard sync (imports are automatic on save). */
const val LAB_TRIGGER_MANUAL = "MANUAL"

data class LabDashboardUiState(
    val labEnabled: Boolean = false,
    val sessionActive: Boolean = false,
    val sessionId: String? = null,
    val sessionStartUtc: String? = null,
    val callLogPermissionGranted: Boolean = false,
    val busy: Boolean = false,
    val lastOutcome: String = "No lab action run yet.",
)

/**
 * Drives the device call-log screen. Nothing here schedules work by itself: the call log is sent when
 * the user presses the button, or automatically after a new expense is saved while the lab is on.
 */
class LabDashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val manager: LabSessionManager = container.labManager

    // Built explicitly rather than through currentState(): reading state.value while the property is
    // still being initialised is a NullPointerException, and this is the one call site where the flow
    // legitimately does not exist yet.
    private val state = MutableStateFlow(
        LabDashboardUiState(
            labEnabled = manager.config.labEnabled,
            sessionActive = manager.isSessionActive(),
            sessionId = manager.activeSessionId,
            sessionStartUtc = manager.sessionStartMillis?.let { DateUtils.utcIso(it) },
            callLogPermissionGranted = container.callLogPermissionGranted(),
        ),
    )
    val uiState: StateFlow<LabDashboardUiState> = state.asStateFlow()

    fun startSession() = runAction("Opening lab session...") { manager.startSession().summary }

    fun stopSession() = runAction("Closing lab session...") { manager.endSession().summary }

    fun sendDeviceCallLogs() = runAction("Reading device call log...") {
        manager.syncDeviceCallLogs(LAB_TRIGGER_MANUAL).summary
    }

    private fun runAction(label: String, block: suspend () -> String) {
        if (state.value.busy) return
        state.value = currentState(busy = true, lastOutcome = label)
        viewModelScope.launch {
            val outcome = runCatching { block() }
            state.value = currentState(
                busy = false,
                lastOutcome = outcome.getOrElse { error ->
                    "Lab action stopped safely: ${error.javaClass.simpleName}"
                },
            )
        }
    }

    private fun currentState(
        busy: Boolean = state.value.busy,
        lastOutcome: String = state.value.lastOutcome,
    ): LabDashboardUiState = LabDashboardUiState(
        labEnabled = manager.config.labEnabled,
        sessionActive = manager.isSessionActive(),
        sessionId = manager.activeSessionId,
        sessionStartUtc = manager.sessionStartMillis?.let { DateUtils.utcIso(it) },
        callLogPermissionGranted = container.callLogPermissionGranted(),
        busy = busy,
        lastOutcome = lastOutcome,
    )

    companion object {
        fun Factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LabDashboardViewModel(container) as T
            }
    }
}
