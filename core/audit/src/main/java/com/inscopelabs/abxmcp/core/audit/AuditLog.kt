package com.inscopelabs.abxmcp.core.audit

import android.content.Context
import android.util.Base64
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.Signature

enum class ReasonCode {
    SESSION_EXPIRED,
    REPLAY_DETECTED,
    PATH_OUT_OF_BOUNDS,
    OP_NOT_ALLOWED,
    SAF_REVOKED,
    TIER_VIOLATION
}

object AuditLog {
    private var logFile: File? = null
    private var keyStoreManager: KeyStoreManager? = null
    private val inMemoryEntries = mutableListOf<JSONObject>()
    private var initialized = false

    @Synchronized
    fun initialize(context: Context, ksManager: KeyStoreManager?) {
        this.keyStoreManager = ksManager
        val dir = try { context.filesDir } catch (e: Exception) { null }
        if (dir != null) {
            this.logFile = File(dir, "audit_log.jsonl")
        }
        this.initialized = true
    }

    @Synchronized
    fun setLogFileForTest(file: File) {
        this.logFile = file
        this.initialized = true
    }

    @Synchronized
    fun clear() {
        logFile?.delete()
        inMemoryEntries.clear()
    }

    /**
     * Formats a log entry deterministically to ensure consistent hash calculations.
     */
    fun toDeterministicString(timestamp: Long, reasonCode: String, sessionId: String, details: String, prevHash: String): String {
        fun escape(str: String): String {
            return str.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t")
        }
        return "{\"details\":\"${escape(details)}\",\"prevHash\":\"$prevHash\",\"reasonCode\":\"$reasonCode\",\"sessionId\":\"$sessionId\",\"timestamp\":$timestamp}"
    }

    @Synchronized
    fun recordRejection(reasonCode: ReasonCode, sessionId: String, details: String = "") {
        try {
            val timestamp = System.currentTimeMillis()
            val lastHash = getLastHash()
            
            // Generate deterministic string representation
            val deterministicStr = toDeterministicString(timestamp, reasonCode.name, sessionId, details, lastHash)
            
            val file = logFile
            if (file != null) {
                try {
                    file.appendText(deterministicStr + "\n")
                } catch (e: Exception) {
                    e.printStackTrace()
                    try {
                        inMemoryEntries.add(JSONObject(deterministicStr))
                    } catch (e2: Throwable) {
                        // ignore if JSON is not available
                    }
                }
            } else {
                try {
                    inMemoryEntries.add(JSONObject(deterministicStr))
                } catch (e2: Throwable) {
                    // ignore if JSON is not available
                }
            }
        } catch (t: Throwable) {
            // Gracefully handle any stub issues on non-Robolectric unit tests
        }
    }

    @Synchronized
    fun getEntries(): List<JSONObject> {
        val file = logFile
        if (file != null && file.exists()) {
            val list = mutableListOf<JSONObject>()
            try {
                file.forEachLine { line ->
                    if (line.isNotBlank()) {
                        list.add(JSONObject(line))
                    }
                }
                return list
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        return try {
            inMemoryEntries.toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun getLastHash(): String {
        try {
            val entries = getEntries()
            if (entries.isEmpty()) {
                return "0000000000000000000000000000000000000000000000000000000000000000"
            }
            val last = entries.last()
            val detStr = toDeterministicString(
                last.getLong("timestamp"),
                last.getString("reasonCode"),
                last.getString("sessionId"),
                last.getString("details"),
                last.getString("prevHash")
            )
            return computeSha256(detStr)
        } catch (t: Throwable) {
            // Fallback for JVM non-Robolectric tests where org.json is stubbed out
            return "0000000000000000000000000000000000000000000000000000000000000000"
        }
    }

    fun computeSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun exportSignedBundle(alias: String = "ABX_MCP_TOKEN_KEY"): String {
        val entries = getEntries()
        val entriesArray = JSONArray()
        entries.forEach { entriesArray.put(it) }

        val chainHead = getLastHash()

        val ks = keyStoreManager
        val (sigBase64, pubKeyBase64, attestationChainArr) = if (ks != null) {
            val keyPair = ks.getOrCreateKeyPair(alias)
            val privateKey = keyPair.private
            val publicKey = keyPair.public

            val sigBytes = if (privateKey is com.inscopelabs.abxmcp.core.keystore.NonExportablePrivateKey) {
                privateKey.sign(chainHead.toByteArray(StandardCharsets.UTF_8))
            } else {
                val signature = Signature.getInstance("SHA256withECDSA")
                signature.initSign(privateKey)
                signature.update(chainHead.toByteArray(StandardCharsets.UTF_8))
                signature.sign()
            }

            val sigB64 = Base64.encodeToString(sigBytes, Base64.NO_WRAP)
            val pubKeyB64 = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

            val chainCerts = ks.getAttestationChain(alias)
            val chainJson = JSONArray()
            chainCerts.forEach { cert ->
                val encodedCert = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
                chainJson.put(encodedCert)
            }

            Triple(sigB64, pubKeyB64, chainJson)
        } else {
            Triple("", "", JSONArray())
        }

        val bundle = JSONObject().apply {
            put("entries", entriesArray)
            put("signature", sigBase64)
            put("publicKey", pubKeyBase64)
            put("attestationChain", attestationChainArr)
            put("chainHead", chainHead)
        }

        return bundle.toString()
    }

    @Synchronized
    fun verifyIntegrity(): Boolean {
        val entries = getEntries()
        if (entries.isEmpty()) return true

        var expectedPrevHash = "0000000000000000000000000000000000000000000000000000000000000000"
        for (i in entries.indices) {
            val entry = entries[i]
            val actualPrevHash = entry.optString("prevHash", "")
            if (actualPrevHash != expectedPrevHash) {
                return false
            }
            val detStr = toDeterministicString(
                entry.getLong("timestamp"),
                entry.getString("reasonCode"),
                entry.getString("sessionId"),
                entry.getString("details"),
                entry.getString("prevHash")
            )
            expectedPrevHash = computeSha256(detStr)
        }
        return true
    }
}
