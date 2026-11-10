package com.example.expensetracker.lab

import android.app.NotificationManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.expensetracker.data.model.SecurityLabEvent
import com.example.expensetracker.testing.UiTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the *Run Security Test* path on a real device: the OkHttp client, the platform notification
 * manager, the Room table and the JSONL log. It needs no Compose automation, so it still reports
 * useful results whatever the lab endpoint answers - including a 404 from a server that has not
 * implemented the documented routes yet.
 */
@RunWith(AndroidJUnit4::class)
class SecurityTestNotificationDeviceTest {

    private val context by lazy { InstrumentationRegistry.getInstrumentation().targetContext }

    @Before
    fun setUp() {
        UiTestSupport.resetState()
        context.getSystemService(NotificationManager::class.java)
            .cancelAll()
    }

    @Test
    fun theSecurityTestArchivesItsOwnNotificationAndMatchesWhatTheTrayShows() {
        val manager = UiTestSupport.container.labManager
        runBlocking {
            manager.updateConfig(
                LabConfig.fromBuildConfig().copy(labEnabled = true, baseUrl = GENYMOTION_HOST),
            )
        }

        val outcome = runBlocking { manager.runSecurityTest() }
        val session = outcome.sessionId
        assertTrue("no session for ${outcome.summary}", session != null)

        val events: List<SecurityLabEvent> =
            runBlocking { UiTestSupport.container.securityLabRepository.eventsFor(session!!) }
        val archived = events.filter { it.eventType == LabEventTypes.SECURITY_TEST_NOTIFICATION }

        assertEquals(1, archived.size)
        val payload = archived.single().payloadJson
        assertEquals(LabDeliveryStatus.LOCAL_ONLY, archived.single().deliveryStatus)
        assertTrue(payload, payload.contains("\"title\":\"DailySpend security lab"))
        assertTrue(payload, payload.contains("\"channelId\":\"$CHANNEL\""))
        assertTrue(payload, payload.contains(SyntheticExpenseFactory.SECURITY_TEST_FIXTURE_ID))
        assertTrue(payload, payload.contains("\"ownAppOnly\":true"))
        assertTrue(payload, payload.contains("\"synthetic\":true"))
        // The transfer trail and its notification belong to the same session.
        assertTrue(events.any { it.eventType == LabEventTypes.TRANSFER_REQUEST })

        val shownInTray = context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .any { it.id == SecurityTestNotification.NOTIFICATION_ID && it.packageName == context.packageName }
        val archivedAsPosted = payload.contains("\"posted\":true")
        assertEquals(
            "the archived notification must not claim more than the tray actually shows",
            shownInTray,
            archivedAsPosted,
        )

        val logRecords = UiTestSupport.container.forensicLogHandle().readAll()
        assertTrue(
            logRecords.any { it.eventType == LabEventTypes.SECURITY_TEST_NOTIFICATION && it.synthetic },
        )
        assertEquals(events.size, logRecords.count { it.sessionId == session })
    }

    @Test
    fun theAppAsksForNotificationPermissionButDeclaresNoListenerService() {
        val packageManager = context.packageManager
        val info = packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val declared = info.requestedPermissionsList()

        assertTrue("declared=$declared", declared.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue("declared=$declared", declared.contains("android.permission.INTERNET"))
        assertFalse(
            "a notification listener would read other applications' notifications",
            declared.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
        )

        val services = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            .services
        assertTrue(
            "the lab module must not declare any service: ${services?.map { it.name }}",
            (services?.size ?: 0) == 0,
        )
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.requestedPermissionsList(): List<String> =
        requestedPermissions?.toList() ?: emptyList()

    private companion object {
        const val GENYMOTION_HOST = "http://10.0.3.2:5000/"
        const val CHANNEL = "daily_spend_security_lab"
    }
}
