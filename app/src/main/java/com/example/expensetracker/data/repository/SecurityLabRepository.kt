package com.example.expensetracker.data.repository

import com.example.expensetracker.data.local.SecurityLabEventDao
import com.example.expensetracker.data.model.SecurityLabEvent
import kotlinx.coroutines.flow.Flow

interface SecurityLabRepository {
    fun observeRecent(limit: Int): Flow<List<SecurityLabEvent>>
    fun observeSession(sessionId: String): Flow<List<SecurityLabEvent>>
    fun observeCount(): Flow<Int>
    suspend fun record(
        sessionId: String,
        eventType: String,
        payloadJson: String,
        deliveryStatus: String,
        timestampMillis: Long = System.currentTimeMillis(),
    ): Long

    suspend fun countByDeliveryStatus(status: String): Int
    suspend fun countForSession(sessionId: String): Int
    suspend fun eventsFor(sessionId: String): List<SecurityLabEvent>
    suspend fun clearAll()
}

class SecurityLabRepositoryImpl(
    private val dao: SecurityLabEventDao,
) : SecurityLabRepository {

    override fun observeRecent(limit: Int): Flow<List<SecurityLabEvent>> = dao.observeRecent(limit)

    override fun observeSession(sessionId: String): Flow<List<SecurityLabEvent>> =
        dao.observeSession(sessionId)

    override fun observeCount(): Flow<Int> = dao.observeCount()

    override suspend fun record(
        sessionId: String,
        eventType: String,
        payloadJson: String,
        deliveryStatus: String,
        timestampMillis: Long,
    ): Long = dao.insert(
        SecurityLabEvent(
            sessionId = sessionId,
            eventType = eventType,
            eventTimestamp = timestampMillis,
            payloadJson = payloadJson,
            deliveryStatus = deliveryStatus,
        )
    )

    override suspend fun countByDeliveryStatus(status: String): Int = dao.countByDeliveryStatus(status)

    override suspend fun countForSession(sessionId: String): Int =
        runCatching { dao.countForSession(sessionId) }.getOrDefault(0)

    override suspend fun eventsFor(sessionId: String): List<SecurityLabEvent> = dao.findSession(sessionId)

    override suspend fun clearAll(): Unit = dao.deleteAll()
}
