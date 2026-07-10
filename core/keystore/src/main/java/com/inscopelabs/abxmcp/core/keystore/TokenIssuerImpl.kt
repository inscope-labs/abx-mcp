package com.inscopelabs.abxmcp.core.keystore

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.Signature

class TokenIssuerImpl(private val keyStoreManager: KeyStoreManager) : TokenIssuer {

    override fun issueToken(
        sessionId: String,
        expiry: Long,
        allowedOperations: List<String>,
        allowedRoots: List<String>,
        nonceSeed: String,
        issuedTime: Long,
        maxRequestCount: Int,
        alias: String
    ): String {
        val payloadObj = JSONObject().apply {
            put("sessionId", sessionId)
            put("expiry", expiry)
            put("allowedOperations", JSONArray(allowedOperations))
            put("allowedRoots", JSONArray(allowedRoots))
            put("nonceSeed", nonceSeed)
            put("issuedTime", issuedTime)
            put("maxRequestCount", maxRequestCount)
        }
        val payloadStr = payloadObj.toString()
        val payloadBase64 = Base64.encodeToString(payloadStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)

        val keyPair = keyStoreManager.getOrCreateKeyPair(alias)
        val privateKey = keyPair.private

        // Sign the payload base64 string
        val sigBytes = if (privateKey is NonExportablePrivateKey) {
            privateKey.sign(payloadBase64.toByteArray(Charsets.UTF_8))
        } else {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature.update(payloadBase64.toByteArray(Charsets.UTF_8))
            signature.sign()
        }
        val sigBase64 = Base64.encodeToString(sigBytes, Base64.NO_WRAP or Base64.URL_SAFE)

        return "$payloadBase64.$sigBase64"
    }

    override fun verifyAndParseToken(token: String, alias: String): ParsedToken? {
        val parts = token.split(".")
        if (parts.size != 2) return null

        val payloadBase64 = parts[0]
        val sigBase64 = parts[1]

        try {
            val keyPair = keyStoreManager.getOrCreateKeyPair(alias)
            val publicKey = keyPair.public

            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(payloadBase64.toByteArray(Charsets.UTF_8))

            val sigBytes = Base64.decode(sigBase64, Base64.NO_WRAP or Base64.URL_SAFE)
            if (!signature.verify(sigBytes)) {
                return null
            }

            // Signature verified, now parse the payload
            val payloadBytes = Base64.decode(payloadBase64, Base64.NO_WRAP or Base64.URL_SAFE)
            val payloadStr = String(payloadBytes, Charsets.UTF_8)
            val payloadObj = JSONObject(payloadStr)

            val sessionId = payloadObj.getString("sessionId")
            val expiry = payloadObj.getLong("expiry")

            val allowedOpsArr = payloadObj.getJSONArray("allowedOperations")
            val allowedOperations = mutableListOf<String>()
            for (i in 0 until allowedOpsArr.length()) {
                allowedOperations.add(allowedOpsArr.getString(i))
            }

            val allowedRootsArr = payloadObj.getJSONArray("allowedRoots")
            val allowedRoots = mutableListOf<String>()
            for (i in 0 until allowedRootsArr.length()) {
                allowedRoots.add(allowedRootsArr.getString(i))
            }

            val nonceSeed = payloadObj.getString("nonceSeed")
            val issuedTime = payloadObj.optLong("issuedTime", System.currentTimeMillis())
            val maxRequestCount = payloadObj.optInt("maxRequestCount", 0)

            return ParsedToken(
                sessionId = sessionId,
                expiry = expiry,
                allowedOperations = allowedOperations,
                allowedRoots = allowedRoots,
                nonceSeed = nonceSeed,
                issuedTime = issuedTime,
                maxRequestCount = maxRequestCount
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
