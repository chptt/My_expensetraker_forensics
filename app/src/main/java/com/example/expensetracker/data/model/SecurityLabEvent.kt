package com.example.expensetracker.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Audit row written by the opt-in university security-lab module only. Normal expense tracking
 * never writes here, and nothing in this table is ever mirrored from [Expense].
 */
@Entity(
    tableName = "security_lab_events",
    indices = [Index("sessionId"), Index("eventType")],
)
data class SecurityLabEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: String,
    val eventType: String,
    val eventTimestamp: Long,
    val payloadJson: String,
    val deliveryStatus: String,
)
