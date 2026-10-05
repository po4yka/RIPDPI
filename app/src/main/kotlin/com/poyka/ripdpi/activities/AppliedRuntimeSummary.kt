package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.platform.StringResolver

internal fun confirmedRuntimeSummary(
    it: AppliedRuntimeConfiguration,
    strings: StringResolver,
): String =
    (
        it.effectiveSelection.confirmedSelectionLabels(strings) +
            listOfNotNull(
                it.dns.confirmedDnsLabel(strings),
                it.strategy.confirmedStrategyLabel(strings),
            )
    ).joinToString(" · ")
