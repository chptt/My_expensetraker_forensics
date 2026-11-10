package com.example.expensetracker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.data.model.ExpenseCategory
import com.example.expensetracker.testing.UiTestSupport
import com.example.expensetracker.ui.theme.DailySpendTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseEntryUiTest {

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

    private fun storedExpenses(): List<Expense> =
        runBlocking { UiTestSupport.container.expenseRepository.observeAll().first() }

    @Test
    fun anExpenseTypedOnTheScreenIsStoredAndShowsOnTheDashboard() {
        launch()

        composeRule.onNodeWithText("Add expense").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Everything you enter stays in the local database on this device.")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("amount_field").performTextInput("249.50")
        composeRule.onNodeWithTag("description_field").performTextInput("Library fine")
        composeRule.onNodeWithText("Education").performClick()
        composeRule.onNodeWithText("UPI").performClick()
        composeRule.onNodeWithTag("save_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Library fine").assertIsDisplayed()
        composeRule.onNodeWithText("₹249.50").assertIsDisplayed()

        val stored = storedExpenses()
        assertEquals(1, stored.size)
        assertEquals(24_950L, stored.single().amountMinor)
        assertEquals(ExpenseCategory.EDUCATION.key, stored.single().category)
        assertEquals("UPI", stored.single().paymentMethod)
    }

    @Test
    fun emptyFieldsAreRefusedWithClearMessages() {
        launch()
        composeRule.onNodeWithText("Add expense").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("save_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Enter an amount").assertIsDisplayed()
        composeRule.onNodeWithText("Add a short description").assertIsDisplayed()
        assertTrue(storedExpenses().isEmpty())
    }

    @Test
    fun anAmountWithThreeDecimalsIsRejectedOnTheField() {
        launch()
        composeRule.onNodeWithText("Add expense").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("amount_field").performTextInput("12.345")
        composeRule.onNodeWithTag("description_field").performTextInput("Half rupee test")
        composeRule.onNodeWithTag("save_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Use digits with up to 2 decimals, e.g. 249.50").assertIsDisplayed()
        assertTrue(storedExpenses().isEmpty())
    }

    @Test
    fun anExistingExpenseKeepsItsIdentityWhenEdited() {
        val id = runBlocking { UiTestSupport.addExpense(10_000L, ExpenseCategory.FOOD.key, "Filter coffee") }
        launch()

        composeRule.onNodeWithText("Filter coffee").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edit expense").assertIsDisplayed()

        composeRule.onNodeWithTag("amount_field").performTextClearance()
        composeRule.onNodeWithTag("amount_field").performTextInput("35.00")
        composeRule.onNodeWithTag("save_button").performClick()
        composeRule.waitForIdle()

        val edited = storedExpenses().single()
        assertEquals(id, edited.id)
        assertEquals(3_500L, edited.amountMinor)
        assertEquals("Filter coffee", edited.description)
        composeRule.onNodeWithText("₹35.00").assertIsDisplayed()
    }
}
