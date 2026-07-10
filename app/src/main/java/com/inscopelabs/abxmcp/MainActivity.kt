package com.inscopelabs.abxmcp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import com.inscopelabs.abxmcp.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private var sharedTextState by mutableStateOf<String?>(null)

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
    
    // Wire AuditLog on app startup before any session or policy engine logic executes
    com.inscopelabs.abxmcp.core.audit.AuditLog.initialize(applicationContext, keyStoreManager)

    handleIntent(intent)

    setContent {
      MyApplicationTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          EnrollmentScreen(
            keyStoreManager = keyStoreManager,
            sharedText = sharedTextState,
            onClearSharedText = { sharedTextState = null },
            modifier = Modifier.padding(innerPadding)
          )
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent?) {
    if (intent != null && intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
      val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
      if (!sharedText.isNullOrBlank()) {
        sharedTextState = sharedText
      }
    }
  }
}

