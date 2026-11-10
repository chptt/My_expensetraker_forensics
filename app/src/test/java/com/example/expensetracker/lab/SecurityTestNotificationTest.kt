package com.example.expensetracker.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityTestNotificationTest {

    private val at = 1_760_000_000_000L

    @Test
    fun `a delivered outcome uses the delivered headline and lab metadata`() {
        val outcome = LabActionOutcome(
            success = true,
            summary = "Security test delivered: HTTP 202, fixture syn-fixture-demo-001, session lab-1",
            sessionId = "lab-1",
            httpStatus = 202,
        )

        val content = SecurityTestNotification.contentFor(outcome, at)

        assertEquals("DailySpend security lab: test delivered", content.title)
        assertTrue(content.message.contains("Security test delivered"))
        assertTrue(content.message.contains("fixture=syn-fixture-demo-001"))
        assertTrue(content.message.contains("http=202"))
        assertTrue(content.message.contains("synthetic=true"))
    }

    @Test
    fun `a failed outcome uses the failure headline and marks a missing status`() {
        val outcome = LabActionOutcome(
            success = false,
            summary = "Security test failed: ConnectException: refused",
            sessionId = "lab-1",
        )

        val content = SecurityTestNotification.contentFor(outcome, at)

        assertEquals("DailySpend security lab: test failed", content.title)
        assertTrue(content.message.contains("ConnectException: refused"))
        assertTrue(content.message.contains("http=n/a"))
    }

    @Test
    fun `every notification uses the one lab channel and id`() {
        val success = SecurityTestNotification.contentFor(
            LabActionOutcome(true, "ok", "lab-1", 200),
            at,
        )
        val failure = SecurityTestNotification.contentFor(
            LabActionOutcome(false, "nope", "lab-1", 503),
            at,
        )

        assertEquals("daily_spend_security_lab", success.channelId)
        assertEquals(success.channelId, failure.channelId)
        assertEquals(SecurityTestNotification.NOTIFICATION_ID, success.notificationId)
        assertEquals(failure.notificationId, success.notificationId)
        assertEquals(740_001, SecurityTestNotification.NOTIFICATION_ID)
    }

    @Test
    fun `the timestamp stored with the notification is utc`() {
        val content = SecurityTestNotification.contentFor(LabActionOutcome(true, "ok", "lab-1", 200), at)

        assertTrue(content.generatedAtUtc, content.generatedAtUtc.endsWith("Z"))
        assertTrue(content.generatedAtUtc.startsWith("2025-10-09T"))
    }

    @Test
    fun `the no op notifier never claims a notification was shown`() {
        val posted = LabResultNotifier.NoOp.post("title", "message")

        assertTrue(!posted)
    }
}
