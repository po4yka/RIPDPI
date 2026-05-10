package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours

internal fun normalizeHostAutolearnConfig(config: RipDpiHostAutolearnConfig): RipDpiHostAutolearnConfig =
    config.copy(
        penaltyTtlHours = normalizeHostAutolearnPenaltyTtlHours(config.penaltyTtlHours),
        maxHosts = normalizeHostAutolearnMaxHosts(config.maxHosts),
        storePath = config.storePath?.trim()?.takeIf { it.isNotEmpty() && config.enabled },
        networkScopeKey = config.networkScopeKey?.trim()?.takeIf { it.isNotEmpty() },
    )
