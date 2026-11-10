package com.example.expensetracker.data.repository

import com.example.expensetracker.data.local.ExpenseDao
import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.util.DateUtils
import com.example.expensetracker.util.YearMonthKey
import kotlinx.coroutines.flow.Flow

/** Result wrapper so ViewModels can surface storage failures without crashing the UI. */
sealed interface StorageResult<out T> {
    data class Success<T>(val value: T) : StorageResult<T>
    data class Failure(val reason: String) : StorageResult<Nothing>
}

data class ExpenseStoreStats(
    val transactionCount: Int,
    val oldestEntryMillis: Long?,
    val newestEntryMillis: Long?,
)

interface ExpenseRepository {
    fun observeAll(): Flow<List<Expense>>
    fun observeMonth(month: YearMonthKey): Flow<List<Expense>>
    fun observeRecent(limit: Int): Flow<List<Expense>>
    fun observeCount(): Flow<Int>
    suspend fun find(id: Long): Expense?
    suspend fun create(expense: Expense): StorageResult<Long>
    suspend fun update(expense: Expense): StorageResult<Unit>
    suspend fun delete(id: Long): StorageResult<Unit>
    suspend fun stats(): ExpenseStoreStats
}

class ExpenseRepositoryImpl(
    private val dao: ExpenseDao,
) : ExpenseRepository {

    override fun observeAll(): Flow<List<Expense>> = dao.observeAll()

    override fun observeMonth(month: YearMonthKey): Flow<List<Expense>> {
        val bounds = DateUtils.monthBounds(month)
        return dao.observeInRange(bounds.first, bounds.last + 1)
    }

    override fun observeRecent(limit: Int): Flow<List<Expense>> = dao.observeRecent(limit)

    override fun observeCount(): Flow<Int> = dao.observeCount()

    override suspend fun find(id: Long): Expense? = runCatching { dao.findById(id) }.getOrNull()

    override suspend fun create(expense: Expense): StorageResult<Long> =
        try {
            val now = System.currentTimeMillis()
            StorageResult.Success(dao.insert(expense.copy(createdAt = now, updatedAt = now)))
        } catch (error: Throwable) {
            StorageResult.Failure(describe(error))
        }

    override suspend fun update(expense: Expense): StorageResult<Unit> =
        try {
            val rows = dao.update(expense.copy(updatedAt = System.currentTimeMillis()))
            if (rows > 0) StorageResult.Success(Unit) else StorageResult.Failure("No expense with id ${expense.id}")
        } catch (error: Throwable) {
            StorageResult.Failure(describe(error))
        }

    override suspend fun delete(id: Long): StorageResult<Unit> =
        try {
            dao.deleteById(id)
            StorageResult.Success(Unit)
        } catch (error: Throwable) {
            StorageResult.Failure(describe(error))
        }

    override suspend fun stats(): ExpenseStoreStats =
        try {
            ExpenseStoreStats(
                transactionCount = dao.count(),
                oldestEntryMillis = dao.oldestExpenseDate(),
                newestEntryMillis = dao.newestExpenseDate(),
            )
        } catch (error: Throwable) {
            ExpenseStoreStats(0, null, null)
        }

    private fun describe(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message ?: "no message"}"
}
