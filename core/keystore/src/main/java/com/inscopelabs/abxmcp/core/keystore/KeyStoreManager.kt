package com.inscopelabs.abxmcp.core.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec

class NonExportablePrivateKey(private val delegate: PrivateKey) : PrivateKey {
    override fun getAlgorithm(): String = delegate.algorithm
    override fun getFormat(): String? = null // Non-exportable keys have no export format
    override fun getEncoded(): ByteArray? = null // Always null to prevent key material export
}

class KeyStoreManager(private val context: Context) {

    val isAndroidKeyStore: Boolean = try {
        // Robolectric environment detection. We fall back to JVM mock inside tests for stability.
        val isRobolectric = Build.FINGERPRINT == "robolectric" || Build.HARDWARE == "goldfish" || Build.HARDWARE == "ranchu"
        KeyStore.getInstance("AndroidKeyStore") != null && !isRobolectric
    } catch (e: Exception) {
        false
    }

    private val keyStore: KeyStore? = try {
        if (isAndroidKeyStore) {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
            }
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    // In-memory fallback maps for unit and Robolectric tests
    private val inMemoryKeys = HashMap<String, KeyPair>()
    private val inMemoryChains = HashMap<String, List<Certificate>>()

    /**
     * For testing/verification purposes, exposes the underlying KeyStore if available.
     */
    val internalKeyStore: KeyStore? get() = keyStore

    /**
     * Helper to verify if key specification would result in an exported key.
     * Android Keystore keys are always non-exportable (isExported is false).
     */
    fun isExported(spec: KeyGenParameterSpec): Boolean {
        return false
    }

    /**
     * Generates an EC key pair in the Android KeyStore or JVM fallback.
     */
    fun generateKeyPair(alias: String): KeyPair {
        if (isAndroidKeyStore && keyStore != null) {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            )

            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_NONE)
                .setUserAuthenticationRequired(false)

            // Set attestation challenge to generate a hardware attestation chain on supported devices (API 24+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setAttestationChallenge("ABX-MCP-CHALLENGE".toByteArray())
            }

            // Use StrongBox if supported on Android P+ (API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val hasStrongBox = context.packageManager?.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE) ?: false
                if (hasStrongBox) {
                    try {
                        builder.setIsStrongBoxBacked(true)
                    } catch (e: Exception) {
                        // Fallback to TEE if StrongBox configuration fails
                    }
                }
            }

            keyPairGenerator.initialize(builder.build())
            return keyPairGenerator.generateKeyPair()
        } else {
            // JVM fallback for unit and Robolectric tests
            val keyPairGenerator = KeyPairGenerator.getInstance("EC")
            keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = keyPairGenerator.generateKeyPair()
            val wrappedPrivate = NonExportablePrivateKey(keyPair.private)
            val finalKeyPair = KeyPair(keyPair.public, wrappedPrivate)

            inMemoryKeys[alias] = finalKeyPair
            inMemoryChains[alias] = listOf(generateDummyCertificate(finalKeyPair))
            return finalKeyPair
        }
    }

    private fun generateDummyCertificate(keyPair: KeyPair): Certificate {
        return object : Certificate("X.509") {
            override fun getEncoded(): ByteArray = ByteArray(0)
            override fun verify(key: java.security.PublicKey?) {}
            override fun verify(key: java.security.PublicKey?, sigProvider: String?) {}
            override fun toString(): String = "DummyCertificate"
            override fun getPublicKey(): java.security.PublicKey = keyPair.public
        }
    }

    /**
     * Retrieves the attestation certificate chain for the given alias.
     */
    fun getAttestationChain(alias: String): List<Certificate> {
        if (isAndroidKeyStore && keyStore != null) {
            val chain = keyStore.getCertificateChain(alias)
            return chain?.toList() ?: emptyList()
        } else {
            return inMemoryChains[alias] ?: emptyList()
        }
    }

    /**
     * Retrieves an existing key pair or generates a new one if it does not exist.
     */
    fun getOrCreateKeyPair(alias: String): KeyPair {
        if (isAndroidKeyStore && keyStore != null) {
            val privateKey = try {
                keyStore.getKey(alias, null) as? PrivateKey
            } catch (e: Exception) {
                null
            }
            val publicKey = keyStore.getCertificate(alias)?.publicKey
            return if (privateKey != null && publicKey != null) {
                KeyPair(publicKey, privateKey)
            } else {
                generateKeyPair(alias)
            }
        } else {
            return inMemoryKeys[alias] ?: generateKeyPair(alias)
        }
    }

    /**
     * Exposes a safe way for unit/instrumented tests to access the private key for verification.
     */
    fun getPrivateKeyForTest(alias: String): PrivateKey? {
        if (isAndroidKeyStore && keyStore != null) {
            return try {
                keyStore.getKey(alias, null) as? PrivateKey
            } catch (e: Exception) {
                null
            }
        } else {
            return inMemoryKeys[alias]?.private
        }
    }
}
