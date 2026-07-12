package com.inscopelabs.abxmcp.boot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.inscopelabs.abxmcp.BuildConfig
import com.inscopelabs.abxmcp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecoveryActivity : ComponentActivity() {

    private companion object {
        const val TAG = "ABX_RECOVERY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_recovery)
            setupUI()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize RecoveryActivity UI safely", t)
            // Even if layout inflates with errors, do not crash.
            // Provide a bare minimal fallback or finish to avoid boot loops.
            try {
                Toast.makeText(this, "An error occurred displaying recovery UI.", Toast.LENGTH_LONG).show()
            } catch (ignored: Throwable) {}
            finish()
        }
    }

    private fun setupUI() {
        val tvStage: TextView = findViewById(R.id.tvStage)
        val tvMessage: TextView = findViewById(R.id.tvMessage)
        val tvMetadata: TextView = findViewById(R.id.tvMetadata)
        val tvStackTrace: TextView = findViewById(R.id.tvStackTrace)
        val btnCopy: Button = findViewById(R.id.btnCopy)
        val btnRetry: Button = findViewById(R.id.btnRetry)

        val failure = BootGuard.currentFailure(applicationContext)

        val stageText = failure?.stage ?: getString(R.string.recovery_unknown_stage)
        val messageText = failure?.message ?: getString(R.string.recovery_unknown_message)
        val stackText = failure?.stackTrace ?: ""

        val timestampStr = failure?.timestamp ?: System.currentTimeMillis().toString()
        val dateStr = try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            format.format(Date(timestampStr.toLong()))
        } catch (e: Exception) {
            timestampStr
        }

        val appVersionName = BuildConfig.VERSION_NAME
        val appVersionCode = BuildConfig.VERSION_CODE
        val buildType = BuildConfig.BUILD_TYPE

        val metadataText = """
            App Version: $appVersionName ($appVersionCode)
            Build Variant: $buildType
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
            Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Timestamp: $dateStr
        """.trimIndent()

        tvStage.text = stageText
        tvMessage.text = messageText
        tvMetadata.text = metadataText
        tvStackTrace.text = stackText

        btnCopy.setOnClickListener {
            try {
                val reportText = """
                    === ABC SERVER STARTUP FAILURE REPORT ===
                    Stage: $stageText
                    Message: $messageText
                    
                    === Device Metadata ===
                    $metadataText
                    
                    === Stack Trace ===
                    $stackText
                """.trimIndent()

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ABC Server Diagnostic Report", reportText)
                clipboard.setPrimaryClip(clip)

                Toast.makeText(this, R.string.recovery_copied, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to copy diagnostic report", t)
                Toast.makeText(this, "Failed to copy report", Toast.LENGTH_SHORT).show()
            }
        }

        btnRetry.setOnClickListener {
            try {
                BootGuard.clear(applicationContext)
                val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) {
                    startActivity(intent)
                }
                finishAffinity()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to restart application", t)
                Toast.makeText(this, "Failed to restart application", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
