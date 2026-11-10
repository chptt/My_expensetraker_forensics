package com.example.expensetracker.ui.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.data.repository.StorageResult
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.domain.ExpenseInput
import com.example.expensetracker.domain.ExpenseValidator
import com.example.expensetracker.domain.FieldError
import com.example.expensetracker.domain.ValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExpenseEditUiState(
    val input: ExpenseInput = ExpenseInput(),
    val errors: Map<String, FieldError> = emptyMap(),
    val isNewEntry: Boolean = true,
    val isLoadingOriginal: Boolean = false,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val loadFailed: Boolean = false,
)

class ExpenseEditViewModel(
    container: AppContainer,
    private val expenseId: Long,
) : ViewModel() {

    private val repository = container.expenseRepository
    private val lab = container.labManager
    private var original: Expense? = null

    private val _uiState = MutableStateFlow(
        ExpenseEditUiState(
            isNewEntry = expenseId == NEW_ENTRY_ID,
            isLoadingOriginal = expenseId != NEW_ENTRY_ID,
        )
    )
    val uiState: StateFlow<ExpenseEditUiState> = _uiState.asStateFlow()

    init {
        if (expenseId != NEW_ENTRY_ID) loadExisting(expenseId)
    }

    private fun loadExisting(id: Long) {
        viewModelScope.launch {
            val found = repository.find(id)
            if (found == null) {
                _uiState.update { it.copy(isLoadingOriginal = false, loadFailed = true) }
                return@launch
            }
            original = found
            _uiState.update {
                it.copy(
                    isLoadingOriginal = false,
                    input = ExpenseInput(
                        amount = plainAmount(found.amountMinor),
                        categoryKey = found.category,
                        description = found.description,
                        paymentMethodKey = found.paymentMethod,
                        dateMillis = found.expenseDate,
                    ),
                )
            }
        }
    }

    fun onAmountChange(value: String) = changeField(KEY_AMOUNT) { it.copy(amount = value) }

    fun onDescriptionChange(value: String) = changeField(KEY_DESCRIPTION) { it.copy(description = value) }

    fun onCategoryChange(value: String) = changeField(KEY_CATEGORY) { it.copy(categoryKey = value) }

    fun onPaymentMethodChange(value: String) =
        changeField(KEY_PAYMENT_METHOD) { it.copy(paymentMethodKey = value) }

    fun onDateChange(millis: Long) = changeField(KEY_DATE) { it.copy(dateMillis = millis) }

    private fun changeField(errorKey: String, transform: (ExpenseInput) -> ExpenseInput) {
        _uiState.update { state ->
            state.copy(
                input = transform(state.input),
                errors = state.errors - errorKey,
                saved = false,
            )
        }
    }

    fun save() {
        if (_uiState.value.isSaving) return
        val state = _uiState.value
        val result = ExpenseValidator.validate(state.input)
        if (result is ValidationResult.Invalid) {
            _uiState.update { it.copy(errors = result.errors, saved = false) }
            return
        }
        val validated = (result as ValidationResult.Valid).expense

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val existing = original
            val isEdit = existing != null && !state.isNewEntry
            val candidate = if (isEdit) {
                validated.copy(id = existing!!.id, createdAt = existing.createdAt)
            } else {
                validated
            }
            val storageResult = if (isEdit) repository.update(candidate) else repository.create(candidate)
            when (storageResult) {
                is StorageResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, saved = true) }
                    // Lab hook: only a newly created expense, and only after Room accepted it. Runs on
                    // the manager's own scope because this screen navigates away immediately;
                    // viewModelScope would be cancelled before the call-log transfer finished.
                    if (!isEdit) lab.onExpenseCreatedInBackground()
                }

                is StorageResult.Failure -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        errors = it.errors + (KEY_STORAGE to FieldError.Storage(storageResult.reason)),
                    )
                }
            }
        }
    }

    private fun plainAmount(minor: Long): String {
        val whole = minor / 100
        val fraction = minor % 100
        return if (fraction == 0L) whole.toString() else "$whole.${fraction.toString().padStart(2, '0')}"
    }

    companion object {
        const val NEW_ENTRY_ID = -1L
        const val KEY_AMOUNT = "amount"
        const val KEY_DESCRIPTION = "description"
        const val KEY_CATEGORY = "category"
        const val KEY_PAYMENT_METHOD = "paymentMethod"
        const val KEY_DATE = "date"
        const val KEY_STORAGE = "storage"

        fun Factory(container: AppContainer, expenseId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ExpenseEditViewModel(container, expenseId) as T
            }
    }
}
