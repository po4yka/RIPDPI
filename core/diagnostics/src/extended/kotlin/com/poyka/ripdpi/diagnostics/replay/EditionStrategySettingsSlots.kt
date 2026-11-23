package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.data.advancedTcpSignatureRequested
import com.poyka.ripdpi.data.setAdvancedTcpSignatureRequested

internal val editionStrategySettingsSlots =
    listOf(
        StrategySettingsSlot(
            "fakeMd5Sig",
            { it.advancedTcpSignatureRequested() },
            { builder, settings -> builder.setAdvancedTcpSignatureRequested(settings.advancedTcpSignatureRequested()) },
        ),
    )
