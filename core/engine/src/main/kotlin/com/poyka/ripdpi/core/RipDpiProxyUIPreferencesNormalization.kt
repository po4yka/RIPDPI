package com.poyka.ripdpi.core

internal fun normalizeListenConfig(config: RipDpiListenConfig): RipDpiListenConfig =
    config.copy(ip = config.ip.ifBlank { "127.0.0.1" })
