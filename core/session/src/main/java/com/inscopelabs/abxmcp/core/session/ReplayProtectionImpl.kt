package com.inscopelabs.abxmcp.core.session

import java.util.Collections

class ReplayProtectionImpl(
    private val sessionManager: SessionManager,
    private var windowSizeMs: Long = 30000L // default 30 seconds
) : ReplayProtection {

    private val seenNonces = Collections.synchronizedSet(HashSet<Nonce>())
    private var lastTimestamp: Long = 0L

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

        // 3. Nonce check: duplicate nonce check
        if (seenNonces.contains(nonce)) {
            return ValidationResult.DuplicateNonce
        }

        // Add to seen nonces and update last timestamp
        seenNonces.add(nonce)
        lastTimestamp = timestampMs

        return ValidationResult.Success
    }

    override fun reset() {
        seenNonces.clear()
        lastTimestamp = 0L
    }

    override fun getSeenNonces(): Set<Nonce> = seenNonces.toSet()

    override fun getLastTimestamp(): Long = lastTimestamp

    override fun setWindowSizeMs(windowMs: Long) {
        this.windowSizeMs = windowMs
    }
}
