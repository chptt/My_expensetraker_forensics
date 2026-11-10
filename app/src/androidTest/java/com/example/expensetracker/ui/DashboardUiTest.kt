package com.example.expensetracker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.expensetracker.data.model.ExpenseCategory
import com.example.expensetracker.testing.UiTestSupport
import com.example.expensetracker.ui.theme.DailySpendTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun cleanSlate() {
        UiTestSupport.resetState()
    }

    private fun launch() {
        composeRule.setContent {
            DailySpendTheme { DailySpendApp(UiTestSupport.container) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun aNewInstallShowsTheEmptyDashboard() {
        launch()

        composeRule.onNodeWithText("DailySpend").assertIsDisplayed()
        composeRule.onNodeWithText("Your money, kept on this device").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing tracked yet").assertIsDisplayed()
        composeRule.onNodeWithText("No transactions this month").assertIsDisplayed()
        composeRule.onNodeWithText("Add expense").assertIsDisplayed()
    }

    @Test
    fun totalsAndCategoryBreakdownAppearOnceExpensesExist() {
        runBlocking {
            UiTestSupport.addExpense(12_500L, ExpenseCategory.FOOD.key, "Canteen lunch")
            UiTestSupport.addExpense(5_500L, ExpenseCategory.TRANSPORT.key, "Metro card top-up")
        }

        launch()

        composeRule.onNodeWithText("₹180.00").assertIsDisplayed()
        composeRule.onNodeWithText("Spending by category").assertIsDisplayed()
        composeRule.onNodeWithText("Recent transactions").assertIsDisplayed()
        composeRule.onNodeWithText("Canteen lunch").assertIsDisplayed()
        composeRule.onNodeWithText("Metro card top-up").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing tracked yet").assertDoesNotExist()
    }

    @Test
    fun theMonthSelectorMovesOffTheCurrentMonthAndBack() {
        runBlocking { UiTestSupport.addExpense(50_000L, ExpenseCategory.SHOPPING.key, "Lab notebook") }

        launch()

        composeRule.onNodeWithText("₹500.00").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Previous month").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No entries in this month").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Next month").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("₹500.00").assertIsDisplayed()
    }
}
