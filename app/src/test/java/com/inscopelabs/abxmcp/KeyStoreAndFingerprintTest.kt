package com.inscopelabs.abxmcp

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import com.inscopelabs.abxmcp.core.keystore.FingerprintUtils
import com.inscopelabs.abxmcp.core.keystore.KeyStoreEnvironment
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeyStoreAndFingerprintTest {

    private lateinit var context: Context
    private lateinit var keyStoreManager: KeyStoreManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyStoreManager = KeyStoreManager(context, KeyStoreEnvironment.TEST_FALLBACK)
    }

    @Test
    fun testPrivateKey_reflectionUnwrap_fails() {
        val alias = "test_reflection_alias"
        keyStoreManager.generateKeyPair(alias)
        val privateKey = keyStoreManager.getPrivateKeyForTest(alias)!!

        // Attempt the same reflective field access that TokenIssuerImpl used to do
        var reflectionSucceeded = false
        try {
            val field = privateKey.javaClass.getDeclaredField("delegate")
            field.isAccessible = true
            val unwrappedKey = field.get(privateKey) as java.security.PrivateKey
            reflectionSucceeded = true
        } catch (e: Exception) {
            // Expected to fail on the corrected implementation
        }

        assertFalse("Reflection unwrap must fail to prevent extracting raw private key bytes", reflectionSucceeded)
    }

    @Test
    fun testKeyStoreEnvironment_productionMode_ignoresHardwareSniffing() {
        val originalHardware = Build.HARDWARE
        val originalFingerprint = Build.FINGERPRINT
        try {
            org.robolectric.util.ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", "goldfish")
            org.robolectric.util.ReflectionHelpers.setStaticField(Build::class.java, "FINGERPRINT", "robolectric")

            // Forced to PRODUCTION/device mode
            val productionManager = KeyStoreManager(context, KeyStoreEnvironment.PRODUCTION)

            // Ensure that isAndroidKeyStore behaves exactly according to AndroidKeyStore availability,
            // ignoring Build.HARDWARE "goldfish" and Build.FINGERPRINT "robolectric".
            val expectedIsAndroidKeyStore = try {
                java.security.KeyStore.getInstance("AndroidKeyStore") != null
            } catch (e: Exception) {
                false
            }

            assertEquals(
                "In PRODUCTION mode, isAndroidKeyStore must match KeyStore availability, ignoring fingerprint/hardware sniffing",
                expectedIsAndroidKeyStore,
                productionManager.isAndroidKeyStore
            )
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", originalHardware)
            org.robolectric.util.ReflectionHelpers.setStaticField(Build::class.java, "FINGERPRINT", originalFingerprint)
        }
    }

    @Test
    fun testKeyGenParameterSpec_isExported_isFalse() {
        val spec = KeyGenParameterSpec.Builder(
            "test_alias",
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        val isExported = keyStoreManager.isExported(spec)
        assertFalse("KeyGenParameterSpec must specify non-exportable key material (isExported must be false)", isExported)
    }

    @Test
    fun testPrivateKey_isNonExportable_returnsNull() {
        val alias = "test_non_exportable_alias"
        val keyPair = keyStoreManager.generateKeyPair(alias)

        // Retrieve private key via our test-friendly wrapper
        val privateKey = keyStoreManager.getPrivateKeyForTest(alias)

        assertNotNull("Private key must exist in KeyStore", privateKey)

        // Verify that the private key is non-exportable (getEncoded returns null or throws an exception)
        val encoded = try {
            privateKey?.encoded
        } catch (e: Exception) {
            null
        }
        assertNull("Private key material must not be exportable (getEncoded must return null)", encoded)
    }

    @Test
    fun testAttestationChain_isNonNull_onApi24Plus() {
        val alias = "test_attestation_alias"
        keyStoreManager.generateKeyPair(alias)

        val chain = keyStoreManager.getAttestationChain(alias)
        assertNotNull("Attestation certificate chain must be non-null on API 24+", chain)
        assertFalse("Attestation certificate chain must not be empty", chain.isEmpty())
    }

    @Test
    fun testFingerprintDerivation() {
        val alias = "test_fingerprint_alias"
        val keyPair = keyStoreManager.generateKeyPair(alias)

        val fingerprint = FingerprintUtils.getFingerprint(keyPair.public)
        assertNotNull("Fingerprint must be non-null", fingerprint)
        assertTrue("Fingerprint must be uppercase hex SHA-256", fingerprint.matches(Regex("[0-9A-F]{64}")))

        val formatted = FingerprintUtils.formatFingerprint(fingerprint)
        assertEquals("Formatted fingerprint should have 32 octets separated by colons", 32, formatted.split(":").size)
    }
}
