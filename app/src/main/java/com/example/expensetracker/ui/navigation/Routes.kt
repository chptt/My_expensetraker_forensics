package com.example.expensetracker.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val DASHBOARD = "dashboard"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val LAB_DASHBOARD = "lab_dashboard"
    const val EXPENSE_EDIT = "expense/{expenseId}"

    fun expenseEdit(expenseId: Long) = "expense/$expenseId"
}

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    DASHBOARD(Routes.DASHBOARD, "Dashboard", Icons.Filled.Home),
    HISTORY(Routes.HISTORY, "History", Icons.Filled.DateRange),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
}
