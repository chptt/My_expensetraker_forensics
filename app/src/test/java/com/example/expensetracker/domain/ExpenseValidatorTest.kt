package com.example.expensetracker.domain

import com.example.expensetracker.data.model.ExpenseCategory
import com.example.expensetracker.data.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseValidatorTest {

    private val now = 1_760_000_000_000L

    private fun input(
        amount: String,
        description: String = "Lab bench supplies",
        category: String = ExpenseCategory.EDUCATION.key,
        method: String = PaymentMethod.UPI.key,
        date: Long = now - 86_400_000L,
    ) = ExpenseInput(
        amount = amount,
        categoryKey = category,
        description = description,
        paymentMethodKey = method,
        dateMillis = date,
    )

    @Test
    fun `a well formed entry becomes an expense stored in minor units`() {
        val result = ExpenseValidator.validate(input("249.50"), now)

        assertTrue(result is ValidationResult.Valid)
        val expense = (result as ValidationResult.Valid).expense
        assertEquals(24_950L, expense.amountMinor)
        assertEquals("Lab bench supplies", expense.description)
        assertEquals(ExpenseCategory.EDUCATION.key, expense.category)
        assertEquals(PaymentMethod.UPI.key, expense.paymentMethod)
        assertTrue(expense.id == 0L)
        assertTrue(expense.createdAt > 0L && expense.updatedAt >= expense.createdAt)
    }

    @Test
    fun `grouped and symbolled amounts parse to the same minor units`() {
        val plain = ExpenseValidator.validate(input("1250.00"), now) as ValidationResult.Valid
        val grouped = ExpenseValidator.validate(input("₹1,250.00"), now) as ValidationResult.Valid
        assertEquals(125_000L, plain.expense.amountMinor)
        assertEquals(plain.expense.amountMinor, grouped.expense.amountMinor)
    }

    @Test
    fun `amount is trimmed and description normalised`() {
        val valid = ExpenseValidator.validate(
            input("  80 ").copy(description = "  bus fare  "),
            now,
        ) as ValidationResult.Valid
        assertEquals(8_000L, valid.expense.amountMinor)
        assertEquals("bus fare", valid.expense.description)
    }

    @Test
    fun `blank amount is reported on the amount field`() {
        val invalid = ExpenseValidator.validate(input("   "), now) as ValidationResult.Invalid
        assertEquals(FieldError.AmountRequired, invalid.errors["amount"])
        assertEquals(setOf("amount"), invalid.errors.keys)
    }

    @Test
    fun `non numeric amount is rejected`() {
        for (raw in listOf("abc", "12.3.4", "-50", "0", "0.00", "1,2ab", "12..")) {
            val invalid = ExpenseValidator.validate(input(raw), now) as ValidationResult.Invalid
            assertTrue("expected an amount error for '$raw'", invalid.errors.containsKey("amount"))
        }
    }

    @Test
    fun `oversized amount gets its own message`() {
        val invalid = ExpenseValidator.validate(input("1234567890123.00"), now) as ValidationResult.Invalid
        assertEquals(FieldError.AmountTooLarge, invalid.errors["amount"])
    }

    @Test
    fun `amount at the upper bound is still refused`() {
        val invalid = ExpenseValidator.validate(input("999999999999"), now) as ValidationResult.Invalid
        assertEquals(FieldError.AmountInvalid, invalid.errors["amount"])
    }

    @Test
    fun `description length limits are enforced`() {
        val blank = ExpenseValidator.validate(input("10", description = "   "), now) as ValidationResult.Invalid
        assertEquals(FieldError.DescriptionRequired, blank.errors["description"])

        val tooLong = ExpenseValidator.validate(
            input("10", description = "x".repeat(ExpenseValidator.MAX_DESCRIPTION_LENGTH + 1)),
            now,
        ) as ValidationResult.Invalid
        assertEquals(FieldError.DescriptionTooLong, tooLong.errors["description"])

        val exact = ExpenseValidator.validate(
            input("10", description = "y".repeat(ExpenseValidator.MAX_DESCRIPTION_LENGTH)),
            now,
        )
        assertTrue(exact is ValidationResult.Valid)
    }

    @Test
    fun `future dates are refused`() {
        val invalid = ExpenseValidator.validate(input("10", date = now + 60_000L), now) as ValidationResult.Invalid
        assertEquals(FieldError.FutureDate, invalid.errors["date"])
    }

    @Test
    fun `unknown category and payment method are refused`() {
        val badCategory = ExpenseValidator.validate(
            input("10", category = "GAMBLING"),
            now,
        ) as ValidationResult.Invalid
        assertEquals(FieldError.UnknownCategory, badCategory.errors["category"])

        val badMethod = ExpenseValidator.validate(
            input("10", method = "paypal"),
            now,
        ) as ValidationResult.Invalid
        assertEquals(FieldError.UnknownPaymentMethod, badMethod.errors["paymentMethod"])
    }

    @Test
    fun `every failing field is reported in one pass`() {
        val invalid = ExpenseValidator.validate(
            ExpenseInput(amount = "", categoryKey = "NOPE", description = "", paymentMethodKey = "NOPE", dateMillis = now + 1),
            now,
        ) as ValidationResult.Invalid

        assertEquals(setOf("amount", "description", "category", "paymentMethod", "date"), invalid.errors.keys)
    }

    @Test
    fun `storage errors carry the underlying reason`() {
        val error: FieldError = FieldError.Storage("IllegalStateException: db locked")
        assertTrue(error.message.contains("db locked"))
    }

    @Test
    fun `all predefined categories and methods round trip`() {
        for (category in ExpenseCategory.entries) {
            for (method in PaymentMethod.entries) {
                val valid = ExpenseValidator.validate(
                    input("5.25", category = category.key, method = method.key),
                    now,
                )
                assertTrue("${category.key}/${method.key} should be valid", valid is ValidationResult.Valid)
                assertEquals(525L, (valid as ValidationResult.Valid).expense.amountMinor)
            }
        }
    }
}
