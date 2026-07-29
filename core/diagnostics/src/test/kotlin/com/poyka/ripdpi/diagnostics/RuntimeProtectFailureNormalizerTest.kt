package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NativeRuntimeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProtectFailureNormalizerTest {
    @Test
    fun `runtime protect rejection normalizes to the classifier contract`() {
        listOf("rejected", "error").forEach { outcome ->
            val normalized = rawProtectEvent(backend = "jni", outcome = outcome).toPrivacySafePersistedRuntimeEvent()

            requireNotNull(normalized)
            assertEquals("service", normalized.source)
            assertEquals("warn", normalized.level)
            assertEquals("event=protect_failed", normalized.message)
            assertEquals("protect_failure", normalized.kind)
            assertEquals("protect", normalized.subsystem)
            assertNull(normalized.runtimeId)
            assertNull(normalized.mode)
            assertNull(normalized.policySignature)
            assertNull(normalized.fingerprintHash)
        }
    }

    @Test
    fun `runtime protect normalizer fails closed on non failure envelopes`() {
        val variants =
            listOf(
                rawProtectEvent(backend = "jni", outcome = "success"),
                rawProtectEvent(backend = "noop", outcome = "error"),
                rawProtectEvent(backend = "jni", outcome = "unknown"),
                rawProtectEvent(backend = "jni", outcome = "error").copy(source = "service"),
                rawProtectEvent(backend = "jni", outcome = "error").copy(level = "warn"),
                rawProtectEvent(backend = "jni", outcome = "error").copy(subsystem = "vpn_protect"),
                rawProtectEvent(backend = "jni", outcome = "error").copy(
                    message = "vpn protect backend=jni outcome=error fd=33",
                ),
            )

        assertTrue(variants.all { event -> event.toPrivacySafePersistedRuntimeEvent() == null })
    }

    private fun rawProtectEvent(
        backend: String,
        outcome: String,
    ): NativeRuntimeEvent =
        NativeRuntimeEvent(
            source = "vpn_protect",
            level = "debug",
            message = "vpn protect backend=$backend outcome=$outcome",
            createdAt = 1L,
            kind = "vpn_protect",
            runtimeId = "private-runtime",
            mode = "private-mode",
            policySignature = "private-policy",
            fingerprintHash = "private-fingerprint",
            subsystem = "protect",
        )
}
