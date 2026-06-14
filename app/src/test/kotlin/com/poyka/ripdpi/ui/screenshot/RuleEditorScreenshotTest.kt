package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.ui.screens.routes.OutboundTarget
import com.poyka.ripdpi.ui.screens.routes.RuleEditorScreen
import com.poyka.ripdpi.ui.screens.routes.RuleEditorUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RuleEditorScreenshotTest {
    @Test
    fun newRule() {
        capture(
            "newRule",
            RuleEditorUiState(
                outboundTargets = OUTBOUND_TARGETS,
                loaded = true,
            ),
        )
    }

    @Test
    fun populatedRule() {
        capture(
            "populatedRule",
            RuleEditorUiState(
                name = "Streaming direct",
                enabled = true,
                domains = "domain_suffix:netflix.com\ngeosite:netflix",
                ipCidrs = "203.0.113.0/24",
                ports = "443",
                sourcePorts = "1024-65535",
                network = RuleNetwork.BOTH,
                processName = "com.netflix.mediaclient",
                packages = setOf("com.netflix.mediaclient", "com.google.android.youtube"),
                outboundTag = OutboundTag.Bypass,
                outboundTargets = OUTBOUND_TARGETS,
                loaded = true,
            ),
        )
    }

    private fun capture(
        name: String,
        state: RuleEditorUiState,
    ) {
        captureScreenBothThemes(name = name, widthDp = 420, heightDp = 1400, testClassFqn = FQN) {
            RuleEditorScreen(
                state = state,
                onBack = {},
                onNameChange = {},
                onEnabledChange = {},
                onDomainsChange = {},
                onIpCidrsChange = {},
                onPortsChange = {},
                onSourcePortsChange = {},
                onNetworkChange = {},
                onProcessNameChange = {},
                onPackagesChange = {},
                onOutboundChange = {},
                onSave = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.RuleEditorScreenshotTest"

        val OUTBOUND_TARGETS =
            listOf(
                OutboundTarget(OutboundTag.Proxy, "Proxy"),
                OutboundTarget(OutboundTag.Bypass, "Bypass (direct)"),
                OutboundTarget(OutboundTag.Block, "Block"),
            )
    }
}
