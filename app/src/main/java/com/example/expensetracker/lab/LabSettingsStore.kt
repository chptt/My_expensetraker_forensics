package com.example.expensetracker.lab

/** Persists the handful of lab switches the Settings screen exposes. Nothing user-entered about
 * expenses is stored here. */
interface LabSettingsStore {
    fun load(): LabConfig
    fun save(config: LabConfig)
}

class LabSettingsStoreImpl(
    private val prefs: android.content.SharedPreferences,
    private val defaults: () -> LabConfig,
) : LabSettingsStore {

    override fun load(): LabConfig {
        val fallback = defaults()
        return LabConfig(
            labEnabled = prefs.getBoolean(KEY_ENABLED, fallback.labEnabled),
            baseUrl = prefs.getString(KEY_BASE_URL, fallback.baseUrl).orEmpty(),
            connectTimeoutMillis = prefs.getLong(KEY_CONNECT_TIMEOUT, fallback.connectTimeoutMillis),
            readTimeoutMillis = prefs.getLong(KEY_READ_TIMEOUT, fallback.readTimeoutMillis),
            retryEnabled = prefs.getBoolean(KEY_RETRY, fallback.retryEnabled),
            maxRetries = prefs.getInt(KEY_MAX_RETRIES, fallback.maxRetries)
                .coerceIn(1, LabConfig.MAX_RETRIES_LIMIT),
            allowedCleartextHosts = fallback.allowedCleartextHosts,
        )
    }

    override fun save(config: LabConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.labEnabled)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putLong(KEY_CONNECT_TIMEOUT, config.connectTimeoutMillis)
            .putLong(KEY_READ_TIMEOUT, config.readTimeoutMillis)
            .putBoolean(KEY_RETRY, config.retryEnabled)
            .putInt(KEY_MAX_RETRIES, config.maxRetries)
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "lab_enabled"
        const val KEY_BASE_URL = "lab_base_url"
        const val KEY_CONNECT_TIMEOUT = "lab_connect_timeout"
        const val KEY_READ_TIMEOUT = "lab_read_timeout"
        const val KEY_RETRY = "lab_retry_enabled"
        const val KEY_MAX_RETRIES = "lab_max_retries"
    }
}
