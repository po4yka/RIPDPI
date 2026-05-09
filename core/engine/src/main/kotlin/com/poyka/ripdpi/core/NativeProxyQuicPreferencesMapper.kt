package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.effectiveQuicFakeHost
import com.poyka.ripdpi.data.effectiveQuicFakeProfile
import com.poyka.ripdpi.data.effectiveQuicInitialMode
import com.poyka.ripdpi.data.effectiveQuicSupportV1
import com.poyka.ripdpi.data.effectiveQuicSupportV2
import com.poyka.ripdpi.proto.AppSettings

internal fun buildQuicConfig(settings: AppSettings): RipDpiQuicConfig =
    RipDpiQuicConfig(
        initialMode = settings.effectiveQuicInitialMode(),
        supportV1 = settings.effectiveQuicSupportV1(),
        supportV2 = settings.effectiveQuicSupportV2(),
        fakeProfile = settings.effectiveQuicFakeProfile(),
        fakeHost = settings.effectiveQuicFakeHost(),
    )
