package com.inscopelabs.abxmcp.core.session

data class Nonce(val value: String)

interface ReplayProtection {
    fun validateRequest(nonce: Nonce, timestampMs: Long, currentTimeMs: Long): ValidationResult
    fun reset()
    fun getSeenNonces(): Set<Nonce>
    fun getLastTimestamp(): Long
    fun setWindowSizeMs(windowMs: Long)
}

sealed class ValidationResult {
    object Success : ValidationResult()
    data class InvalidSessionState(val state: SessionState) : ValidationResult()
    object DuplicateNonce : ValidationResult()
    data class OutsideTimestampWindow(val differenceMs: Long) : ValidationResult()
}
