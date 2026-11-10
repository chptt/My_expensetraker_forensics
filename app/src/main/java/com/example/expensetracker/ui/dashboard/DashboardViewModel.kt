package com.example.expensetracker.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.domain.ExpenseAggregator
import com.example.expensetracker.util.DateUtils
import com.example.expensetracker.util.YearMonthKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class DashboardUiState(
    val month: YearMonthKey = DateUtils.todayZoneMonth(),
    val monthTitle: String = "",
    val totalMinor: Long = 0L,
    val transactionCount: Int = 0,
    val categoryTotals: List<ExpenseAggregator.CategoryTotal> = emptyList(),
    val recent: List<Expense> = emptyList(),
    val isEmptyDatabase: Boolean = true,
    val allTimeEntries: Int = 0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val selectedMonth = MutableStateFlow(DateUtils.todayZoneMonth())

    private val monthExpenses = selectedMonth.flatMapLatest { month ->
        container.expenseRepository.observeMonth(month)
    }

    private val recentExpenses = container.expenseRepository.observeRecent(RECENT_LIMIT)
    private val totalRowCount = container.expenseRepository.observeCount()

    val uiState: StateFlow<DashboardUiState> =
        combine(monthExpenses, recentExpenses, totalRowCount) { monthly, recent, rowCount ->
            val summary = ExpenseAggregator.summarise(monthly)
            val month = selectedMonth.value
            DashboardUiState(
                month = month,
                monthTitle = DateUtils.monthTitle(month),
                totalMinor = summary.totalMinor,
                transactionCount = summary.transactionCount,
                categoryTotals = summary.categoryTotals,
                recent = recent,
                isEmptyDatabase = rowCount == 0,
                allTimeEntries = rowCount,
                isLoading = false,
                errorMessage = null,
            )
        }
            .catch { error ->
                val month = selectedMonth.value
                emit(
                    DashboardUiState(
                        month = month,
                        monthTitle = DateUtils.monthTitle(month),
                        isLoading = false,
                        isEmptyDatabase = false,
                        errorMessage = "Could not read the local database (${error.javaClass.simpleName}). " +
                            "Your entries are still on the device.",
                    )
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DashboardUiState(monthTitle = DateUtils.monthTitle(selectedMonth.value)),
            )

    fun previousMonth() {
        selectedMonth.update { it.shifted(-1) }
    }

    fun nextMonth() {
        val candidate = selectedMonth.value.shifted(1)
        if (candidate <= DateUtils.todayZoneMonth()) selectedMonth.update { candidate }
    }

    companion object {
        const val RECENT_LIMIT = 6

        fun Factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(container) as T
            }
    }
}
