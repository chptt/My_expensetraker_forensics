package com.example.expensetracker.ui.expenses

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expensetracker.data.model.ExpenseCategory
import com.example.expensetracker.data.model.PaymentMethod
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.ui.common.MessageBar
import com.example.expensetracker.ui.common.SectionCard
import com.example.expensetracker.util.DateUtils
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ExpenseEditScreen(
    container: AppContainer,
    expenseId: Long,
    onDone: () -> Unit,
    viewModel: ExpenseEditViewModel = viewModel(
        factory = ExpenseEditViewModel.Factory(container, expenseId)
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.saved) onDone()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                text = if (state.isNewEntry) "Add expense" else "Edit expense",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Everything you enter stays in the local database on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.loadFailed) {
            MessageBar(
                text = "That entry could not be loaded. It may have been deleted.",
                negative = true,
            )
        }

        SectionCard {
            OutlinedTextField(
                value = state.input.amount,
                onValueChange = { raw -> viewModel.onAmountChange(raw.filter { !it.isWhitespace() }) },
                label = { Text("Amount") },
                prefix = { Text("₹") },
                singleLine = true,
                isError = state.errors.containsKey(ExpenseEditViewModel.KEY_AMOUNT),
                supportingText = {
                    Text(
                        state.errors[ExpenseEditViewModel.KEY_AMOUNT]?.message
                            ?: "Up to two decimals, for example 249.50"
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("amount_field"),
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = state.input.description,
                onValueChange = { viewModel.onDescriptionChange(it) },
                label = { Text("Description") },
                singleLine = true,
                isError = state.errors.containsKey(ExpenseEditViewModel.KEY_DESCRIPTION),
                supportingText = {
                    Text(
                        state.errors[ExpenseEditViewModel.KEY_DESCRIPTION]?.message
                            ?: "Short note, for example \"Bus pass top-up\""
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("description_field"),
            )
        }

        SectionCard(title = "Category") {
            ChipRow(
                options = ExpenseCategory.entries.map { it.key to it.label },
                selected = state.input.categoryKey,
                onSelect = viewModel::onCategoryChange,
            )
            state.errors[ExpenseEditViewModel.KEY_CATEGORY]?.let {
                Text(
                    text = it.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard(title = "Payment method") {
            ChipRow(
                options = PaymentMethod.entries.map { it.key to it.label },
                selected = state.input.paymentMethodKey,
                onSelect = viewModel::onPaymentMethodChange,
            )
            state.errors[ExpenseEditViewModel.KEY_PAYMENT_METHOD]?.let {
                Text(
                    text = it.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard(title = "Date") {
            DateRow(
                millis = state.input.dateMillis,
                error = state.errors[ExpenseEditViewModel.KEY_DATE]?.message,
                onPicked = viewModel::onDateChange,
            )
        }

        state.errors[ExpenseEditViewModel.KEY_STORAGE]?.let { storageError ->
            MessageBar(text = storageError.message, negative = true)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving && !state.isLoadingOriginal && !state.loadFailed,
                modifier = Modifier
                    .weight(1f)
                    .testTag("save_button"),
            ) {
                Text(if (state.isSaving) "Saving..." else "Save expense")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (key, label) ->
            FilterChip(
                selected = key == selected,
                onClick = { onSelect(key) },
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(millis: Long, error: String?, onPicked: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { open = true }) {
                Text(DateUtils.formatTimestamp(millis))
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = {
                    onPicked(
                        LocalDate.now()
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                    )
                }
            ) {
                Text("Today")
            }
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (open) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { utcMillis ->
                            // The picker returns UTC midnight; shift back to local start of day so
                            // the stored date lands in the month the user selected.
                            onPicked(
                                java.time.Instant.ofEpochMilli(utcMillis)
                                    .atZone(java.time.ZoneOffset.UTC)
                                    .toLocalDate()
                                    .atStartOfDay(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()
                            )
                        }
                        open = false
                    }
                ) { Text("Choose") }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
