package com.example.expensetracker.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.ui.common.MessageBar
import com.example.expensetracker.ui.common.SectionCard

/**
 * The operator view for the device call-log lab exercise. Unlike the old generator screen, nothing
 * here manufactures a record: the only source of data is the emulator's own `CallLog.Calls` provider,
 * read through [com.example.expensetracker.lab.AndroidCallLogSource].
 */
@Composable
fun LabDashboardScreen(
    container: AppContainer,
    onBack: () -> Unit = {},
    viewModel: LabDashboardViewModel = viewModel(factory = LabDashboardViewModel.Factory(container)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Security lab dashboard",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                DeviceBanner()
                Text(
                    text = "This screen sends the emulator's own call history to the local lab server. " +
                        "The call log is read automatically each time you save a new expense while the " +
                        "lab module is on; the button below does the same thing on demand. Expense " +
                        "amounts, categories and descriptions are never part of this payload.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("lab_dashboard_back"),
                ) { Text("Back to settings") }
            }
        }

        item { SessionCard(state, viewModel) }
        item { DeviceCallLogCard(state, viewModel) }

        item { Spacer(modifier = Modifier.height(28.dp)) }
    }
}

@Composable
private fun DeviceBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("lab_device_banner"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = "REAL DEVICE CALL LOG - read from this emulator, not fabricated",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SessionCard(state: LabDashboardUiState, viewModel: LabDashboardViewModel) {
    SectionCard(title = "Lab session") {
        LabeledLine("State", if (state.sessionActive) "open" else "closed")
        LabeledLine("Session id", state.sessionId?.take(13) ?: "-")
        LabeledLine("Started", state.sessionStartUtc ?: "-")
        LabeledLine("Lab module", if (state.labEnabled) "enabled on this device" else "disabled")
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = if (state.sessionActive) viewModel::stopSession else viewModel::startSession,
            enabled = state.labEnabled && !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("lab_session_button"),
        ) {
            Text(if (state.sessionActive) "Stop session" else "Start session")
        }
        if (!state.labEnabled) {
            Text(
                text = "Turn the lab module on in Settings before using this screen.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCallLogCard(state: LabDashboardUiState, viewModel: LabDashboardViewModel) {
    SectionCard(title = "Device call log") {
        LabeledLine(
            "Permission",
            if (state.callLogPermissionGranted) "READ_CALL_LOG granted" else "READ_CALL_LOG not granted",
        )
        LabeledLine("Source", "CallLog.Calls provider on this device")
        LabeledLine("Automatic trigger", "Every new expense save")
        Spacer(modifier = Modifier.height(10.dp))

        if (!state.callLogPermissionGranted) {
            Text(
                text = "Android is refusing access to the call log, so nothing can be read. Grant " +
                    "READ_CALL_LOG in the system settings for DailySpend, or reinstall the debug build " +
                    "and accept the prompt at launch. The expense tracker keeps working either way.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        Button(
            onClick = viewModel::sendDeviceCallLogs,
            enabled = state.labEnabled && state.callLogPermissionGranted && !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("lab_send_device_call_logs"),
        ) { Text("Send device call logs now") }

        Text(
            text = "Only entries the server has not already accepted are sent; accepted record ids are " +
                "remembered on this device. A failed send leaves the records pending, so the next " +
                "expense save tries again.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        MessageBar(
            text = state.lastOutcome,
            modifier = Modifier.padding(top = 12.dp),
            negative = state.lastOutcome.isLabFailure(),
        )

        if (state.busy) {
            Text(
                text = "Working...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Keeps a failed or refused outcome red without needing a second state flag. */
private fun String.isLabFailure(): Boolean {
    val text = lowercase()
    return text.startsWith("lab action stopped safely") ||
        text.contains("failed") ||
        text.contains("denied") ||
        text.contains("refused") ||
        text.contains("unreachable") ||
        text.contains("timed out") ||
        text.contains("not granted")
}

@Composable
private fun LabeledLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (label == "Session id") FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f),
        )
    }
}
