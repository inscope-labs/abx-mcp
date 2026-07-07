package com.inscopelabs.abxmcp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import com.inscopelabs.abxmcp.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    
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

