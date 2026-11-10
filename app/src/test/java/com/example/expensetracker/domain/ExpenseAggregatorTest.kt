package com.example.expensetracker.domain

import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.data.model.ExpenseCategory
import com.example.expensetracker.data.model.PaymentMethod
import com.example.expensetracker.util.DateUtils
import com.example.expensetracker.util.YearMonthKey
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseAggregatorTest {

    private val utc = ZoneId.of("UTC")
    private val october = YearMonthKey(2026, 10)

    private fun expense(
        amountMinor: Long,
        category: ExpenseCategory,
        description: String,
        day: Int = 5,
    ) = Expense(
        id = 0L,
        amountMinor = amountMinor,
        category = category.key,
        description = description,
        paymentMethod = PaymentMethod.UPI.key,
        expenseDate = DateUtils.epochMillisOfDay(2026, 10, day, utc),
    )

    private val sample = listOf(
        expense(12_500L, ExpenseCategory.FOOD, "canteen lunch", day = 2),
        expense(45_000L, ExpenseCategory.SHOPPING, "desk lamp", day = 4),
        expense(2_500L, ExpenseCategory.FOOD, "filter coffee", day = 9),
        expense(30_000L, ExpenseCategory.TRANSPORT, "metro card top-up", day = 11),
        expense(10_000L, ExpenseCategory.EDUCATION, "lab manual print", day = 20),
    )

    @Test
    fun `monthly total and count are summed from the stored minor units`() {
        val summary = ExpenseAggregator.summarise(sample)

        assertEquals(100_000L, summary.totalMinor)
        assertEquals(5, summary.transactionCount)
        assertEquals(20_000L, summary.averagePerTransactionMinor)
    }

    @Test
    fun `an empty month summarises to zeroes and no rows`() {
        val summary = ExpenseAggregator.summarise(emptyList())
        assertEquals(0L, summary.totalMinor)
        assertEquals(0, summary.transactionCount)
        assertEquals(0L, summary.averagePerTransactionMinor)
        assertTrue(summary.categoryTotals.isEmpty())
    }

    @Test
    fun `category aggregation is ordered by spend and carries shares`() {
        val summary = ExpenseAggregator.summarise(sample)

        assertEquals(
            listOf("SHOPPING", "TRANSPORT", "FOOD", "EDUCATION"),
            summary.categoryTotals.map { it.categoryKey },
        )
        val food = summary.categoryTotals.first { it.categoryKey == "FOOD" }
        assertEquals(15_000L, food.totalMinor)
        assertEquals(2, food.transactionCount)
        assertEquals(0.15f, food.share, 0.0001f)
        assertEquals("Food", food.label)

        val total = summary.categoryTotals.sumOf { it.totalMinor }
        assertEquals(summary.totalMinor, total)
        assertEquals(1.0f, summary.categoryTotals.sumOf { it.share.toDouble() }.toFloat(), 0.0001f)
    }

    @Test
    fun `unknown stored category keys fall back to Other instead of dropping money`() {
        val summary = ExpenseAggregator.summarise(
            sample + expense(5_000L, ExpenseCategory.OTHER, "ferry"),
        )
        assertEquals(105_000L, summary.totalMinor)
        assertEquals(5_000L, summary.categoryTotals.first { it.categoryKey == "OTHER" }.totalMinor)
    }

    @Test
    fun `filtering by category keeps only that category`() {
        val food = ExpenseAggregator.filter(sample, ExpenseCategory.FOOD.key, "")
        assertEquals(listOf("canteen lunch", "filter coffee"), food.map { it.description })

        val all = ExpenseAggregator.filter(sample, null, "")
        assertEquals(sample.size, all.size)

        val blankCategory = ExpenseAggregator.filter(sample, "  ", "")
        assertEquals(sample.size, blankCategory.size)
    }

    @Test
    fun `search matches descriptions case insensitively`() {
        val matches = ExpenseAggregator.filter(sample, null, "METRO")
        assertEquals(1, matches.size)
        assertEquals("metro card top-up", matches.single().description)

        assertTrue(ExpenseAggregator.filter(sample, null, "nothing like this").isEmpty())
    }

    @Test
    fun `search also matches the category label`() {
        val matches = ExpenseAggregator.filter(sample, null, "shopping")
        assertEquals(listOf("desk lamp"), matches.map { it.description })
    }

    @Test
    fun `category and search combine`() {
        val matches = ExpenseAggregator.filter(sample, ExpenseCategory.FOOD.key, "coffee")
        assertEquals(1, matches.size)
        assertEquals("filter coffee", matches.single().description)

        assertTrue(ExpenseAggregator.filter(sample, ExpenseCategory.FOOD.key, "lamp").isEmpty())
    }

    @Test
    fun `month bounds feed the dao filter used by the dashboard`() {
        val bounds = DateUtils.monthBounds(october, utc)
        assertEquals(sample.size, sample.count { it.expenseDate in bounds })

        val november = sample.map { it.copy(expenseDate = DateUtils.epochMillisOfDay(2026, 11, 1, utc)) }
        assertTrue(november.none { it.expenseDate in bounds })
        assertEquals(november.size, november.count { it.expenseDate in DateUtils.monthBounds(YearMonthKey(2026, 11), utc) })
    }
}
