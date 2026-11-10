package com.example.expensetracker.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.expensetracker.data.model.ExpenseCategory
import com.example.expensetracker.data.model.SecurityLabEvent
import com.example.expensetracker.lab.LabEventTypes
import com.example.expensetracker.testing.UiTestSupport
import com.example.expensetracker.ui.theme.DailySpendTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SettingsLabUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A real user note that must never reach a lab record. */
    private val privateNote = "Canteen lunch"

    @Before
    fun cleanSlate() {
        UiTestSupport.resetState()
        runBlocking { UiTestSupport.addExpense(12_500L, ExpenseCategory.FOOD.key, privateNote) }
    }

    private fun openSettings() {
        composeRule.setContent {
            DailySpendTheme { DailySpendApp(UiTestSupport.container) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
    }

    /** The lab card is taller than one screen, so controls are scrolled into view before tapping. */
    private fun tap(node: SemanticsNodeInteraction) {
        node.performScrollTo()
        node.performClick()
        composeRule.waitForIdle()
    }

    private fun enableLabModule() {
        tap(composeRule.onNodeWithTag("lab_enable_switch"))
        composeRule.waitUntil(timeoutMillis = 15_000) {
            UiTestSupport.container.labManager.config.labEnabled
        }
    }

    private fun labEvents(sessionId: String): List<SecurityLabEvent> =
        runBlocking { UiTestSupport.container.securityLabRepository.eventsFor(sessionId) }

    @Test
    fun theLabCardIsPresentButSwitchedOffOnAFreshInstall() {
        openSettings()

        composeRule.onNodeWithText("University security lab").assertIsDisplayed()
        composeRule.onNodeWithText("Enable lab module").assertExists()
        composeRule.onNodeWithText("No lab action run yet.").assertExists()
        composeRule.onNodeWithText("closed").assertExists()
        composeRule.onNodeWithText("not checked yet").assertExists()
        composeRule.onNodeWithText(
            "Genymotion uses http://10.0.3.2:5000/", substring = true,
        ).assertExists()
        composeRule.onNodeWithText("No lab events recorded yet", substring = true).assertExists()
        composeRule.onNodeWithTag("lab_enable_switch").assertIsOff()
        composeRule.onNodeWithTag("lab_retry_switch").assertIsOff()
        // Nothing can run while the module is off, so the session button is inert.
        composeRule.onNodeWithText("Start test session").assertIsNotEnabled()
        composeRule.onNodeWithTag("run_security_test").assertIsNotEnabled()
    }

    @Test
    fun aStartedSessionRecordsSyntheticEventsAndNeverTheUserExpenses() {
        openSettings()
        enableLabModule()

        tap(composeRule.onNodeWithText("Start test session"))
        composeRule.waitUntilAtLeastOneExists(
            matcher = hasText("Lab session started", substring = true),
            timeoutMillis = 30_000,
        )

        val sessionId = UiTestSupport.container.labManager.activeSessionId!!
        composeRule.onNodeWithText("open - $sessionId").assertExists()

        // Behaviour B: fixtures come from the lab script, never from the expense table.
        tap(composeRule.onNodeWithText("Generate synthetic fixtures"))
        composeRule.waitUntilAtLeastOneExists(
            matcher = hasText(LabEventTypes.SYNTHETIC_GENERATED),
            timeoutMillis = 15_000,
        )

        val events = labEvents(sessionId)
        assertTrue("The session must record events", events.isNotEmpty())
        assertTrue("A lab payload leaked a real expense note", events.none { e ->
            e.payloadJson.contains(privateNote)
        })
        assertTrue(
            "Only the lab session id may be recorded",
            events.all { it.sessionId == sessionId },
        )
        composeRule.onNodeWithText(LabEventTypes.SESSION_START).assertExists()
        composeRule.onNodeWithText("LOCAL_ONLY").assertExists()

        tap(composeRule.onNodeWithText("Stop test session"))
        composeRule.waitUntilAtLeastOneExists(
            matcher = hasText(LabEventTypes.SESSION_END),
            timeoutMillis = 15_000,
        )
        composeRule.onNodeWithText("closed").assertExists()

        // The user's own expense is untouched by any of the above.
        composeRule.onNodeWithContentDescription("Dashboard").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(privateNote).assertIsDisplayed()
    }

    @Test
    fun pingingTheLabServerShowsAConnectionStatusInsteadOfThePlaceholder() {
        openSettings()
        enableLabModule()

        tap(composeRule.onNodeWithText("Ping"))
        composeRule.waitUntil(timeoutMillis = 30_000) {
            UiTestSupport.container.labManager.currentConnection() != null
        }
        composeRule.waitForIdle()

        val snapshot = UiTestSupport.container.labManager.currentConnection()!!
        composeRule.onNodeWithText("Server status").assertExists()
        composeRule.onNodeWithText("not checked yet").assertDoesNotExist()

        val expected = if (snapshot.reachable) "reachable" else "not reachable"
        assertTrue(
            "Server status should read \"$expected\" (${snapshot.detail})",
            composeRule.onAllNodes(hasText(expected, substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }

    @Test
    fun theLabSessionKeepsWorkingWhileTheServerIsUnavailable() {
        openSettings()
        enableLabModule()
        // No endpoint means no server: the session must stay usable and log the miss locally.
        runBlocking {
            UiTestSupport.container.labManager.updateConfig(
                UiTestSupport.container.labManager.config.copy(baseUrl = ""),
            )
        }

        tap(composeRule.onNodeWithText("Start test session"))
        composeRule.waitUntilAtLeastOneExists(
            matcher = hasText("offline mode", substring = true),
            timeoutMillis = 30_000,
        )
        val sessionId = UiTestSupport.container.labManager.activeSessionId!!

        tap(composeRule.onNodeWithText("Send synthetic batch to server"))
        composeRule.waitUntilAtLeastOneExists(
            matcher = hasText("Transfer skipped", substring = true),
            timeoutMillis = 30_000,
        )
        composeRule.waitUntilAtLeastOneExists(
            matcher = hasText("FAILED_NETWORK"),
            timeoutMillis = 15_000,
        )
        assertTrue(
            "The failed transfer must be recorded locally",
            labEvents(sessionId).any { it.eventType == LabEventTypes.TRANSFER_FAILURE },
        )

        // Nothing crashed and the session is still open, so the user can end it from this card.
        tap(composeRule.onNodeWithText("Stop test session"))
        composeRule.waitUntil(timeoutMillis = 15_000) {
            !UiTestSupport.container.labManager.isSessionActive()
        }
        composeRule.onNodeWithText("closed").assertExists()

        composeRule.onNodeWithContentDescription("Dashboard").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(privateNote).assertIsDisplayed()
    }

    @Test
    fun theEndpointFieldRefusesAnAddressThatIsNotPartOfTheLab() {
        openSettings()
        enableLabModule()

        val endpoint = composeRule.onNodeWithTag("endpoint_field")
        endpoint.performScrollTo()
        endpoint.performTextClearance()
        endpoint.performTextInput("ftp://10.0.2.2/lab")
        tap(composeRule.onNodeWithText("Save endpoint"))

        composeRule.onNodeWithText("Unsupported scheme 'ftp'").assertExists()
        composeRule.onNodeWithText("not checked yet").assertExists()

        endpoint.performTextClearance()
        endpoint.performTextInput("http://8.8.8.8/")
        tap(composeRule.onNodeWithText("Save endpoint"))
        composeRule.onNodeWithText("Cleartext http is only permitted for lab hosts", substring = true)
            .assertExists()
    }
}
