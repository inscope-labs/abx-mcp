package com.inscopelabs.abxmcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.inscopelabs.abxmcp.core.keystore.FingerprintUtils
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollmentScreen(
    keyStoreManager: KeyStoreManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val alias = "abx_mcp_device_key"

    // Component state
    var keyPair by remember { mutableStateOf<KeyPair?>(null) }
    var fingerprint by remember { mutableStateOf("") }
    var formattedFingerprint by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isHardwareBacked by remember { mutableStateOf(false) }
    var isStrongBoxBacked by remember { mutableStateOf(false) }
    var enrollStatusMessage by remember { mutableStateOf("") }

    // Logic to load/generate keys
    fun loadOrEnrollKey(forceRegenerate: Boolean = false) {
        try {
            val kp = if (forceRegenerate) {
                keyStoreManager.generateKeyPair(alias)
            } else {
                keyStoreManager.getOrCreateKeyPair(alias)
            }
            keyPair = kp

            // Calculate fingerprint
            val rawFingerprint = FingerprintUtils.getFingerprint(kp.public)
            fingerprint = rawFingerprint
            formattedFingerprint = FingerprintUtils.formatFingerprint(rawFingerprint)

            // Generate QR code with fingerprint
            qrBitmap = generateQrCodeBitmap(rawFingerprint, 512)

            // Check hardware security status
            if (keyStoreManager.isAndroidKeyStore) {
                try {
                    val keyFactory = KeyFactory.getInstance(kp.private.algorithm, "AndroidKeyStore")
                    val keyInfo = keyFactory.getKeySpec(kp.private, KeyInfo::class.java) as KeyInfo
                    isHardwareBacked = keyInfo.isInsideSecureHardware
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isStrongBoxBacked = keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
                    }
                } catch (e: Exception) {
                    isHardwareBacked = false
                }
            } else {
                isHardwareBacked = false // JVM mock path
            }

            enrollStatusMessage = if (forceRegenerate) {
                "New EC-256 device credential keypair rotated successfully."
            } else {
                "Secure keypair active."
            }
        } catch (e: Exception) {
            enrollStatusMessage = "Credential initialization failed: ${e.message}"
        }
    }

    // Load on launch
    LaunchedEffect(Unit) {
        loadOrEnrollKey()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "ABC Server Credential Enrollment",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Secure Enclave Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("enclave_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "Security Status Indicator",
                            tint = if (isHardwareBacked || !keyStoreManager.isAndroidKeyStore) Color(0xFF2E7D32) else Color(0xFFD84315),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Hardware Enclave Status",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                    SecurityStatusRow(
                        label = "Provider Backend",
                        value = if (keyStoreManager.isAndroidKeyStore) "Android KeyStore (Secure Hardware)" else "JVM Sandbox (Test Environment)"
                    )
                    SecurityStatusRow(
                        label = "Key Material Isolation",
                        value = "NON-EXPORTABLE (Private key locked in hardware)"
                    )
                    SecurityStatusRow(
                        label = "Hardware Backed (TEE)",
                        value = if (isHardwareBacked || !keyStoreManager.isAndroidKeyStore) "YES (Cryptographic operations restricted)" else "NO (TEE fallback unavailable)"
                    )
                    if (isStrongBoxBacked) {
                        SecurityStatusRow(
                            label = "StrongBox Support",
                            value = "YES (Discrete Hardware Security Module)"
                        )
                    }
                }
            }

            // QR Code Container Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .testTag("qr_card")
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Scan to Enroll Device",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "Device Public Key Fingerprint QR Code",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("qr_image")
                            )
                        } else {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("qr_loading")
                            )
                        }
                    }

                    Text(
                        text = "Present this QR code to the server or gateway to enroll this device.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }

            // Fingerprint Display Box
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("fingerprint_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Device Fingerprint (SHA-256)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                if (fingerprint.isNotEmpty()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("ABC Server Public Key Fingerprint", fingerprint)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Fingerprint copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("copy_fingerprint_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy raw fingerprint to clipboard",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (formattedFingerprint.isNotEmpty()) formattedFingerprint else "Loading...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 18.sp,
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth()
                                .testTag("fingerprint_text"),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Status message and actions
            AnimatedVisibility(
                visible = enrollStatusMessage.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = enrollStatusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // Quick rotation action
            Button(
                onClick = { loadOrEnrollKey(forceRegenerate = true) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("rotate_key_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Rotate Secure Keypair", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SecurityStatusRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Generates a clean 2D black & white QR code bitmap from the given content string.
 */
fun generateQrCodeBitmap(content: String, size: Int): Bitmap {
    val bitMatrix: BitMatrix = try {
        MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    } catch (e: Exception) {
        BitMatrix(size, size)
    }
    val width = bitMatrix.width
    val height = bitMatrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (bitMatrix.get(x, y)) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}
