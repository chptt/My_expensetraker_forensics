package com.example.expensetracker.lab

import com.example.expensetracker.util.DateUtils
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Behaviour F. One JSON object per line, bounded by both a record cap and a byte cap, with a single
 * rotation file. These are ordinary app-private files: convenient for a forensic exercise, but they
 * are not tamper-proof and are not presented as such.
 */
@Serializable
data class ForensicEventRecord(
    val eventId: String,
    val sessionId: String,
    val eventType: String,
    val timestampUtc: String,
    val synthetic: Boolean,
    val requestStatus: String? = null,
    val detail: String? = null,
)

class ForensicEventLog(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idSupplier: () -> String = { java.util.UUID.randomUUID().toString() },
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private val lock = Any()

    val file: File get() = File(directory, FILE_NAME)

    fun append(
        sessionId: String,
        eventType: String,
        synthetic: Boolean,
        requestStatus: String? = null,
        detail: String? = null,
    ): ForensicEventRecord {
        val record = ForensicEventRecord(
            eventId = idSupplier(),
            sessionId = sessionId,
            eventType = eventType,
            timestampUtc = DateUtils.utcIso(clock()),
            synthetic = synthetic,
            requestStatus = requestStatus,
            detail = detail?.take(MAX_DETAIL_LENGTH),
        )
        synchronized(lock) {
            ensureDirectory()
            if (shouldRotate()) {
                rotate()
            }
            file.appendText(json.encodeToString(ForensicEventRecord.serializer(), record) + "\n")
            trimToCapacity()
        }
        return record
    }

    fun readAll(): List<ForensicEventRecord> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                runCatching { json.decodeFromString(ForensicEventRecord.serializer(), line) }.getOrNull()
            }
    }

    fun sizeBytes(): Long = if (file.exists()) file.length() else 0L

    fun clear() = synchronized(lock) {
        file.delete()
        rotatedFile().delete()
        Unit
    }

    private fun rotatedFile(): File = File(directory, ROTATED_FILE_NAME)

    private fun ensureDirectory() {
        if (!directory.exists()) directory.mkdirs()
    }

    private fun shouldRotate(): Boolean = file.exists() && file.length() > maxBytes

    private fun rotate() {
        val previous = rotatedFile()
        if (previous.exists()) previous.delete()
        file.renameTo(previous)
    }

    /** Keeps the active file within [maxRecords] lines; rotation alone cannot bound a burst. */
    private fun trimToCapacity() {
        if (!file.exists()) return
        val lines = file.readLines()
        if (lines.size <= maxRecords) return
        val kept = lines.takeLast(maxRecords)
        file.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
    }

    companion object {
        const val FILE_NAME = "lab_forensic_events.jsonl"
        const val ROTATED_FILE_NAME = "lab_forensic_events.prev.jsonl"
        const val DEFAULT_MAX_RECORDS = 500
        const val DEFAULT_MAX_BYTES = 256_000L
        const val MAX_DETAIL_LENGTH = 240
    }
}
