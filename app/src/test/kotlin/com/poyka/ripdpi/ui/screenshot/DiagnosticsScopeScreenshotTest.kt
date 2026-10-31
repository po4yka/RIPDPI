package com.poyka.ripdpi.ui.screenshot

import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.BuildScanUiModelParams
import com.poyka.ripdpi.activities.DiagnosticsUiFactorySupport
import com.poyka.ripdpi.activities.buildScanUiModel
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsScanWorkflowCard
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DiagnosticsScopeScreenshotTest {
    @Test
    fun measurementScopeLight() = captureScope("measurementScope_light")

    @Test
    fun measurementScopeDark() = captureScope("measurementScope_dark", darkMode = true)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun measurementScopeRtl() = captureScope("measurementScope_rtl", direction = LayoutDirection.Rtl)

    @Test
    fun measurementScopeMaximumFont() = captureScope("measurementScope_max_font", fontScale = 2f)

    private fun captureScope(
        name: String,
        darkMode: Boolean = false,
        direction: LayoutDirection? = null,
        fontScale: Float = 1f,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val request =
            DiagnosticsProfileProjection(kind = ScanKind.CONNECTIVITY, family = DiagnosticProfileFamily.GENERAL)
        val profile =
            DiagnosticProfile(
                id = "connectivity",
                name = context.getString(R.string.diagnostics_profile_connectivity_title),
                source = "bundled",
                version = 1,
                request = request,
                updatedAt = 0L,
            )
        val scan =
            DiagnosticsUiFactorySupport(context).buildScanUiModel(
                BuildScanUiModelParams(
                    profiles = listOf(profile),
                    activeProfile = profile,
                    activeProfileRequest = request,
                    latestProfileSession = null,
                    activeScanPathMode = null,
                    latestReportResults = emptyList(),
                    latestResolverRecommendation = null,
                    latestStrategyProbeReport = null,
                    progress = null,
                    rawArgsEnabled = false,
                    serviceStatus = AppStatus.Running,
                    serviceMode = Mode.VPN,
                    autoResumeAfterRawScan =
                        com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue.diagnosticsAutoResumeAfterRawScan,
                    scanStartedAt = null,
                ),
            )
        captureSingle(
            name = name,
            widthDp = 420,
            heightDp = if (fontScale > 1f) 2000 else 1200,
            darkMode = darkMode,
            fontScale = fontScale,
            layoutDirection = direction,
            testClassFqn = javaClass.name,
        ) {
            DiagnosticsScanWorkflowCard(
                profile = requireNotNull(scan.selectedProfile),
                scan = scan,
                strategyProbeSelected = false,
                isFullAudit = false,
                onRunRawScan = {},
                onRunInPathScan = {},
                onCancelScan = {},
                onOpenAdvancedSettings = {},
                onOpenDnsSettings = {},
                onRequestVpnPermission = {},
                onOpenHistory = {},
                onOpenModeEditor = {},
                onOpenOwnedStackBrowser = {},
            )
        }
    }
}
