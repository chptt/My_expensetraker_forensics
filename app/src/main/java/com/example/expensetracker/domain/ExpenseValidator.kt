package com.example.expensetracker.domain

import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.data.model.ExpenseCategory
import com.example.expensetracker.data.model.PaymentMethod
import com.example.expensetracker.util.Money

/** Raw text coming straight off the add/edit screen. */
data class ExpenseInput(
    val amount: String = "",
    val categoryKey: String = ExpenseCategory.FOOD.key,
    val description: String = "",
    val paymentMethodKey: String = PaymentMethod.CASH.key,
    val dateMillis: Long = System.currentTimeMillis(),
)

sealed interface FieldError {
    val message: String
    data object AmountRequired : FieldError {
        override val message = "Enter an amount"
    }

    data object AmountInvalid : FieldError {
        override val message = "Use digits with up to 2 decimals, e.g. 249.50"
    }

    data object AmountTooLarge : FieldError {
        override val message = "Amount is too large"
    }

    data object DescriptionRequired : FieldError {
        override val message = "Add a short description"
    }

    data object DescriptionTooLong : FieldError {
        override val message = "Keep the description under 120 characters"
    }

    data object FutureDate : FieldError {
        override val message = "Date cannot be in the future"
    }

    data object UnknownCategory : FieldError {
        override val message = "Pick a category"
    }

    data object UnknownPaymentMethod : FieldError {
        override val message = "Pick a payment method"
    }

    data class Storage(val reason: String) : FieldError {
        override val message get() = "Could not save this entry: $reason"
    }
}

object ExpenseValidator {

    const val MAX_DESCRIPTION_LENGTH = 120

    fun validate(input: ExpenseInput, nowMillis: Long = System.currentTimeMillis()): ValidationResult {
        val errors = linkedMapOf<String, FieldError>()

        val amountMinor = parseAmount(input.amount, errors)

        val description = input.description.trim()
        when {
            description.isEmpty() -> errors["description"] = FieldError.DescriptionRequired
            description.length > MAX_DESCRIPTION_LENGTH -> errors["description"] = FieldError.DescriptionTooLong
        }

        if (ExpenseCategory.entries.none { it.key == input.categoryKey }) {
            errors["category"] = FieldError.UnknownCategory
        }
        if (PaymentMethod.fromKey(input.paymentMethodKey) == null) {
            errors["paymentMethod"] = FieldError.UnknownPaymentMethod
        }
        if (input.dateMillis > nowMillis) {
            errors["date"] = FieldError.FutureDate
        }

        if (errors.isNotEmpty() || amountMinor == null) return ValidationResult.Invalid(errors)

        val now = System.currentTimeMillis()
        return ValidationResult.Valid(
            Expense(
                amountMinor = amountMinor,
                category = input.categoryKey,
                description = description,
                paymentMethod = input.paymentMethodKey,
                expenseDate = input.dateMillis,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private fun parseAmount(raw: String, errors: MutableMap<String, FieldError>): Long? {
        if (raw.isBlank()) {
            errors["amount"] = FieldError.AmountRequired
            return null
        }
        val minor = Money.parseToMinor(raw)
        return when {
            minor == null -> {
                errors["amount"] =
                    if (raw.replace(",", "").replace(".", "").length > 14) FieldError.AmountTooLarge
                    else FieldError.AmountInvalid
                null
            }

            else -> minor
        }
    }
}

sealed interface ValidationResult {
    data class Valid(val expense: Expense) : ValidationResult
    data class Invalid(val errors: Map<String, FieldError>) : ValidationResult
}
