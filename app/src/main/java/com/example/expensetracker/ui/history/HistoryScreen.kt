package com.example.expensetracker.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expensetracker.data.model.ExpenseCategory
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.ui.common.EmptyState
import com.example.expensetracker.ui.common.ExpenseRow
import com.example.expensetracker.ui.common.MessageBar
import com.example.expensetracker.util.DateUtils
import com.example.expensetracker.util.Money

@Composable
fun HistoryScreen(
    container: AppContainer,
    onEditExpense: (Long) -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(container)),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Transactions",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Showing ${state.items.size} of ${state.totalEntries} stored expense(s)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_field"),
            singleLine = true,
            label = { Text("Search descriptions") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            shape = MaterialTheme.shapes.medium,
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.selectedCategory == null,
                    onClick = { viewModel.setCategory(null) },
                    label = { Text("All") },
                )
            }
            items(ExpenseCategory.entries.toList(), key = { it.key }) { category ->
                FilterChip(
                    selected = state.selectedCategory == category.key,
                    onClick = {
                        viewModel.setCategory(if (state.selectedCategory == category.key) null else category.key)
                    },
                    label = { Text(category.label) },
                )
            }
        }

        state.message?.let { message ->
            MessageBar(
                text = message,
                modifier = Modifier.fillMaxWidth(),
                negative = message.startsWith("Delete failed"),
            )
        }

        if (state.items.isEmpty()) {
            EmptyState(
                title = if (state.totalEntries == 0) "No expenses yet" else "Nothing matches this filter",
                body = if (state.totalEntries == 0) {
                    "Entries you add appear here, newest first."
                } else {
                    "Try a different category or clear the search text."
                },
                icon = Icons.Filled.Search,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.id }) { expense ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExpenseRow(
                            expense = expense,
                            modifier = Modifier.weight(1f),
                            onClick = { onEditExpense(expense.id) },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = { viewModel.requestDelete(expense) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete ${expense.description}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    state.pendingDeletion?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete this expense?") },
            text = {
                Text(
                    "\"${target.description}\" - ${Money.format(target.amountMinor)} on " +
                        DateUtils.formatTimestamp(target.expenseDate) + ". This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text("Keep it") }
            },
        )
    }
}
