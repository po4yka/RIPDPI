package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.data.xray.XrayConnectionStage
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.screens.xray.bannerTone
import com.poyka.ripdpi.ui.screens.xray.subtitle
import com.poyka.ripdpi.ui.screens.xray.title
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

/**
 * Home surface for the embedded Xray provider stage, rendered DISTINCTLY from the
 * tunnel destructive error banner (decision 6). It appears only while an Xray
 * session is active (snapshot non-null) and uses the provider-specific banner
 * tone — provider config/protect/DNS-loop failures NEVER take the tunnel
 * `WarningBannerTone.Error` treatment used by [HomeScreen]'s tunnel error banner.
 *
 * Null snapshot (native provider path, or VPN stopped) renders nothing.
 */
@Composable
internal fun HomeXrayProviderBanner(
    snapshot: XrayProviderSnapshot?,
    modifier: Modifier = Modifier,
) {
    if (snapshot == null) return
    val stage = XrayConnectionStage.fromSnapshot(snapshot)
    // Idle adds no signal on Home — suppress so the banner only surfaces an active
    // or failed provider state.
    if (stage is XrayConnectionStage.Idle) return
    WarningBanner(
        title = stage.title(),
        message = stage.subtitle(snapshot),
        tone = stage.bannerTone(),
        modifier = modifier.fillMaxWidth(),
        testTag = RipDpiTestTags.HomeXrayProviderBanner,
        // Provider state is a static, always-present notice while active — read on
        // focus, not re-announced on every recomposition.
        announce = stage is XrayConnectionStage.ProviderFailed,
    )
}
