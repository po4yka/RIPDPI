package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicInitialModeRouteAndCache
import com.poyka.ripdpi.data.normalizeQuicFakeHost
import com.poyka.ripdpi.data.normalizeQuicFakeProfile
import com.poyka.ripdpi.data.normalizeQuicInitialMode

internal fun normalizeQuicConfig(config: RipDpiQuicConfig): RipDpiQuicConfig =
    config.copy(
        initialMode = normalizeQuicInitialMode(config.initialMode.ifBlank { QuicInitialModeRouteAndCache }),
        fakeProfile = normalizeQuicFakeProfile(config.fakeProfile.ifBlank { QuicFakeProfileDisabled }),
        fakeHost = normalizeQuicFakeHost(config.fakeHost),
    )
