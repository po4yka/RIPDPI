package com.poyka.ripdpi.services.redaction

/**
 * Opt-in diagnostics mode with a time-to-live guard.
 *
 * Diagnostics is disabled by default. Callers must call [enable] with an
 * explicit TTL. [isActive] returns false once the TTL has elapsed according
 * to [clock]. Bundle export is gated behind [isActive].
 */
class DiagnosticsMode(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var enabledUntilMs: Long = 0L

    val isActive: Boolean
        get() = clock() < enabledUntilMs

    fun enable(ttlMs: Long) {
        enabledUntilMs = clock() + ttlMs
    }

    fun disable() {
        enabledUntilMs = 0L
    }

    fun canExportBundle(): Boolean = isActive
}
