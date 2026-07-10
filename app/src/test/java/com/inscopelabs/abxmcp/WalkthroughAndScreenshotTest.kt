package com.inscopelabs.abxmcp

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.inscopelabs.abxmcp.core.audit.AuditLog
import com.inscopelabs.abxmcp.core.audit.ReasonCode
import com.inscopelabs.abxmcp.core.keystore.KeyStoreEnvironment
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import com.inscopelabs.abxmcp.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class WalkthroughAndScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var keyStoreManager: KeyStoreManager

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        context = ApplicationProvider.getApplicationContext()
        com.inscopelabs.abxmcp.core.session.SessionManagerProvider.setForTesting(null)
        keyStoreManager = KeyStoreManager(context, KeyStoreEnvironment.TEST_FALLBACK)
        AuditLog.initialize(context, keyStoreManager)
        AuditLog.clear()
    }

    private fun renderAndCapture(tabIndex: Int, isDark: Boolean, isSessionActive: Boolean, fileName: String) {
        // Reset and load key for pristine screenshot layout
        AuditLog.clear()
        
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = isDark, dynamicColor = false) {
                EnrollmentScreen(keyStoreManager = keyStoreManager)
            }
        }
        
        composeTestRule.waitForIdle()
        
        // Locate bottom navigation tab and select it
        val tabTag = when(tabIndex) {
            0 -> "nav_tab_connect"
            1 -> "nav_tab_access"
            2 -> "nav_tab_activity"
            3 -> "nav_tab_remove"
            else -> "nav_tab_connect"
        }
        
        composeTestRule.onNodeWithTag(tabTag).performClick()
        composeTestRule.waitForIdle()
        
        // Handle starting session if requested for active screen states
        if (tabIndex == 1 && isSessionActive) {
            val startBtn = composeTestRule.onNodeWithTag("start_session_button")
            try {
                startBtn.assertExists()
                startBtn.performClick()
                composeTestRule.waitForIdle()
            } catch (e: AssertionError) {
                // Ignore if already active
            }
        }
        
        // If Activity screen, add some simulated events to make the screenshot look realistic
        if (tabIndex == 2) {
            val expiredBtn = composeTestRule.onNodeWithTag("sim_btn_expired")
            try {
                expiredBtn.assertExists()
                expiredBtn.performClick()
                composeTestRule.waitForIdle()
            } catch (e: AssertionError) {}
        }
        
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/$fileName")
    }

    // ==========================================
    // ROBORAZZI SCREENSHOT TESTS
    // ==========================================

    @Test
    fun screenshot_01_Connect_Light() {
        renderAndCapture(tabIndex = 0, isDark = false, isSessionActive = false, fileName = "connect_screen_light.png")
    }

    @Test
    fun screenshot_01_Connect_Dark() {
        renderAndCapture(tabIndex = 0, isDark = true, isSessionActive = false, fileName = "connect_screen_dark.png")
    }

    @Test
    fun screenshot_02_Access_Inactive_Light() {
        renderAndCapture(tabIndex = 1, isDark = false, isSessionActive = false, fileName = "access_screen_inactive_light.png")
    }

    @Test
    fun screenshot_02_Access_Inactive_Dark() {
        renderAndCapture(tabIndex = 1, isDark = true, isSessionActive = false, fileName = "access_screen_inactive_dark.png")
    }

    @Test
    fun screenshot_02_Access_Active_Light() {
        renderAndCapture(tabIndex = 1, isDark = false, isSessionActive = true, fileName = "access_screen_active_light.png")
    }

    @Test
    fun screenshot_02_Access_Active_Dark() {
        renderAndCapture(tabIndex = 1, isDark = true, isSessionActive = true, fileName = "access_screen_active_dark.png")
    }

    @Test
    fun screenshot_03_Activity_Light() {
        renderAndCapture(tabIndex = 2, isDark = false, isSessionActive = false, fileName = "activity_screen_light.png")
    }

    @Test
    fun screenshot_03_Activity_Dark() {
        renderAndCapture(tabIndex = 2, isDark = true, isSessionActive = false, fileName = "activity_screen_dark.png")
    }

    @Test
    fun screenshot_04_Remove_Light() {
        renderAndCapture(tabIndex = 3, isDark = false, isSessionActive = false, fileName = "remove_screen_light.png")
    }

    @Test
    fun screenshot_04_Remove_Dark() {
        renderAndCapture(tabIndex = 3, isDark = true, isSessionActive = false, fileName = "remove_screen_dark.png")
    }

    // ==========================================
    // FUNCTIONAL & CLICK TESTS (Espresso equivalent)
    // ==========================================

    @Test
    fun testTabNavigationAndClicks() {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                EnrollmentScreen(keyStoreManager = keyStoreManager)
            }
        }
        composeTestRule.waitForIdle()

        // 1. Initially we are on Connect tab (index 0)
        composeTestRule.onNodeWithTag("fingerprint_card").assertIsDisplayed()

        // 2. Click Access Tab
        composeTestRule.onNodeWithTag("nav_tab_access").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("session_control_card").assertIsDisplayed()

        // 3. Click Activity Tab
        composeTestRule.onNodeWithTag("nav_tab_activity").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("integrity_banner").assertIsDisplayed()

        // 4. Click Remove Tab
        composeTestRule.onNodeWithTag("nav_tab_remove").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("uninstall_card").assertIsDisplayed()
    }

    @Test
    fun testTtlCountdownDisplay() {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                EnrollmentScreen(keyStoreManager = keyStoreManager)
            }
        }
        composeTestRule.waitForIdle()

        // Go to Access Screen
        composeTestRule.onNodeWithTag("nav_tab_access").performClick()
        composeTestRule.waitForIdle()

        // Initially inactive state
        composeTestRule.onNodeWithTag("countdown_text").assertTextEquals("Session Inactive")
        composeTestRule.onNodeWithTag("start_session_button").assertIsDisplayed()

        // Start session
        composeTestRule.onNodeWithTag("start_session_button").performClick()
        composeTestRule.waitForIdle()

        // Now session active
        composeTestRule.onNodeWithTag("stop_session_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Time Remaining: 300 seconds").assertIsDisplayed()

        // Stop session
        composeTestRule.onNodeWithTag("stop_session_button").performClick()
        composeTestRule.waitForIdle()

        // Reverts to inactive
        composeTestRule.onNodeWithTag("countdown_text").assertTextEquals("Session Inactive")
        composeTestRule.onNodeWithTag("start_session_button").assertIsDisplayed()
    }

    // ==========================================
    // ACCESSIBILITY & CONTRAST LEVEL CHECKS
    // ==========================================

    @Test
    fun testAccessibilityContentDescriptions() {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                EnrollmentScreen(keyStoreManager = keyStoreManager)
            }
        }
        composeTestRule.waitForIdle()

        // Verify that critical interactive elements have content descriptions
        composeTestRule.onNodeWithTag("top_bar_about_button").assertContentDescriptionContains("About & Privacy Policy")
        composeTestRule.onNodeWithTag("copy_fingerprint_button").assertContentDescriptionContains("Copy fingerprint")
        composeTestRule.onNodeWithTag("qr_image").assertContentDescriptionContains("Device Public Key Fingerprint QR Code for Server Enrollment")
    }

    // ==========================================
    // NON-TECHNICAL WALKTHROUGH COMPLIANCE
    // ==========================================

    @Test
    fun testNonTechnicalWalkthrough_SimulatesCompleteLifecycle() {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                EnrollmentScreen(keyStoreManager = keyStoreManager)
            }
        }
        composeTestRule.waitForIdle()

        try {
            // 1. Initial State: Unpaired, device identity active but not enrolled with a gateway
            composeTestRule.onNodeWithText("Not paired with any gateway").assertIsDisplayed()

            // 2. Simulation Gateway Enrollment Dialogue Trigger
            composeTestRule.onNodeWithTag("simulate_enrollment_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            // Type a mock server URL and enroll
            composeTestRule.onNodeWithTag("pairing_input_field").performClick()
            composeTestRule.onNodeWithTag("pairing_input_field").performTextReplacement("https://abc-gateway.local/enroll")
            composeTestRule.onNodeWithTag("confirm_pairing_button").performClick()
            composeTestRule.waitForIdle()

            // Confirm updated pairing banner
            composeTestRule.onNodeWithText("Paired with Gateway: https://abc-gateway.local/enroll").assertIsDisplayed()

            // 3. Navigate to Access Screen and activate session
            composeTestRule.onNodeWithTag("nav_tab_access").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("start_session_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Time Remaining: 300 seconds").assertIsDisplayed()

            // 4. Navigate to Activity Screen and verify audit ledger logs violations
            composeTestRule.onNodeWithTag("nav_tab_activity").performClick()
            composeTestRule.waitForIdle()

            // Check clean log state initially
            composeTestRule.onNodeWithText("No security block events or rejections recorded yet.").assertIsDisplayed()

            // Trigger two simulated block events
            composeTestRule.onNodeWithTag("sim_btn_expired").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("sim_btn_bounds").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            // Confirm log items are visible with plain-language translations
            composeTestRule.onNodeWithText("Blocked: session had ended").assertIsDisplayed()
            composeTestRule.onNodeWithText("Blocked: access outside of authorized folders").assertIsDisplayed()

            // 5. Navigate to Remove screen and execute compliance wipe
            composeTestRule.onNodeWithTag("nav_tab_remove").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("wipe_data_compliance_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            // 6. Return to Connect screen and verify reset to original unpaired, clean state
            composeTestRule.onNodeWithTag("nav_tab_connect").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Not paired with any gateway").assertIsDisplayed()
        } catch (e: Throwable) {
            println("=== WALKTHROUGH TEST FAIL SEMANTICS TREE ===")
            try {
                composeTestRule.onRoot(useUnmergedTree = true).printToLog("WALKTHROUGH_DEBUG")
            } catch (ex: Exception) {
                println("Failed to print tree: ${ex.message}")
            }
            throw e
        }
    }
}
