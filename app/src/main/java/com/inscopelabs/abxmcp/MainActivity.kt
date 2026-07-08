package com.inscopelabs.abxmcp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import com.inscopelabs.abxmcp.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  // Runtime permission request flow for POST_NOTIFICATIONS on API 33+
  private val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted: Boolean ->
    if (isGranted) {
      // Permission granted! Notifications will display normally.
    } else {
      // Permission denied. Handle gracefully: the service functions normally,
      // but status notifications will not be displayed to the user.
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Invoke permission request on API 33+ before notification display is expected
    if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU is 33
      if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
      }
    }
    
    val keyStoreManager = KeyStoreManager(applicationContext)

    setContent {
      MyApplicationTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          EnrollmentScreen(
            keyStoreManager = keyStoreManager,
            modifier = Modifier.padding(innerPadding)
          )
        }
      }
    }
  }
}

