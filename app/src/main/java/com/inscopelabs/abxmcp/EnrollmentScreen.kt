package com.inscopelabs.abxmcp

import com.inscopelabs.abxmcp.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.launch
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
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Component state
    var keyPair by remember { mutableStateOf<KeyPair?>(null) }
    var fingerprint by remember { mutableStateOf("") }
    var formattedFingerprint by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isHardwareBacked by remember { mutableStateOf(false) }
    var isStrongBoxBacked by remember { mutableStateOf(false) }
    var enrollStatusMessage by remember { mutableStateOf("") }
    var isFingerprintExpanded by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // Helper to format truncated fingerprint
    fun getTruncatedFingerprint(fp: String): String {
        if (fp.length < 16) return fp
        val firstPart = fp.take(4)
        val secondPart = fp.substring(4, 8)
        val lastSecondPart = fp.substring(fp.length - 8, fp.length - 4)
        val lastPart = fp.takeLast(4)
        return "$firstPart • $secondPart • ... • $lastSecondPart • $lastPart"
    }

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
                context.getString(R.string.msg_rotated_success)
            } else {
                context.getString(R.string.msg_secure_active)
            }
        } catch (e: Exception) {
            keyPair = null
            fingerprint = ""
            formattedFingerprint = ""
            qrBitmap = null
            enrollStatusMessage = context.getString(R.string.msg_init_failed, e.message ?: "Unknown Error")
        }
    }

    // Logic to clear keys (Play Console delete user data compliance)
    fun clearCredentials() {
        try {
            keyStoreManager.deleteKeyPair(alias)
            keyPair = null
            fingerprint = ""
            formattedFingerprint = ""
            qrBitmap = null
            isHardwareBacked = false
            isStrongBoxBacked = false
            enrollStatusMessage = context.getString(R.string.msg_keys_cleared)
            coroutineScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.msg_keys_cleared))
            }
        } catch (e: Exception) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Failed to clear credentials: ${e.message}")
            }
        }
    }

    // Load on launch
    LaunchedEffect(Unit) {
        loadOrEnrollKey()
    }

    // About/Help Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Help,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.dialog_about_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Version info
                    Column {
                        Text(
                            text = stringResource(R.string.dialog_about_version),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.dialog_about_spec),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.dialog_about_support),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Privacy section
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.dialog_about_privacy_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.dialog_about_privacy_text),
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Terms section
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.dialog_about_terms_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.dialog_about_terms_text),
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showAboutDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.btn_close),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "ABC Security Shield Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAboutDialog = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(48.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.btn_about_info),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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

            // Status notification banner
            AnimatedVisibility(
                visible = enrollStatusMessage.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = if (keyPair != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = if (keyPair != null) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = if (keyPair != null) "Success Status Icon" else "Error Warning Icon",
                            tint = if (keyPair != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = enrollStatusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (keyPair != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (keyPair == null) {
                // Empty / Failure / Reset state
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock Closed Icon",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(64.dp)
                        )

                        Text(
                            text = stringResource(R.string.error_state_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = stringResource(R.string.error_state_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )

                        Button(
                            onClick = { loadOrEnrollKey() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.btn_retry_initialization),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                // Sectioned and Restructured UI Layout

                // CARD 1: Device Identity (Fingerprint)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("fingerprint_card")
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = stringResource(R.string.section_device_identity),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Text(
                            text = stringResource(R.string.label_fingerprint_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (isFingerprintExpanded) {
                                        formattedFingerprint
                                    } else {
                                        getTruncatedFingerprint(fingerprint)
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .fillMaxWidth()
                                        .testTag("fingerprint_text"),
                                    textAlign = TextAlign.Center
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (fingerprint.isNotEmpty()) {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("ABC Server Public Key Fingerprint", fingerprint)
                                        clipboard.setPrimaryClip(clip)
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.msg_fingerprint_copied))
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(48.dp)
                                    .testTag("copy_fingerprint_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.btn_copy_fingerprint),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // Monospace Full-Fingerprint Toggle Button
                        TextButton(
                            onClick = { isFingerprintExpanded = !isFingerprintExpanded },
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(
                                imageVector = if (isFingerprintExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                text = if (isFingerprintExpanded) stringResource(R.string.btn_hide_full) else stringResource(R.string.btn_view_full),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // CARD 2: Verification (QR Code)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("qr_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = stringResource(R.string.section_verification),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Text(
                            text = stringResource(R.string.label_scan_to_enroll),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
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
                                    contentDescription = "Device Public Key Fingerprint QR Code for Server Enrollment",
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
                            text = stringResource(R.string.desc_verification_instruction),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                // CARD 3: Security Status
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = CardDefaults.outlinedCardBorder(),
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
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = if (isHardwareBacked || !keyStoreManager.isAndroidKeyStore) Color(0xFF2E7D32) else Color(0xFFD84315),
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = stringResource(R.string.label_hardware_enclave),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Dynamic hardware chip indicator
                            val isSecure = isHardwareBacked || !keyStoreManager.isAndroidKeyStore
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = if (isSecure) "TEE SECURE" else "UNPROTECTED",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (isSecure) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                    labelColor = if (isSecure) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                        SecurityStatusRow(
                            label = stringResource(R.string.label_provider_backend),
                            value = if (keyStoreManager.isAndroidKeyStore) {
                                stringResource(R.string.val_android_keystore)
                            } else {
                                stringResource(R.string.val_jvm_sandbox)
                            }
                        )
                        SecurityStatusRow(
                            label = stringResource(R.string.label_key_isolation),
                            value = stringResource(R.string.val_key_isolation_text)
                        )
                        SecurityStatusRow(
                            label = stringResource(R.string.label_hardware_backed),
                            value = if (isHardwareBacked || !keyStoreManager.isAndroidKeyStore) {
                                stringResource(R.string.val_hardware_backed_yes)
                            } else {
                                stringResource(R.string.val_hardware_backed_no)
                            }
                        )
                        if (isStrongBoxBacked) {
                            SecurityStatusRow(
                                label = stringResource(R.string.label_strongbox_support),
                                value = stringResource(R.string.val_strongbox_yes)
                            )
                        }
                    }
                }

                // CARD 4: Hardware Actions
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
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
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = stringResource(R.string.section_enclave_actions),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Rotation Action Button
                        Button(
                            onClick = { loadOrEnrollKey(forceRegenerate = true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("rotate_key_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = stringResource(R.string.btn_rotate_key),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Clear Credentials Action Button (Google Play compliance for data erasure)
                        OutlinedButton(
                            onClick = { clearCredentials() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = stringResource(R.string.btn_clear_credentials),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SecurityStatusRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
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
