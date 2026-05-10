package com.poyka.ripdpi.core

internal fun normalizeHostsConfig(config: RipDpiHostsConfig): RipDpiHostsConfig {
    val normalizedEntries = config.entries?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedMode = if (normalizedEntries == null) RipDpiHostsConfig.Mode.Disable else config.mode
    return RipDpiHostsConfig(
        mode = normalizedMode,
        entries = normalizedEntries.takeUnless { normalizedMode == RipDpiHostsConfig.Mode.Disable },
    )
}
