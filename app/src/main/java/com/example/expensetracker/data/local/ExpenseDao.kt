package com.example.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.expensetracker.data.model.Expense
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {

    @Insert
    suspend fun insert(expense: Expense): Long

    @Update
    suspend fun update(expense: Expense): Int

    @Delete
    suspend fun delete(expense: Expense)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun findById(id: Long): Expense?

    @Query("SELECT * FROM expenses ORDER BY expenseDate DESC, id DESC")
    fun observeAll(): Flow<List<Expense>>

    @Query(
        """
        SELECT * FROM expenses
        WHERE expenseDate >= :startMillis AND expenseDate < :endExclusiveMillis
        ORDER BY expenseDate DESC, id DESC
        """
    )
    fun observeInRange(startMillis: Long, endExclusiveMillis: Long): Flow<List<Expense>>

    @Query("SELECT * FROM expenses ORDER BY expenseDate DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Expense>>

    @Query("SELECT * FROM expenses ORDER BY expenseDate DESC, id DESC LIMIT :limit")
    suspend fun findRecent(limit: Int): List<Expense>

    @Query("SELECT COUNT(*) FROM expenses")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun count(): Int

    @Query("SELECT MIN(expenseDate) FROM expenses")
    suspend fun oldestExpenseDate(): Long?

    @Query("SELECT MAX(expenseDate) FROM expenses")
    suspend fun newestExpenseDate(): Long?

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()
}
