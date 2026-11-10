package com.example.expensetracker.testing

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ApplicationProvider
import com.example.expensetracker.DailySpendApplication
import com.example.expensetracker.data.local.AppDatabase
import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.lab.LabConfig
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Shared set-up for the instrumentation tests. The app keeps its real dependency graph: the tests
 * clear the same tables and preference file the app writes to, so what they exercise is exactly
 * what a user would see.
 */
object UiTestSupport {

    /** Must match AppContainer.LAB_PREFS_NAME; that constant is private to the container. */
    private const val LAB_PREFS = "daily_spend_lab"

    val app: DailySpendApplication
        get() = ApplicationProvider.getApplicationContext()

    val container: AppContainer get() = app.container

    fun resetState() {
        val context: Context = app
        runBlocking {
            val database = AppDatabase.get(context)
            database.expenseDao().deleteAll()
            database.securityLabEventDao().deleteAll()
        }
        context.getSharedPreferences(LAB_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        File(context.filesDir, "lab_events").listFiles()?.forEach { it.delete() }
        runBlocking {
            container.labManager.updateConfig(
                LabConfig.fromBuildConfig().copy(labEnabled = false, retryEnabled = false),
            )
            // updateConfig itself writes an audit row; the tests must start from an empty log.
            AppDatabase.get(context).securityLabEventDao().deleteAll()
        }
    }

    suspend fun addExpense(
        amountMinor: Long,
        category: String,
        description: String,
        method: String = "CASH",
        expenseDate: Long = System.currentTimeMillis(),
    ): Long = container.expenseRepository.create(
        Expense(
            amountMinor = amountMinor,
            category = category,
            description = description,
            paymentMethod = method,
            expenseDate = expenseDate,
        ),
    ).let { result ->
        (result as com.example.expensetracker.data.repository.StorageResult.Success).value
    }

    fun settle(composeRule: ComposeTestRule) {
        composeRule.waitForIdle()
    }
}
