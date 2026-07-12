package com.inscopelabs.abxmcp.boot

import android.content.Context
import android.content.Intent
import android.text.ClipboardManager
import android.widget.Button
import android.widget.TextView
import com.inscopelabs.abxmcp.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecoveryActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        BootGuard.clear(context)
    }

    @Test
    fun testUIRenderingWithFailure() {
        val testException = RuntimeException("Render check")
        BootGuard.recordFailure(context, "RENDER_STAGE", testException)

        val controller = Robolectric.buildActivity(RecoveryActivity::class.java)
        controller.create()
        val activity = controller.get()

        val tvStage: TextView = activity.findViewById(R.id.tvStage)
        val tvMessage: TextView = activity.findViewById(R.id.tvMessage)
        val tvStackTrace: TextView = activity.findViewById(R.id.tvStackTrace)
        val tvMetadata: TextView = activity.findViewById(R.id.tvMetadata)

        assertEquals("RENDER_STAGE", tvStage.text.toString())
        assertEquals("Render check", tvMessage.text.toString())
        assertTrue(tvStackTrace.text.toString().contains("Render check"))
        assertTrue(tvMetadata.text.toString().contains("App Version"))
    }

    @Test
    fun testCopyReportWritesToClipboard() {
        val testException = RuntimeException("Copy check")
        BootGuard.recordFailure(context, "COPY_STAGE", testException)

        val controller = Robolectric.buildActivity(RecoveryActivity::class.java).create()
        val activity = controller.get()

        val btnCopy: Button = activity.findViewById(R.id.btnCopy)
        btnCopy.performClick()

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipData = clipboard.primaryClip
        assertNotNull(clipData)
        assertTrue(clipData!!.itemCount > 0)
        val text = clipData.getItemAt(0).text.toString()
        assertTrue(text.contains("COPY_STAGE"))
        assertTrue(text.contains("Copy check"))
        assertTrue(text.contains("Device Metadata"))
    }

    @Test
    fun testRestartAppClearsFailureAndLaunchesIntent() {
        val testException = RuntimeException("Restart check")
        BootGuard.recordFailure(context, "RESTART_STAGE", testException)
        assertTrue(BootGuard.hasFailure(context))

        val controller = Robolectric.buildActivity(RecoveryActivity::class.java).create()
        val activity = controller.get()

        val btnRetry: Button = activity.findViewById(R.id.btnRetry)
        btnRetry.performClick()

        // BootGuard should be cleared
        assertFalse(BootGuard.hasFailure(context))

        // Activity should be finishing
        assertTrue(activity.isFinishing)

        // Relaunch intent should be sent
        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull(nextIntent)
        val expectedFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        assertEquals(expectedFlags, nextIntent.flags and expectedFlags)
    }
}
