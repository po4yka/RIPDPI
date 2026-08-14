package com.poyka.ripdpi.ui.screens.home

import androidx.compose.runtime.Composable
import com.poyka.ripdpi.data.xray.XrayConnectionStage
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.ui.screens.xray.bannerTone
import com.poyka.ripdpi.ui.screens.xray.subtitle
import com.poyka.ripdpi.ui.screens.xray.title
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

/**
 * The embedded Xray provider stage as a home advisory, kept DISTINCT from the
 * tunnel's own fault reporting (decision 6). It exists only while an Xray
 * session is active (snapshot non-null) and keeps the provider-specific banner
 * tone — provider config/protect/DNS-loop failures NEVER take the tunnel
 * `WarningBannerTone.Error` treatment the actuator's fault surface uses.
 *
 * A null snapshot (native provider path, or VPN stopped) yields no advisory.
 */
@Composable
internal fun xrayProviderAdvisory(snapshot: XrayProviderSnapshot?): HomeAdvisory? {
    // Idle adds no signal on Home, so it is suppressed alongside the null
    // snapshot: the advisory only ever surfaces an active or failed provider.
    val stage =
        snapshot
            ?.let { XrayConnectionStage.fromSnapshot(it) }
            ?.takeUnless { it is XrayConnectionStage.Idle }
            ?: return null
    val failed = stage is XrayConnectionStage.ProviderFailed
    return HomeAdvisory(
        title = stage.title(),
        message = stage.subtitle(snapshot),
        severity = if (failed) HomeAdvisorySeverity.Error else HomeAdvisorySeverity.Info,
        presentation = HomeAdvisoryPresentation.Banner,
        tone = stage.bannerTone(),
        actionLabel = null,
        onClick = null,
        testTag = RipDpiTestTags.HomeXrayProviderBanner,
        // Provider state is a static, always-present notice while active — read on
        // focus, not re-announced on every recomposition.
        announce = failed,
    )
}

/**
 * The provider stage on its own, for the screenshot catalogue that documents it
 * beside the rest of the Xray surfaces. Home reaches the same advisory through
 * [buildHomeAdvisories], so the two cannot drift apart.
 */
@Composable
internal fun HomeXrayProviderBanner(snapshot: XrayProviderSnapshot?) {
    xrayProviderAdvisory(snapshot)?.let { AdvisoryBanner(it) }
}
