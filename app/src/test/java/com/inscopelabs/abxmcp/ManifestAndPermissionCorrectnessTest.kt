package com.inscopelabs.abxmcp

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ManifestAndPermissionCorrectnessTest {

    @Test
    @Config(sdk = [33])
    fun testInternetAndPostNotificationsPermissionsAreInMergedManifest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requestedPermissions = packageInfo.requestedPermissions ?: emptyArray()
        
        val hasInternet = requestedPermissions.contains(android.Manifest.permission.INTERNET)
        val hasPostNotifications = requestedPermissions.contains(android.Manifest.permission.POST_NOTIFICATIONS)
        
        assertTrue("INTERNET permission must be declared in the final merged manifest", hasInternet)
        assertTrue("POST_NOTIFICATIONS permission must be declared in the final merged manifest", hasPostNotifications)
    }

    @Test
    @Config(sdk = [33])
    fun testPostNotificationsRequestedOnApi33Plus() {
        // Start MainActivity on API 33 (Tiramisu)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        
        // At this stage, MainActivity is created and should trigger POST_NOTIFICATIONS request
        controller.create()
        
        val shadowActivity = Shadows.shadowOf(activity)
        val lastPermissionRequest = shadowActivity.lastRequestedPermission
        
        assertNotNull("Should have requested some permission on API 33+", lastPermissionRequest)
        val hasPostNotificationsRequest = lastPermissionRequest?.requestedPermissions?.contains(
            android.Manifest.permission.POST_NOTIFICATIONS
        ) ?: false
        assertTrue("Should request POST_NOTIFICATIONS permission on API 33+", hasPostNotificationsRequest)
    }

    @Test
    @Config(sdk = [31])
    fun testPostNotificationsNotRequestedOnApi32OrBelow() {
        // Start MainActivity on API 31 (S)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        
        controller.create()
        
        val shadowActivity = Shadows.shadowOf(activity)
        val lastPermissionRequest = shadowActivity.lastRequestedPermission
        
        // Should not request POST_NOTIFICATIONS on API < 33
        if (lastPermissionRequest != null) {
            val hasPostNotificationsRequest = lastPermissionRequest.requestedPermissions.contains(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
            assertFalse("Should NOT request POST_NOTIFICATIONS permission on API < 33", hasPostNotificationsRequest)
        }
    }
}
