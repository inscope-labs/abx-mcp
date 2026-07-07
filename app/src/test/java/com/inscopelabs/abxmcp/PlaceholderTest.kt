package com.inscopelabs.abxmcp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 0 exit-condition proof.
 *
 * This test is INTENTIONALLY failing. Its only purpose is to confirm the CI
 * pipeline actually surfaces a red build when a test fails. Do not "fix" this
 * test by making it pass — replace/remove it once Phase 0 is verified and
 * real tests exist in Phase 1+.
 */
class PlaceholderTest {
    @Test
    fun placeholderIntentionallyFailingTest() {
        assertTrue(false, "Intentional failure: verifies CI fails loudly on a broken test (Phase 0 exit condition).")
    }
}
