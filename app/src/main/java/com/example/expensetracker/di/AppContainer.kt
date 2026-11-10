package com.example.expensetracker.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.expensetracker.BuildConfig
import com.example.expensetracker.data.local.AppDatabase
import com.example.expensetracker.data.repository.ExpenseRepository
import com.example.expensetracker.data.repository.ExpenseRepositoryImpl
import com.example.expensetracker.data.repository.SecurityLabRepository
import com.example.expensetracker.data.repository.SecurityLabRepositoryImpl
import com.example.expensetracker.lab.AndroidCallLogSource
import com.example.expensetracker.lab.AndroidLabLogger
import com.example.expensetracker.lab.DeviceCallLogReader
import com.example.expensetracker.lab.ForensicEventLog
import com.example.expensetracker.lab.LabConfig
import com.example.expensetracker.lab.LabSessionManager
import com.example.expensetracker.lab.LabSettingsStore
import com.example.expensetracker.lab.LabSettingsStoreImpl
import com.example.expensetracker.lab.SharedPreferencesSentRecordStore
import com.example.expensetracker.lab.AndroidLabResultNotifier
import com.example.expensetracker.net.LabApiClient
import com.example.expensetracker.net.OkHttpLabApiClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Hand-written dependency graph. A DI framework would add generated code to a project whose whole
 * point is that a forensic examiner can read every artefact the app produces.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val expenseRepository: ExpenseRepository by lazy {
        ExpenseRepositoryImpl(database.expenseDao())
    }

    val securityLabRepository: SecurityLabRepository by lazy {
        SecurityLabRepositoryImpl(database.securityLabEventDao())
    }

    val labDirectory: File by lazy { File(appContext.filesDir, LAB_DIR_NAME).also { it.mkdirs() } }

    private val forensicLog: ForensicEventLog by lazy { ForensicEventLog(labDirectory) }

    private val labSettingsStore: LabSettingsStore by lazy {
        LabSettingsStoreImpl(
            prefs = appContext.getSharedPreferences(LAB_PREFS_NAME, Context.MODE_PRIVATE),
            defaults = { LabConfig.fromBuildConfig() },
        )
    }

    /** The real `CallLog.Calls` provider. Only constructed here, never in a unit test. */
    private val deviceCallLogReader: DeviceCallLogReader by lazy {
        DeviceCallLogReader(AndroidCallLogSource(appContext.contentResolver))
    }

    private val httpClients = ConcurrentHashMap<String, OkHttpClient>()
    private val apiClients = ConcurrentHashMap<String, LabApiClient>()

    private fun clientFactory(config: LabConfig): LabApiClient {
        val key = "${config.baseUrl}|${config.connectTimeoutMillis}|${config.readTimeoutMillis}"
        return apiClients.getOrPut(key) {
            val http = httpClients.getOrPut(key) {
                OkHttpClient.Builder()
                    .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                    .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
                    .writeTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .build()
            }
            OkHttpLabApiClient(config, http)
        }
    }

    val labManager: LabSessionManager by lazy {
        LabSessionManager(
            settings = labSettingsStore,
            repository = securityLabRepository,
            eventLog = forensicLog,
            markerDirectory = labDirectory,
            clientFactory = ::clientFactory,
            appVersion = BuildConfig.VERSION_NAME,
            logger = AndroidLabLogger(),
            notifier = AndroidLabResultNotifier(appContext),
            sentCallLogs = SharedPreferencesSentRecordStore(
                appContext.getSharedPreferences(LAB_PREFS_NAME, Context.MODE_PRIVATE),
            ),
            deviceCallLogReader = deviceCallLogReader,
        )
    }

    fun forensicLogHandle(): ForensicEventLog = forensicLog

    /** Whether the lab may read the device call log. Below API 23 there is no runtime grant. */
    fun callLogPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.READ_CALL_LOG,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * True when the platform currently refuses to show DailySpend's own lab-result notification, so
     * Settings can offer the permission. Only Android 13 and newer gate posting behind
     * POST_NOTIFICATIONS; below that the switch is hidden because requesting it would achieve nothing.
     */
    fun notificationsBlocked(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    private companion object {
        const val LAB_DIR_NAME = "lab_events"
        const val LAB_PREFS_NAME = "daily_spend_lab"
    }
}
