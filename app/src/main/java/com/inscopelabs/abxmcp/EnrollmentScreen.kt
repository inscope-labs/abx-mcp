package com.inscopelabs.abxmcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
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
import com.inscopelabs.abxmcp.core.audit.AuditLog
import com.inscopelabs.abxmcp.core.audit.ReasonCode
import com.inscopelabs.abxmcp.core.keystore.FingerprintUtils
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import com.inscopelabs.abxmcp.core.session.SessionManagerProvider
import com.inscopelabs.abxmcp.core.session.SessionState
import com.inscopelabs.abxmcp.core.session.UserGesture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.text.SimpleDateFormat
import java.util.*

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
    
    // Core Services
    val sessionManager = remember { SessionManagerProvider.get(context) }
    val sessionState by sessionManager.stateFlow.collectAsState()

    // Navigation and UX State
    var selectedTab by remember { mutableStateOf(0) } // 0: Connect, 1: Access, 2: Activity, 3: Remove
    var advancedToggleAccess by remember { mutableStateOf(false) }
    var advancedToggleActivity by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var pairingCodeInput by remember { mutableStateOf("") }
    var gatewayPairedStatus by remember { mutableStateOf("Not paired with any gateway") }

    // Core Key Enrollment State
    var keyPair by remember { mutableStateOf<KeyPair?>(null) }
    var fingerprint by remember { mutableStateOf("") }
    var formattedFingerprint by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isHardwareBacked by remember { mutableStateOf(false) }
    var isStrongBoxBacked by remember { mutableStateOf(false) }
    var enrollStatusMessage by remember { mutableStateOf("") }
    var isFingerprintExpanded by remember { mutableStateOf(false) }

    // Session Live Countdown State
    var ttlRemaining by remember { mutableStateOf(sessionManager.getSessionTtl()) }

    // Refresh triggers
    var auditRefreshTrigger by remember { mutableStateOf(0) }

    // Predictive back gesture: if selectedTab > 0, go back to 0
    if (selectedTab > 0) {
        BackHandler {
            selectedTab = 0
        }
    }

    // Load or enroll key
    fun loadOrEnrollKey(forceRegenerate: Boolean = false) {
        try {
            val kp = if (forceRegenerate) {
                keyStoreManager.generateKeyPair(alias)
            } else {
                keyStoreManager.getOrCreateKeyPair(alias)
            }
            keyPair = kp

            val rawFingerprint = FingerprintUtils.getFingerprint(kp.public)
            fingerprint = rawFingerprint
            formattedFingerprint = FingerprintUtils.formatFingerprint(rawFingerprint)
            qrBitmap = generateQrCodeBitmap(rawFingerprint, 512)

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
                isHardwareBacked = false
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

    // Clear key credentials (Play compliance)
    fun clearCredentials() {
        try {
            keyStoreManager.deleteKeyPair(alias)
            keyPair = null
            fingerprint = ""
            formattedFingerprint = ""
            qrBitmap = null
            isHardwareBacked = false
            isStrongBoxBacked = false
            gatewayPairedStatus = "Not paired with any gateway"
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

    // Initialize
    LaunchedEffect(Unit) {
        loadOrEnrollKey()
    }

    // Live session ticker
    LaunchedEffect(sessionState) {
        ttlRemaining = sessionManager.getSessionTtl()
        if (sessionState is SessionState.ACTIVE) {
            while (true) {
                delay(1000L)
                val nextTtl = sessionManager.decrementTtl(1)
                ttlRemaining = nextTtl
                if (nextTtl <= 0) {
                    sessionManager.expireSession()
                    break
                }
            }
        }
    }

    // Main App Bar and Adaptive Layout Container
    Box(modifier = modifier.fillMaxSize()) {
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
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAboutDialog = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(48.dp)
                            .testTag("top_bar_about_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.btn_about_info)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        bottomBar = {
            // Display Bottom Navigation Bar ONLY on Compact layout (Phone)
            BoxWithConstraints {
                if (maxWidth < 600.dp) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.testTag("bottom_nav_bar")
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_connect)) },
                            modifier = Modifier.testTag("nav_tab_connect")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_access)) },
                            modifier = Modifier.testTag("nav_tab_access")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.History, contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_activity)) },
                            modifier = Modifier.testTag("nav_tab_activity")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_remove)) },
                            modifier = Modifier.testTag("nav_tab_remove")
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->

        // Adaptive Layout Selector: BoxWithConstraints
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isTablet = maxWidth >= 600.dp

            Row(modifier = Modifier.fillMaxSize()) {
                // Side Navigation Rail for Large/Tablet Screens
                if (isTablet) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxHeight()
                            .testTag("side_nav_rail")
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        NavigationRailItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.tab_connect)) },
                            label = { Text(stringResource(R.string.tab_connect)) },
                            modifier = Modifier.testTag("nav_tab_connect_rail")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        NavigationRailItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.VpnKey, contentDescription = stringResource(R.string.tab_access)) },
                            label = { Text(stringResource(R.string.tab_access)) },
                            modifier = Modifier.testTag("nav_tab_access_rail")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        NavigationRailItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.History, contentDescription = stringResource(R.string.tab_activity)) },
                            label = { Text(stringResource(R.string.tab_activity)) },
                            modifier = Modifier.testTag("nav_tab_activity_rail")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        NavigationRailItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.tab_remove)) },
                            label = { Text(stringResource(R.string.tab_remove)) },
                            modifier = Modifier.testTag("nav_tab_remove_rail")
                        )
                    }
                }

                // Main Scrollable Content Pane
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    when (selectedTab) {
                        0 -> ConnectScreenContent(
                            keyPair = keyPair,
                            fingerprint = fingerprint,
                            formattedFingerprint = formattedFingerprint,
                            qrBitmap = qrBitmap,
                            isHardwareBacked = isHardwareBacked,
                            isStrongBoxBacked = isStrongBoxBacked,
                            isFingerprintExpanded = isFingerprintExpanded,
                            onToggleFingerprint = { isFingerprintExpanded = !isFingerprintExpanded },
                            enrollStatusMessage = enrollStatusMessage,
                            onLoadKey = { loadOrEnrollKey() },
                            onRotateKey = { loadOrEnrollKey(forceRegenerate = true) },
                            onClearKeys = { clearCredentials() },
                            gatewayPairedStatus = gatewayPairedStatus,
                            onShowPairing = { showPairingDialog = true },
                            keyStoreManager = keyStoreManager,
                            snackbarHostState = snackbarHostState,
                            coroutineScope = coroutineScope
                        )
                        1 -> AccessScreenContent(
                            sessionState = sessionState,
                            ttlRemaining = ttlRemaining,
                            onStartSession = {
                                try {
                                    sessionManager.startSession(UserGesture.LocalButtonPress)
                                } catch (e: Exception) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Cannot start session: ${e.message}")
                                    }
                                }
                            },
                            onStopSession = {
                                try {
                                    sessionManager.stopSession()
                                } catch (e: Exception) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Cannot stop session: ${e.message}")
                                    }
                                }
                            },
                            advancedToggle = advancedToggleAccess,
                            onToggleAdvanced = { advancedToggleAccess = !advancedToggleAccess },
                            fingerprint = fingerprint
                        )
                        2 -> ActivityScreenContent(
                            advancedToggle = advancedToggleActivity,
                            onToggleAdvanced = { advancedToggleActivity = !advancedToggleActivity },
                            refreshTrigger = auditRefreshTrigger,
                            onAddSimulatedEvent = { code ->
                                AuditLog.recordRejection(code, "session_mock", "Simulated security event validation")
                                auditRefreshTrigger++
                            }
                        )
                        3 -> RemoveScreenContent(
                            onWipeData = { clearCredentials() }
                        )
                    }
                }
            }
        }
    }

    // About / Help Dialog as custom overlay modal to ensure 100% platform-agnostic rendering and testability
    if (showAboutDialog) {
        CustomModalDialog(
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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

    // Mock Gateway Enrollment Dialog as custom overlay modal to ensure 100% platform-agnostic rendering and testability
    if (showPairingDialog) {
        CustomModalDialog(
            onDismissRequest = { showPairingDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.mock_pairing_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.mock_pairing_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pairingCodeInput,
                        onValueChange = { pairingCodeInput = it },
                        placeholder = { Text("https://abc-gateway.local/enroll") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pairing_input_field"),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        gatewayPairedStatus = if (pairingCodeInput.isNotBlank()) {
                            "Paired with Gateway: ${pairingCodeInput.trim()}"
                        } else {
                            "Paired with Mock Gateway (https://abc-gateway.local/enroll)"
                        }
                        showPairingDialog = false
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.dialog_enroll_success))
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirm_pairing_button")
                ) {
                    Text("Enroll")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPairingDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    }
}

// ==========================================
// SCREEN 1: Connect Screen Content
// ==========================================
@Composable
fun ConnectScreenContent(
    keyPair: KeyPair?,
    fingerprint: String,
    formattedFingerprint: String,
    qrBitmap: Bitmap?,
    isHardwareBacked: Boolean,
    isStrongBoxBacked: Boolean,
    isFingerprintExpanded: Boolean,
    onToggleFingerprint: () -> Unit,
    enrollStatusMessage: String,
    onLoadKey: () -> Unit,
    onRotateKey: () -> Unit,
    onClearKeys: () -> Unit,
    gatewayPairedStatus: String,
    onShowPairing: () -> Unit,
    keyStoreManager: KeyStoreManager,
    snackbarHostState: SnackbarHostState,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Pairing Status Banner
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (gatewayPairedStatus.startsWith("Paired")) 
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth().testTag("pairing_status_banner")
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (gatewayPairedStatus.startsWith("Paired")) Icons.Default.Link else Icons.Default.LinkOff,
                    contentDescription = null,
                    tint = if (gatewayPairedStatus.startsWith("Paired")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column {
                    Text(
                        text = "Gateway Pairing Status",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = gatewayPairedStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Verification Status Message Banner
        AnimatedVisibility(
            visible = enrollStatusMessage.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                color = if (keyPair != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = if (keyPair != null) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
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
            // Empty / Error Initialisation Card
            Card(
                colors = CardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceDim,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                border = CardDefaults.outlinedCardBorder(),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Closed Lock Icon indicating Uninitialized State",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
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
                        onClick = { onLoadKey() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("retry_init_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.btn_retry_initialization),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // Main Identity and QR Pairing Cards

            // 1. Device Identity (Fingerprint)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("fingerprint_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).animateContentSize(),
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
                                text = if (isFingerprintExpanded) formattedFingerprint else getTruncatedFingerprint(fingerprint),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(12.dp).fillMaxWidth().testTag("fingerprint_text"),
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

                    TextButton(
                        onClick = onToggleFingerprint,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .heightIn(min = 48.dp)
                            .testTag("expand_fingerprint_button")
                    ) {
                        Icon(
                            imageVector = if (isFingerprintExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text(
                            text = if (isFingerprintExpanded) stringResource(R.string.btn_hide_full) else stringResource(R.string.btn_view_full),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 2. Verification QR Code Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("qr_card")
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
                            imageVector = Icons.Default.QrCodeScanner,
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(
                        text = stringResource(R.string.label_scan_to_enroll),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Device Public Key Fingerprint QR Code for Server Enrollment",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().testTag("qr_image")
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

                    Spacer(modifier = Modifier.height(4.dp))

                    // Gateway simulation trigger (essential for the non-technical walkthrough)
                    Button(
                        onClick = onShowPairing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("simulate_enrollment_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.mock_pairing_button),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3. Hardware security enclave status card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                border = CardDefaults.outlinedCardBorder(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("enclave_card")
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

                    SecurityStatusRow(
                        label = stringResource(R.string.label_provider_backend),
                        value = if (keyStoreManager.isAndroidKeyStore) stringResource(R.string.val_android_keystore) else stringResource(R.string.val_jvm_sandbox)
                    )
                    SecurityStatusRow(
                        label = stringResource(R.string.label_key_isolation),
                        value = stringResource(R.string.val_key_isolation_text)
                    )
                    SecurityStatusRow(
                        label = stringResource(R.string.label_hardware_backed),
                        value = if (isHardwareBacked || !keyStoreManager.isAndroidKeyStore) stringResource(R.string.val_hardware_backed_yes) else stringResource(R.string.val_hardware_backed_no)
                    )
                    if (isStrongBoxBacked) {
                        SecurityStatusRow(
                            label = stringResource(R.string.label_strongbox_support),
                            value = stringResource(R.string.val_strongbox_yes)
                        )
                    }
                }
            }

            // 4. Hardware enclave actions
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("actions_card")
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Button(
                        onClick = onRotateKey,
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
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onClearKeys,
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
                            .testTag("clear_credentials_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.btn_clear_credentials),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 2: Access Screen Content
// ==========================================
@Composable
fun AccessScreenContent(
    sessionState: SessionState,
    ttlRemaining: Int,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    advancedToggle: Boolean,
    onToggleAdvanced: () -> Unit,
    fingerprint: String
) {
    val isActive = sessionState is SessionState.ACTIVE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Large Session Action Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
            ),
            border = CardDefaults.outlinedCardBorder(),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().testTag("session_control_card")
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Large Session Status Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isActive) Color(0xFF2E7D32) else Color(0xFF757575))
                    )
                    Text(
                        text = if (isActive) "ACTIVE SESSION" else "INACTIVE / EXPIRED",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // TTL Countdown Display
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isActive) stringResource(R.string.ttl_display, ttlRemaining) else stringResource(R.string.ttl_inactive),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("countdown_text")
                    )
                    if (isActive) {
                        Text(
                            text = "Automatic session auto-expiry countdown is running.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                // START/STOP TRIGGER BUTTON
                Button(
                    onClick = {
                        if (isActive) onStopSession() else onStartSession()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (isActive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .testTag(if (isActive) "stop_session_button" else "start_session_button")
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp).padding(end = 8.dp)
                    )
                    Text(
                        text = if (isActive) stringResource(R.string.btn_stop_session) else stringResource(R.string.btn_start_session),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        // Plain Language Access Policy Summary
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder(),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("policy_summary_card")
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
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.policy_summary_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                if (isActive) {
                    Text(
                        text = stringResource(R.string.policy_summary_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.policy_summary_details),
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.policy_inactive_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    )
                }
            }
        }

        // Advanced Toggle & Capability JSON viewer
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder(),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("advanced_access_card")
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
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(R.string.advanced_toggle),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = advancedToggle,
                        onCheckedChange = { onToggleAdvanced() },
                        modifier = Modifier.testTag("advanced_switch_access")
                    )
                }

                AnimatedVisibility(visible = advancedToggle) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        val mockToken = JSONObject().apply {
                            put("sessionId", if (isActive) "sess_active_99" else "sess_inactive_00")
                            put("expiry", System.currentTimeMillis() + if (isActive) ttlRemaining * 1000L else 0L)
                            put("allowedOperations", listOf("read_file", "write_file", "list_directory"))
                            put("allowedRoots", listOf("/storage/emulated/0/Download", "/storage/emulated/0/Documents"))
                            put("nonceSeed", "seed_abc_123_xyz")
                            put("fingerprint", fingerprint)
                        }

                        Surface(
                            color = Color(0xFF1E1E1E),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = mockToken.toString(2),
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp).testTag("raw_token_json_text")
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 3: Activity Screen Content
// ==========================================
@Composable
fun ActivityScreenContent(
    advancedToggle: Boolean,
    onToggleAdvanced: () -> Unit,
    refreshTrigger: Int,
    onAddSimulatedEvent: (ReasonCode) -> Unit
) {
    val logs = remember(refreshTrigger) { AuditLog.getEntries() }
    val isChainSecure = remember(refreshTrigger) { AuditLog.verifyIntegrity() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header Section
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.audit_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.audit_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Log chain integrity status chip
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isChainSecure) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
            ),
            modifier = Modifier.fillMaxWidth().testTag("integrity_banner")
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isChainSecure) Icons.Default.Verified else Icons.Default.ReportGmailerrorred,
                    contentDescription = null,
                    tint = if (isChainSecure) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
                Text(
                    text = if (isChainSecure) stringResource(R.string.audit_integrity_valid) else stringResource(R.string.audit_integrity_invalid),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isChainSecure) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
            }
        }

        // Fast actions to trigger simulated block event for walkthroughs and compliance verification
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().testTag("sim_rejection_card")
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Verify System Security (Interactive Validation)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Tap a button to trigger a policy verification test event. Confirm the event gets recorded in the chronological log below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onAddSimulatedEvent(ReasonCode.SESSION_EXPIRED) },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp).testTag("sim_btn_expired"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Simulate Expired", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { onAddSimulatedEvent(ReasonCode.PATH_OUT_OF_BOUNDS) },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp).testTag("sim_btn_bounds"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Simulate Out-of-Bounds", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Advanced toggle
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Show Technical JSON Details",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = advancedToggle,
                onCheckedChange = { onToggleAdvanced() },
                modifier = Modifier.testTag("advanced_switch_activity")
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Event List
        if (logs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = stringResource(R.string.audit_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("audit_logs_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                logs.reversed().forEachIndexed { index, json ->
                    val rawReason = json.optString("reasonCode", "UNKNOWN")
                    val details = json.optString("details", "")
                    val sessionId = json.optString("sessionId", "N/A")
                    val timestamp = json.optLong("timestamp", 0L)

                    // Translate reason code into warm, plain-language descriptions
                    val translatedReason = when (rawReason) {
                        "SESSION_EXPIRED" -> "Blocked: session had ended"
                        "REPLAY_DETECTED" -> "Blocked: duplicate request detected"
                        "PATH_OUT_OF_BOUNDS" -> "Blocked: access outside of authorized folders"
                        "OP_NOT_ALLOWED" -> "Blocked: operation not allowed"
                        "SAF_REVOKED" -> "Blocked: system permission revoked"
                        "TIER_VIOLATION" -> "Blocked: restricted operation attempted"
                        else -> "Blocked: security policy violation"
                    }

                    val dateStr = remember(timestamp) {
                        try {
                            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                            sdf.format(Date(timestamp))
                        } catch (e: Exception) {
                            "Unknown time"
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = CardDefaults.outlinedCardBorder(),
                        modifier = Modifier.fillMaxWidth().testTag("log_item_$index")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.error)
                                    )
                                    Text(
                                        text = translatedReason,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Text(
                                    text = dateStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Policy Exception Details: $details",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = "Session ID: $sessionId",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 2.dp)
                            )

                            if (advancedToggle) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "TECHNICAL JSON FOR SECURITY REVIEW:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Surface(
                                    color = Color(0xFF1E1E1E),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = json.toString(2),
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF81C784),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(8.dp).testTag("raw_log_json_$index")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 4: Remove Screen Content
// ==========================================
@Composable
fun RemoveScreenContent(
    onWipeData: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Uninstallation & Compliance explanation card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder(),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("uninstall_card")
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
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.uninstall_instructions_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = stringResource(R.string.uninstall_instructions_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Manual erasure card (Google Play erasure compliance)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder(),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("data_erasure_card")
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
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Wipe Cryptographic Data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = stringResource(R.string.clear_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onWipeData,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("wipe_data_compliance_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.btn_clear_data),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ==========================================
// TOP-LEVEL HELPER COMPONENTS & UTILS
// ==========================================
@Composable
fun SecurityStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

fun generateQrCodeBitmap(content: String, size: Int): Bitmap {
    val bitMatrix: BitMatrix = MultiFormatWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size
    )
    val width = bitMatrix.width
    val height = bitMatrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (bitMatrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

fun getTruncatedFingerprint(fp: String): String {
    if (fp.length < 16) return fp
    val firstPart = fp.take(4)
    val secondPart = fp.substring(4, 8)
    val lastSecondPart = fp.substring(fp.length - 8, fp.length - 4)
    val lastPart = fp.takeLast(4)
    return "$firstPart • $secondPart • ... • $lastSecondPart • $lastPart"
}

@Composable
fun CustomModalDialog(
    onDismissRequest: () -> Unit,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Scrim background area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {
                    onDismissRequest()
                }
        )

        // Dialog content card
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .padding(24.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {} // Consume click inside dialog
                )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (icon != null) {
                    Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        icon()
                    }
                }

                Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    title()
                }

                Box(modifier = Modifier.align(Alignment.Start)) {
                    text()
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dismissButton != null) {
                        dismissButton()
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    confirmButton()
                }
            }
        }
    }
}
