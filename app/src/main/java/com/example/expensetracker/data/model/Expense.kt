package com.example.expensetracker.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-entered expense. [amountMinor] stores currency minor units (paise/cents) so that money
 * never travels through floating point.
 */
@Entity(
    tableName = "expenses",
    indices = [Index("expenseDate"), Index("category")],
)
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val amountMinor: Long,
    val category: String,
    val description: String,
    val paymentMethod: String,
    val expenseDate: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
