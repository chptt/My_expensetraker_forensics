package com.example.expensetracker.lab

import com.example.expensetracker.util.DateUtils

/**
 * Delivers the result of a security test as a notification owned by this app. The manager only ever
 * sees this interface, so lab orchestration stays free of Android types and remains unit-testable.
 */
fun interface LabResultNotifier {
    /** Returns true when the operating system accepted the notification for display. */
    fun post(title: String, message: String): Boolean

    companion object {
        val NoOp = LabResultNotifier { _, _ -> false }
    }
}

data class SecurityTestNotificationContent(
    val title: String,
    val message: String,
    val channelId: String,
    val notificationId: Int,
    val generatedAtUtc: String,
)

/**
 * Builds the text for the app's *own* result notification. It carries lab metadata only - fixture
 * id, session outcome, HTTP status - and never reads notifications posted by other applications.
 */
object SecurityTestNotification {
    const val CHANNEL_ID = "daily_spend_security_lab"
    const val CHANNEL_NAME = "Security lab results"
    const val NOTIFICATION_ID = 740_001

    private const val TITLE_SUCCESS = "DailySpend security lab: test delivered"
    private const val TITLE_FAILURE = "DailySpend security lab: test failed"

    fun contentFor(outcome: LabActionOutcome, atUtcMillis: Long): SecurityTestNotificationContent =
        SecurityTestNotificationContent(
            title = if (outcome.success) TITLE_SUCCESS else TITLE_FAILURE,
            message = "${outcome.summary} | fixture=${SyntheticExpenseFactory.SECURITY_TEST_FIXTURE_ID} " +
                "http=${outcome.httpStatus ?: "n/a"} synthetic=true",
            channelId = CHANNEL_ID,
            notificationId = NOTIFICATION_ID,
            generatedAtUtc = DateUtils.utcIso(atUtcMillis),
        )
}
