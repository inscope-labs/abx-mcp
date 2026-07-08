package com.inscopelabs.abxmcp.core.session

import java.util.Collections

class ReplayProtectionImpl(
    private val sessionManager: SessionManager,
    @Volatile private var windowSizeMs: Long = 30000L // default 30 seconds
) : ReplayProtection {

    private val seenNoncesMap = HashMap<Nonce, Long>()
    @Volatile private var lastTimestamp: Long = 0L

    override fun validateRequest(
        nonce: Nonce,
        timestampMs: Long,
        currentTimeMs: Long
    ): ValidationResult {
        // 1. Check session state first. Must be ACTIVE
        val state = sessionManager.getState()
        if (state !is SessionState.ACTIVE) {
            return ValidationResult.InvalidSessionState(state)
        }

        // 2. Boundary check: Send a request with timestamp exactly 1ms outside the acceptable window (configurable, e.g., 30 seconds). Must reject.
        val diff = Math.abs(currentTimeMs - timestampMs)
        if (diff > windowSizeMs) {
            return ValidationResult.OutsideTimestampWindow(diff)
        }

        synchronized(seenNoncesMap) {
            // 3. Nonce check: duplicate nonce check
            if (seenNoncesMap.containsKey(nonce)) {
                return ValidationResult.DuplicateNonce
            }

            // Add to seen nonces and update last timestamp
            seenNoncesMap[nonce] = currentTimeMs
            lastTimestamp = timestampMs

            // Opportunistic eviction: evict nonces older than windowSizeMs
            val iterator = seenNoncesMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (currentTimeMs - entry.value > windowSizeMs) {
                    iterator.remove()
                }
            }
        }

        return ValidationResult.Success
    }

    override fun reset() {
        synchronized(seenNoncesMap) {
            seenNoncesMap.clear()
        }
        lastTimestamp = 0L
    }

    override fun getSeenNonces(): Set<Nonce> {
        synchronized(seenNoncesMap) {
            return seenNoncesMap.keys.toSet()
        }
    }

    override fun getLastTimestamp(): Long = lastTimestamp

    override fun setWindowSizeMs(windowMs: Long) {
        this.windowSizeMs = windowMs
    }
}
