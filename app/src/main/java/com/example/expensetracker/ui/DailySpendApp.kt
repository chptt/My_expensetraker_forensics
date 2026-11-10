package com.example.expensetracker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.ui.dashboard.DashboardScreen
import com.example.expensetracker.ui.expenses.ExpenseEditScreen
import com.example.expensetracker.ui.history.HistoryScreen
import com.example.expensetracker.ui.lab.LabDashboardScreen
import com.example.expensetracker.ui.navigation.Routes
import com.example.expensetracker.ui.navigation.TopLevelDestination
import com.example.expensetracker.ui.settings.SettingsScreen

@Composable
fun DailySpendApp(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TopLevelDestination.entries.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(Routes.DASHBOARD) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    container = container,
                    onAddExpense = { navController.navigate(Routes.expenseEdit(-1L)) },
                    onEditExpense = { id -> navController.navigate(Routes.expenseEdit(id)) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                )
            }

            composable(Routes.HISTORY) {
                HistoryScreen(
                    container = container,
                    onEditExpense = { id -> navController.navigate(Routes.expenseEdit(id)) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onOpenLabDashboard = { navController.navigate(Routes.LAB_DASHBOARD) },
                )
            }

            composable(Routes.LAB_DASHBOARD) {
                LabDashboardScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.EXPENSE_EDIT,
                arguments = listOf(navArgument("expenseId") { type = NavType.LongType }),
            ) { entry ->
                val expenseId = entry.arguments?.getLong("expenseId") ?: -1L
                ExpenseEditScreen(
                    container = container,
                    expenseId = expenseId,
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}
