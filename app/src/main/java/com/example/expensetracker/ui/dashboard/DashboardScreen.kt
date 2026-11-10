package com.example.expensetracker.ui.dashboard

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.ui.common.CategoryBreakdown
import com.example.expensetracker.ui.common.EmptyState
import com.example.expensetracker.ui.common.ExpenseRow
import com.example.expensetracker.ui.common.MessageBar
import com.example.expensetracker.ui.common.MetricTile
import com.example.expensetracker.ui.common.PrimaryStatCard
import com.example.expensetracker.ui.common.SectionCard
import com.example.expensetracker.util.DateUtils
import com.example.expensetracker.util.Money

@Composable
fun DashboardScreen(
    container: AppContainer,
    onAddExpense: () -> Unit,
    onEditExpense: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory(container)),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val currentMonth = DateUtils.todayZoneMonth()

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddExpense,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add expense") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "DailySpend",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Your money, kept on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                MonthSelector(
                    monthTitle = state.monthTitle,
                    canGoNext = state.month < currentMonth,
                    onPrevious = viewModel::previousMonth,
                    onNext = viewModel::nextMonth,
                )
            }

            state.errorMessage?.let { message ->
                item { MessageBar(text = message, negative = true) }
            }

            item {
                PrimaryStatCard(
                    label = "Spent in ${state.monthTitle}",
                    amountMinor = state.totalMinor,
                    caption = if (state.transactionCount == 0) {
                        "No transactions this month"
                    } else {
                        "${state.transactionCount} transaction(s) - average " +
                            Money.format(state.totalMinor / state.transactionCount)
                    },
                )
            }

            if (state.isEmptyDatabase) {
                item {
                    SectionCard {
                        EmptyState(
                            title = "Nothing tracked yet",
                            body = "Add your first expense and the dashboard fills in automatically.",
                            icon = Icons.Filled.DateRange,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onAddExpense, modifier = Modifier.fillMaxWidth()) {
                            Text("Add first expense")
                        }
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricTile(
                            label = "Transactions",
                            value = state.transactionCount.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = "Categories used",
                            value = state.categoryTotals.size.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = "All-time entries",
                            value = state.allTimeEntries.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item {
                    SectionCard(title = "Spending by category") {
                        CategoryBreakdown(state.categoryTotals)
                    }
                }

                item {
                    SectionCard(
                        title = "Recent transactions",
                        action = {
                            TextButton(onClick = onOpenHistory) {
                                Text("View all", style = MaterialTheme.typography.labelLarge)
                            }
                        },
                    ) {
                        if (state.recent.isEmpty()) {
                            EmptyState(
                                title = "No entries in this month",
                                body = "Switch months or add an expense.",
                                icon = Icons.Filled.List,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.recent.forEach { expense ->
                                    ExpenseRow(
                                        expense = expense,
                                        onClick = { onEditExpense(expense.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthSelector(
    monthTitle: String,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Text(
            text = monthTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (canGoNext) "" else "current",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next month")
        }
    }
}
