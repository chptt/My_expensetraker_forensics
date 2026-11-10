package com.example.expensetracker.data.repository

import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.data.model.ExpenseCategory
import com.example.expensetracker.testing.FakeExpenseDao
import com.example.expensetracker.util.DateUtils
import com.example.expensetracker.util.YearMonthKey
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseRepositoryTest {

    private val utc = ZoneId.of("UTC")
    private val dao = FakeExpenseDao()
    private val repository = ExpenseRepositoryImpl(dao)

    private fun expense(
        amountMinor: Long = 5_000L,
        category: ExpenseCategory = ExpenseCategory.FOOD,
        description: String = "tea",
        year: Int = 2026,
        month: Int = 10,
        day: Int = 5,
    ) = Expense(
        amountMinor = amountMinor,
        category = category.key,
        description = description,
        paymentMethod = "CASH",
        expenseDate = DateUtils.epochMillisOfDay(year, month, day, utc),
    )

    @Test
    fun `creating an expense assigns an id and stamps the timestamps`() = runTest {
        val result = repository.create(expense())

        assertTrue(result is StorageResult.Success)
        val id = (result as StorageResult.Success).value
        assertEquals(1L, id)
        val stored = dao.stored.single()
        assertEquals(id, stored.id)
        assertTrue(stored.createdAt > 0L)
        assertTrue(stored.updatedAt >= stored.createdAt)
    }

    @Test
    fun `editing replaces the row and keeps the creation stamp`() = runTest {
        val id = (repository.create(expense(description = "bus fare")) as StorageResult.Success).value
        val created = repository.find(id)!!

        val updated = created.copy(amountMinor = 7_500L, description = "bus fare (corrected)")
        val result = repository.update(updated)

        assertTrue(result is StorageResult.Success)
        val stored = repository.find(id)!!
        assertEquals(7_500L, stored.amountMinor)
        assertEquals("bus fare (corrected)", stored.description)
        assertEquals(created.createdAt, stored.createdAt)
        assertTrue(stored.updatedAt >= created.updatedAt)
    }

    @Test
    fun `editing an unknown id fails without throwing`() = runTest {
        repository.create(expense())
        val result = repository.update(expense().copy(id = 9_999L))
        assertTrue(result is StorageResult.Failure)
        assertTrue((result as StorageResult.Failure).reason.contains("9999"))
    }

    @Test
    fun `deleting removes only the chosen row`() = runTest {
        val keepId = (repository.create(expense(description = "keep")) as StorageResult.Success).value
        val dropId = (repository.create(expense(description = "drop")) as StorageResult.Success).value

        assertTrue(repository.delete(dropId) is StorageResult.Success)
        assertEquals(1, dao.stored.size)
        assertEquals("keep", repository.find(keepId)?.description)
        assertNull(repository.find(dropId))
    }

    @Test
    fun `a database write error is surfaced as a storage failure`() = runTest {
        dao.failNextWrite = "disk is full"

        val result = repository.create(expense())

        assertTrue(result is StorageResult.Failure)
        assertEquals("IllegalStateException: disk is full", (result as StorageResult.Failure).reason)
        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `stats report count and the date span`() = runTest {
        repository.create(expense(year = 2026, month = 3, day = 1, description = "oldest"))
        repository.create(expense(year = 2026, month = 10, day = 12, description = "newest"))

        val stats = repository.stats()

        assertEquals(2, stats.transactionCount)
        assertEquals(DateUtils.epochMillisOfDay(2026, 3, 1, utc), stats.oldestEntryMillis)
        assertEquals(DateUtils.epochMillisOfDay(2026, 10, 12, utc), stats.newestEntryMillis)
    }

    @Test
    fun `stats on an empty store are safe`() = runTest {
        val stats = repository.stats()
        assertEquals(0, stats.transactionCount)
        assertNull(stats.oldestEntryMillis)
        assertNull(stats.newestEntryMillis)
    }

    @Test
    fun `month queries return only that month, newest first`() = runTest {
        repository.create(expense(month = 10, day = 4, description = "early oct"))
        repository.create(expense(month = 10, day = 21, description = "late oct"))
        repository.create(expense(month = 11, day = 1, description = "november"))

        val october = repository.observeMonth(YearMonthKey(2026, 10)).first()

        assertEquals(listOf("late oct", "early oct"), october.map { it.description })
        assertEquals(1, repository.observeMonth(YearMonthKey(2026, 11)).first().size)
        assertTrue(repository.observeMonth(YearMonthKey(2025, 6)).first().isEmpty())
    }

    @Test
    fun `recent list is limited and ordered`() = runTest {
        listOf(1, 2, 3, 4, 5).forEach { day ->
            repository.create(expense(day = day, description = "day $day"))
        }

        val recent = repository.observeRecent(3).first()

        assertEquals(3, recent.size)
        assertEquals(listOf("day 5", "day 4", "day 3"), recent.map { it.description })
        assertEquals(5, repository.observeCount().first())
    }

    @Test
    fun `an empty store yields empty flows rather than errors`() = runTest {
        assertNotNull(repository.observeAll().first())
        assertTrue(repository.observeAll().first().isEmpty())
        assertTrue(repository.observeMonth(YearMonthKey(2026, 10)).first().isEmpty())
        assertEquals(0, repository.observeCount().first())
        assertNull(repository.find(1L))
    }
}
