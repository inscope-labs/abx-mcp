package com.inscopelabs.abxmcp.core.keystore

import java.security.MessageDigest
import java.security.PublicKey

object FingerprintUtils {
    /**
     * Derives the SHA-256 fingerprint of a public key and returns it as an uppercase hex string.
     */
    fun getFingerprint(publicKey: PublicKey): String {
        val encoded = publicKey.encoded ?: return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(encoded)
        return hash.joinToString("") { "%02X".format(it) }
    }

    /**
     * Formats a hex fingerprint into a colon-separated string for easier user verification.
     */
    fun formatFingerprint(fingerprint: String): String {
        return fingerprint.chunked(2).joinToString(":")
    }
}
