package com.example.expensetracker.lab

import android.content.SharedPreferences

/**
 * Remembers which device call-log record ids the server has already accepted, so an expense-triggered
 * sync sends only the entries that have not gone out before. Losing this set is safe: the worst case
 * is a duplicate batch, which the server de-duplicates by record id.
 *
 * Only device call-log ids are ever stored here - no other device identifiers and nothing else.
 */
interface SentRecordStore {
    fun sentIds(): Set<String>
    fun markSent(ids: Collection<String>)
    fun clear()
}

class InMemorySentRecordStore(seed: Collection<String> = emptyList()) : SentRecordStore {
    private val sent = seed.toMutableSet()

    override fun sentIds(): Set<String> = sent.toSet()

    override fun markSent(ids: Collection<String>) {
        sent += ids
    }

    override fun clear() = sent.clear()
}

class SharedPreferencesSentRecordStore(
    private val prefs: SharedPreferences,
) : SentRecordStore {

    override fun sentIds(): Set<String> =
        prefs.getString(KEY, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    override fun markSent(ids: Collection<String>) {
        if (ids.isEmpty()) return
        prefs.edit().putString(KEY, (sentIds() + ids).joinToString(",")).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "lab_sent_device_call_log_ids"
    }
}
