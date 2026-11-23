package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone

internal fun strategyAccessNotice(
    context: Context,
    config: String,
    authorized: Boolean,
): StrategyConfigBanner? {
    val chain = parseStrategyChainDsl(config).getOrNull() ?: return null
    val required =
        buildSet {
            chain.tcpSteps.forEach { step ->
                if (step.kind in AccessRequiredTcpSteps) {
                    add(step.kind.wireName)
                }
            }
            if (chain.udpSteps.any { it.kind == UdpChainStepKind.IpFrag2Udp }) {
                add(UdpChainStepKind.IpFrag2Udp.wireName)
            }
        }
    if (required.isEmpty()) return null
    return StrategyConfigBanner(
        title =
            context.getString(
                if (authorized) R.string.root_mode_strategy_requires_access else R.string.root_mode_toggle_label,
            ),
        message = required.joinToString(),
        tone = if (authorized) WarningBannerTone.Info else WarningBannerTone.Restricted,
    )
}

private val AccessRequiredTcpSteps =
    setOf(TcpChainStepKind.FakeRst, TcpChainStepKind.IpFrag2, TcpChainStepKind.MultiDisorder)
