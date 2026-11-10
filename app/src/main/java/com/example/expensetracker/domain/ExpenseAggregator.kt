package com.example.expensetracker.domain

import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.data.model.ExpenseCategory

/** Pure, JVM-testable roll-ups. Room only supplies rows; arithmetic lives here. */
object ExpenseAggregator {

    data class CategoryTotal(
        val categoryKey: String,
        val label: String,
        val totalMinor: Long,
        val transactionCount: Int,
        val share: Float,
    )

    data class MonthSummary(
        val totalMinor: Long,
        val transactionCount: Int,
        val categoryTotals: List<CategoryTotal>,
        val averagePerTransactionMinor: Long,
    )

    fun summarise(expenses: List<Expense>): MonthSummary {
        if (expenses.isEmpty()) return MonthSummary(0L, 0, emptyList(), 0L)

        val total = expenses.sumOf { it.amountMinor }
        val grouped = expenses
            .groupBy { ExpenseCategory.fromKey(it.category).key }
            .map { (key, rows) ->
                val groupTotal = rows.sumOf { it.amountMinor }
                CategoryTotal(
                    categoryKey = key,
                    label = ExpenseCategory.label(key),
                    totalMinor = groupTotal,
                    transactionCount = rows.size,
                    share = if (total > 0) groupTotal.toFloat() / total.toFloat() else 0f,
                )
            }
            .sortedWith(compareByDescending<CategoryTotal> { it.totalMinor }.thenBy { it.label })

        return MonthSummary(
            totalMinor = total,
            transactionCount = expenses.size,
            categoryTotals = grouped,
            averagePerTransactionMinor = if (expenses.isEmpty()) 0L else total / expenses.size,
        )
    }

    /** [category] matches a stored category key; null or blank means "all categories". */
    fun filter(expenses: List<Expense>, category: String?, query: String): List<Expense> {
        val normalizedCategory = category?.takeIf { it.isNotBlank() }
        val normalizedQuery = query.trim().lowercase()
        return expenses.filter { expense ->
            val categoryMatches =
                normalizedCategory == null ||
                    ExpenseCategory.fromKey(expense.category).key == ExpenseCategory.fromKey(normalizedCategory).key
            val queryMatches = normalizedQuery.isEmpty() ||
                expense.description.lowercase().contains(normalizedQuery) ||
                ExpenseCategory.label(expense.category).lowercase().contains(normalizedQuery)
            categoryMatches && queryMatches
        }
    }
}
