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
        alias: String
    ): String {
        val payloadObj = JSONObject().apply {
            put("sessionId", sessionId)
            put("expiry", expiry)
            put("allowedOperations", JSONArray(allowedOperations))
            put("allowedRoots", JSONArray(allowedRoots))
            put("nonceSeed", nonceSeed)
        }
        val payloadStr = payloadObj.toString()
        val payloadBase64 = Base64.encodeToString(payloadStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)

        val keyPair = keyStoreManager.getOrCreateKeyPair(alias)
        val privateKey = keyPair.private

        val realPrivateKey = if (privateKey.javaClass.simpleName == "NonExportablePrivateKey") {
            try {
                val field = privateKey.javaClass.getDeclaredField("delegate")
                field.isAccessible = true
                field.get(privateKey) as java.security.PrivateKey
            } catch (e: Exception) {
                privateKey
            }
        } else {
            privateKey
        }

        // Sign the payload base64 string
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(realPrivateKey)
        signature.update(payloadBase64.toByteArray(Charsets.UTF_8))
        val sigBytes = signature.sign()
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

            return ParsedToken(
                sessionId = sessionId,
                expiry = expiry,
                allowedOperations = allowedOperations,
                allowedRoots = allowedRoots,
                nonceSeed = nonceSeed
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
