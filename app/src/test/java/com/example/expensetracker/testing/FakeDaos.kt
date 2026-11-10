package com.example.expensetracker.testing

import com.example.expensetracker.data.local.ExpenseDao
import com.example.expensetracker.data.local.SecurityLabEventDao
import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.data.model.SecurityLabEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory stand-in for the Room DAO so repository rules are testable on the JVM. */
class FakeExpenseDao : ExpenseDao {

    private val rows = MutableStateFlow<List<Expense>>(emptyList())
    private var nextId = 1L

    var failNextWrite: String? = null

    val stored: List<Expense> get() = rows.value

    override suspend fun insert(expense: Expense): Long {
        failNextWrite?.let { reason ->
            failNextWrite = null
            throw IllegalStateException(reason)
        }
        val id = nextId++
        rows.value = rows.value + expense.copy(id = id)
        return id
    }

    override suspend fun update(expense: Expense): Int {
        val hit = rows.value.any { it.id == expense.id }
        if (hit) rows.value = rows.value.map { if (it.id == expense.id) expense else it }
        return if (hit) 1 else 0
    }

    override suspend fun delete(expense: Expense) {
        rows.value = rows.value.filterNot { it.id == expense.id }
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun findById(id: Long): Expense? = rows.value.firstOrNull { it.id == id }

    override fun observeAll(): Flow<List<Expense>> = rows

    override fun observeInRange(startMillis: Long, endExclusiveMillis: Long): Flow<List<Expense>> =
        rows.map { list ->
            list.filter { it.expenseDate in startMillis until endExclusiveMillis }
                .sortedByDescending { it.expenseDate }
        }

    override fun observeRecent(limit: Int): Flow<List<Expense>> =
        rows.map { list -> list.sortedByDescending { it.expenseDate }.take(limit) }

    override suspend fun findRecent(limit: Int): List<Expense> =
        rows.value.sortedByDescending { it.expenseDate }.take(limit)

    override fun observeCount(): Flow<Int> = rows.map { it.size }

    override suspend fun count(): Int = rows.value.size

    override suspend fun oldestExpenseDate(): Long? = rows.value.minOfOrNull { it.expenseDate }

    override suspend fun newestExpenseDate(): Long? = rows.value.maxOfOrNull { it.expenseDate }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

class FakeSecurityLabEventDao : SecurityLabEventDao {

    private val rows = MutableStateFlow<List<SecurityLabEvent>>(emptyList())
    private var nextId = 1L

    val stored: List<SecurityLabEvent> get() = rows.value

    override suspend fun insert(event: SecurityLabEvent): Long {
        val id = nextId++
        rows.value = rows.value + event.copy(id = id)
        return id
    }

    override fun observeRecent(limit: Int): Flow<List<SecurityLabEvent>> =
        rows.map { list -> list.sortedByDescending { it.eventTimestamp }.take(limit) }

    override fun observeSession(sessionId: String): Flow<List<SecurityLabEvent>> =
        rows.map { list -> list.filter { it.sessionId == sessionId }.sortedBy { it.eventTimestamp } }

    override suspend fun findSession(sessionId: String): List<SecurityLabEvent> =
        rows.value.filter { it.sessionId == sessionId }.sortedBy { it.eventTimestamp }

    override fun observeCount(): Flow<Int> = rows.map { it.size }

    override suspend fun countByDeliveryStatus(status: String): Int =
        rows.value.count { it.deliveryStatus == status }

    override suspend fun countForSession(sessionId: String): Int =
        rows.value.count { it.sessionId == sessionId }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}
