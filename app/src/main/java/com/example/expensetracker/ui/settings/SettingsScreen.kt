package com.example.expensetracker.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.lab.SyntheticExpenseFactory
import com.example.expensetracker.ui.common.MessageBar
import com.example.expensetracker.ui.common.SectionCard
import com.example.expensetracker.util.DateUtils

@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenLabDashboard: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        SectionCard(title = "About") {
            InfoLine("App", "DailySpend")
            InfoLine("Version", "${state.appVersion} (${state.buildType})")
            InfoLine("Minimum Android", "8.0 (API 26)")
            InfoLine("Permissions", "Internet (lab transfers) and call log (device data, debug builds only)")
            InfoLine("Data collected", "Amount, category, description, payment method, date")
        }

        SectionCard(title = "Local database") {
            InfoLine(
                "Engine",
                "SQLite via Room, version ${com.example.expensetracker.data.local.AppDatabase.VERSION}",
            )
            InfoLine("Database file", "databases/${com.example.expensetracker.data.local.AppDatabase.NAME}")
            InfoLine("Stored expenses", "${state.storeStats.transactionCount}")
            InfoLine(
                "Oldest entry",
                state.storeStats.oldestEntryMillis
                    ?.let { DateUtils.formatTimestamp(it) }
                    ?: "-",
            )
            InfoLine(
                "Newest entry",
                state.storeStats.newestEntryMillis
                    ?.let { DateUtils.formatTimestamp(it) }
                    ?: "-",
            )
            Text(
                text = "Deleting the app or clearing its storage removes these rows. Only this database " +
                    "file is listed in the backup rules, so the lab folder never leaves the device " +
                    "through Android backup or device transfer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LabCard(state, viewModel, onOpenLabDashboard)

        SectionCard(title = "Recorded lab events") {
            if (state.events.isEmpty()) {
                Text(
                    text = "No lab events recorded yet. Events appear here only after you press a button above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.events.forEach { event ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = event.eventType,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = "${DateUtils.utcIso(event.eventTimestamp)} - " +
                                        "session ${event.sessionId.take(8)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = event.deliveryStatus,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenLabDashboard: () -> Unit,
) {
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.onNotificationPermissionResult() }

    SectionCard(title = "University security lab") {
        Text(
            text = "Controlled forensic exercise for an authorised mobile-security course. It sends " +
                "fabricated expense fixtures when you press the buttons below, and this emulator's own " +
                "call history whenever you save a new expense while the module is enabled. Your real " +
                "expenses are never included in any payload, and nothing runs unless the switch is on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(14.dp))

        ToggleRow(
            label = "Enable lab module",
            detail = "Off by default. Required before any test action runs.",
            checked = state.labEnabled,
            tag = "lab_enable_switch",
            onChecked = viewModel::setLabEnabled,
        )

        OutlinedTextField(
            value = state.endpointDraft,
            onValueChange = viewModel::onEndpointChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("endpoint_field"),
            singleLine = true,
            label = { Text("Lab server base URL") },
            supportingText = {
                Text(
                    state.endpointError
                        ?: "Genymotion uses http://10.0.3.2:5000/ and the Android Studio emulator uses " +
                            "http://10.0.2.2:5000/ to reach a Flask server on your host machine."
                )
            },
            isError = state.endpointError != null,
        )
        OutlinedButton(onClick = viewModel::saveEndpoint) { Text("Save endpoint") }

        Spacer(modifier = Modifier.height(10.dp))

        ToggleRow(
            label = "Allow one retry",
            detail = "Only used when you press the transfer button again after a failure.",
            checked = state.retryEnabled,
            tag = "lab_retry_switch",
            onChecked = viewModel::setRetryEnabled,
        )

        Spacer(modifier = Modifier.height(14.dp))

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoLine("Session", if (state.sessionActive) "open - ${state.sessionId}" else "closed")
            InfoLine(
                "Server status",
                state.connection?.let { snapshot ->
                    (if (snapshot.reachable) "reachable" else "not reachable") +
                        " - ${snapshot.detail} - ${snapshot.lastAttemptUtcMillis?.let { DateUtils.utcIso(it) } ?: "-"}"
                } ?: "not checked yet"
            )
            InfoLine(
                "Forensic log",
                "${state.forensicRecords} record(s), ${(state.forensicBytes / 1024).toInt()} KB on disk",
            )
            Text(
                text = state.forensicPath,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.lastOutcome.let { outcome ->
            MessageBar(
                text = outcome,
                modifier = Modifier.padding(top = 12.dp),
                negative = outcome.startsWith("Rejected") || outcome.startsWith("Not reachable") ||
                    outcome.startsWith("Lab action stopped safely") ||
                    outcome.startsWith("Security test failed"),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = if (state.sessionActive) viewModel::stopSession else viewModel::startSession,
                enabled = state.labEnabled && !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.sessionActive) "Stop test session" else "Start test session")
            }
            OutlinedButton(onClick = viewModel::runConnectionCheck, enabled = !state.busy) {
                Text("Ping")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = viewModel::runSecurityTest,
            enabled = state.labEnabled && !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("run_security_test"),
        ) { Text("Run Security Test") }
        Text(
            text = "Security-lab simulation: sends exactly one fabricated event " +
                "(${SyntheticExpenseFactory.SECURITY_TEST_FIXTURE_ID}) to the endpoint above, records " +
                "the result locally and posts a DailySpend notification with the same details. Your real " +
                "expenses are never included, nothing is read from other apps, and nothing is sent until " +
                "you press this button.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.labEnabled && state.notificationsBlocked) {
            OutlinedButton(
                onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .testTag("allow_notifications"),
            ) { Text("Allow lab result notifications") }
            Text(
                text = "Android 13 and newer need this before DailySpend can show the result of its own " +
                    "security test. The test, the Room rows and the JSONL log all run without it - " +
                    "declining only hides the notification, and the archived event then records " +
                    "posted=false. Nothing in this app can read notifications from other applications.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onOpenLabDashboard,
                modifier = Modifier.testTag("open_lab_dashboard"),
            ) { Text("Open lab dashboard") }
            OutlinedButton(
                onClick = viewModel::generateSynthetic,
                enabled = state.labEnabled && state.sessionActive && !state.busy,
            ) { Text("Generate synthetic fixtures") }
            OutlinedButton(
                onClick = viewModel::transferSynthetic,
                enabled = state.labEnabled && state.sessionActive && !state.busy,
            ) { Text("Send synthetic batch to server") }
            OutlinedButton(
                onClick = viewModel::fetchCommand,
                enabled = state.labEnabled && state.sessionActive && !state.busy,
            ) { Text("Fetch allowlisted test command") }
            OutlinedButton(
                onClick = viewModel::clearLabArtefacts,
                enabled = !state.busy,
            ) { Text("Clear lab events and log") }
        }

        Text(
            text = "Fixture amounts are fixed values from the lab script; the expense table is never " +
                "read. The device call log is a separate payload, sent only while the module is enabled " +
                "and only after Android grants READ_CALL_LOG.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    tag: String,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
