package com.example.expensetracker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
class HistoryUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun cleanSlate() {
        UiTestSupport.resetState()
        runBlocking {
            UiTestSupport.addExpense(12_500L, ExpenseCategory.FOOD.key, "Canteen lunch")
            UiTestSupport.addExpense(5_500L, ExpenseCategory.TRANSPORT.key, "Metro card top-up")
            UiTestSupport.addExpense(1_990L, ExpenseCategory.EDUCATION.key, "Library fine")
        }
    }

    private fun openHistory() {
        composeRule.setContent {
            DailySpendTheme { DailySpendApp(UiTestSupport.container) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("History").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun everyStoredExpenseIsListedNewestFirst() {
        openHistory()

        composeRule.onNodeWithText("Transactions").assertIsDisplayed()
        composeRule.onNodeWithText("Showing 3 of 3 stored expense(s)").assertIsDisplayed()
        composeRule.onNodeWithText("Canteen lunch").assertIsDisplayed()
        composeRule.onNodeWithText("Metro card top-up").assertIsDisplayed()
        composeRule.onNodeWithText("Library fine").assertIsDisplayed()
        composeRule.onNodeWithText("₹125.00").assertIsDisplayed()
    }

    @Test
    fun searchingDescriptionsNarrowsTheList() {
        openHistory()

        composeRule.onNodeWithTag("search_field").performTextInput("metro")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Showing 1 of 3 stored expense(s)").assertIsDisplayed()
        composeRule.onNodeWithText("Metro card top-up").assertIsDisplayed()
        composeRule.onNodeWithText("Canteen lunch").assertDoesNotExist()

        composeRule.onNodeWithTag("search_field").performTextClearance()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Showing 3 of 3 stored expense(s)").assertIsDisplayed()
    }

    @Test
    fun aCategoryChipFiltersAndASearchWithNoMatchShowsTheEmptyState() {
        openHistory()

        composeRule.onNodeWithText("Food").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Showing 1 of 3 stored expense(s)").assertIsDisplayed()

        composeRule.onNodeWithText("Food").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Showing 3 of 3 stored expense(s)").assertIsDisplayed()

        composeRule.onNodeWithTag("search_field").performTextInput("nothing like this")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Nothing matches this filter").assertIsDisplayed()
        composeRule.onNodeWithText("Showing 0 of 3 stored expense(s)").assertIsDisplayed()
    }

    @Test
    fun deletionNeedsConfirmationAndCancellingKeepsTheRow() {
        openHistory()

        composeRule.onNodeWithContentDescription("Delete Library fine").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete this expense?").assertIsDisplayed()
        composeRule.onNodeWithText("Library fine").assertExists()

        composeRule.onNodeWithText("Keep it").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Delete this expense?").assertDoesNotExist()
        composeRule.onNodeWithText("Showing 3 of 3 stored expense(s)").assertIsDisplayed()
        composeRule.onNodeWithText("Library fine").assertIsDisplayed()
    }

    @Test
    fun confirmingDeletionRemovesTheExpenseFromStorage() {
        openHistory()

        composeRule.onNodeWithContentDescription("Delete Metro card top-up").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete this expense?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Deleted \"Metro card top-up\"").assertIsDisplayed()
        composeRule.onNodeWithText("Showing 2 of 2 stored expense(s)").assertIsDisplayed()
        composeRule.onNodeWithText("Metro card top-up").assertDoesNotExist()

        // The dashboard reads the same table, so the totals drop with it.
        composeRule.onNodeWithContentDescription("Dashboard").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("₹144.90").assertIsDisplayed()
    }

    @Test
    fun theSearchFieldKeepsWhatTheUserTypes() {
        openHistory()

        composeRule.onNodeWithTag("search_field").performTextInput("Canteen")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Showing 1 of 3 stored expense(s)").assertIsDisplayed()

        composeRule.onNodeWithTag("search_field").performTextClearance()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Nothing matches this filter").assertDoesNotExist()
    }
}
