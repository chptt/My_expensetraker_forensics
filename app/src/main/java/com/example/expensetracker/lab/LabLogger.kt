package com.example.expensetracker.lab

import android.util.Log

/** Single Logcat surface for the lab module so `adb logcat -s DailySpendLab` captures the run. */
interface LabLogger {
    fun info(eventType: String, sessionId: String, detail: String)
    fun warn(eventType: String, sessionId: String, detail: String)
}

class AndroidLabLogger : LabLogger {
    override fun info(eventType: String, sessionId: String, detail: String) {
        Log.i(TAG, "$eventType session=$sessionId $detail")
    }

    override fun warn(eventType: String, sessionId: String, detail: String) {
        Log.w(TAG, "$eventType session=$sessionId $detail")
    }

    private companion object {
        const val TAG = "DailySpendLab"
    }
}

/** Captures log lines in memory; used by unit tests to assert on the Logcat surface. */
class RecordingLabLogger : LabLogger {
    val lines: MutableList<String> = mutableListOf()

    override fun info(eventType: String, sessionId: String, detail: String) {
        lines += "I/$eventType session=$sessionId $detail"
    }

    override fun warn(eventType: String, sessionId: String, detail: String) {
        lines += "W/$eventType session=$sessionId $detail"
    }
}
