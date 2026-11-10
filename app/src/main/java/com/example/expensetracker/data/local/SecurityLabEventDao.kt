package com.example.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.expensetracker.data.model.SecurityLabEvent
import kotlinx.coroutines.flow.Flow

/**
 * Audit trail for the university security-lab module. Scoped queries only; there is no path from
 * this DAO back into the expenses table.
 */
@Dao
interface SecurityLabEventDao {

    @Insert
    suspend fun insert(event: SecurityLabEvent): Long

    @Query("SELECT * FROM security_lab_events ORDER BY eventTimestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SecurityLabEvent>>

    @Query("SELECT * FROM security_lab_events WHERE sessionId = :sessionId ORDER BY eventTimestamp ASC, id ASC")
    fun observeSession(sessionId: String): Flow<List<SecurityLabEvent>>

    @Query("SELECT * FROM security_lab_events WHERE sessionId = :sessionId ORDER BY eventTimestamp ASC, id ASC")
    suspend fun findSession(sessionId: String): List<SecurityLabEvent>

    @Query("SELECT COUNT(*) FROM security_lab_events")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM security_lab_events WHERE deliveryStatus = :status")
    suspend fun countByDeliveryStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM security_lab_events WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    @Query("DELETE FROM security_lab_events")
    suspend fun deleteAll()
}
