package com.example.expensetracker.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.data.repository.StorageResult
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.domain.ExpenseAggregator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<Expense> = emptyList(),
    val totalEntries: Int = 0,
    val selectedCategory: String? = null,
    val query: String = "",
    val pendingDeletion: Expense? = null,
    val message: String? = null,
    val isLoading: Boolean = true,
)

class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    private val categoryFilter = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val pendingDeletion = MutableStateFlow<Expense?>(null)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HistoryUiState> =
        combine(
            container.expenseRepository.observeAll(),
            categoryFilter,
            searchQuery,
            pendingDeletion,
            message,
        ) { all, category, query, deletion, notice ->
            HistoryUiState(
                items = ExpenseAggregator.filter(all, category, query),
                totalEntries = all.size,
                selectedCategory = category,
                query = query,
                pendingDeletion = deletion,
                message = notice,
                isLoading = false,
            )
        }
            .catch { error ->
                emit(
                    HistoryUiState(
                        isLoading = false,
                        message = "Could not read expenses (${error.javaClass.simpleName}).",
                    )
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setCategory(categoryKey: String?) {
        categoryFilter.update { categoryKey }
    }

    fun setQuery(raw: String) {
        searchQuery.update { raw }
    }

    fun requestDelete(expense: Expense) {
        pendingDeletion.update { expense }
    }

    fun cancelDelete() {
        pendingDeletion.update { null }
    }

    fun confirmDelete() {
        val target = pendingDeletion.value ?: return
        viewModelScope.launch {
            when (val result = container.expenseRepository.delete(target.id)) {
                is StorageResult.Success -> {
                    pendingDeletion.update { null }
                    message.value = "Deleted \"${target.description}\""
                }

                is StorageResult.Failure -> {
                    pendingDeletion.update { null }
                    message.value = "Delete failed: ${result.reason}"
                }
            }
        }
    }

    fun consumeMessage() {
        message.update { null }
    }

    companion object {
        fun Factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HistoryViewModel(container) as T
            }
    }
}
