package com.poyka.ripdpi.ui.screens.xray

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.xray.XrayConnectionStage
import com.poyka.ripdpi.data.xray.XrayProviderFailureClass
import com.poyka.ripdpi.data.xray.XrayProviderProbeKind
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone

/**
 * Presentation mapping for the embedded Xray provider health axis.
 *
 * The provider tone is kept VISUALLY + SEMANTICALLY DISTINCT from the tunnel
 * data-plane failure treatment (decision 6 / `.claude/rules/rds-spec.md`): a
 * provider config/protect/DNS-loop failure NEVER reuses the destructive tunnel
 * `Error` banner. Connected and ListenerReady map to neutral/positive tones;
 * provider-level failures map to the provider Warning/Restricted family so the
 * user can tell "provider not ready" apart from "tunnel down".
 *
 * Every string surfaced here is already privacy-safe — the snapshot deriver and
 * `XrayProfileRedactor` guarantee no secret or endpoint reaches these fields.
 *
 * Provider-distinct tone for the Home banner / Settings status surface.
 */
internal enum class XrayProviderTone {
    /** Engine serving traffic — neutral/positive, NOT the tunnel-success tone. */
    Connected,

    /** Engine coming up / probing — informational. */
    Progress,

    /** Provider-level failure — provider Warning family, NOT tunnel destructive. */
    Failed,

    /** No provider session bound — idle. */
    Idle,
}

internal fun XrayConnectionStage.tone(): XrayProviderTone =
    when (this) {
        XrayConnectionStage.Connected -> XrayProviderTone.Connected

        XrayConnectionStage.ValidatingConfig,
        XrayConnectionStage.StartingEngine,
        XrayConnectionStage.ListenerReady,
        XrayConnectionStage.ProbingOutbound,
        -> XrayProviderTone.Progress

        is XrayConnectionStage.ProviderFailed -> XrayProviderTone.Failed

        XrayConnectionStage.Idle -> XrayProviderTone.Idle
    }

/**
 * Banner tone for the provider stage. Provider failures intentionally use
 * [WarningBannerTone.Warning] (provider-specific) rather than
 * [WarningBannerTone.Error] (reserved for tunnel destructive failures), so the
 * two are not visually conflated. The protect / DNS-loop classes use
 * [WarningBannerTone.Restricted] because they are topology/permission hazards
 * with a distinct remediation.
 */
internal fun XrayConnectionStage.bannerTone(): WarningBannerTone =
    when (this) {
        XrayConnectionStage.Connected -> {
            WarningBannerTone.Info
        }

        XrayConnectionStage.ValidatingConfig,
        XrayConnectionStage.StartingEngine,
        XrayConnectionStage.ListenerReady,
        XrayConnectionStage.ProbingOutbound,
        -> {
            WarningBannerTone.Info
        }

        is XrayConnectionStage.ProviderFailed -> {
            when (failureClass) {
                XrayProviderFailureClass.ProtectFailure,
                XrayProviderFailureClass.DnsLoopSuspected,
                -> WarningBannerTone.Restricted

                else -> WarningBannerTone.Warning
            }
        }

        XrayConnectionStage.Idle -> {
            WarningBannerTone.Info
        }
    }

internal fun XrayProviderTone.statusTone(): StatusIndicatorTone =
    when (this) {
        XrayProviderTone.Connected -> StatusIndicatorTone.Active
        XrayProviderTone.Progress -> StatusIndicatorTone.Warning
        XrayProviderTone.Failed -> StatusIndicatorTone.Error
        XrayProviderTone.Idle -> StatusIndicatorTone.Idle
    }

@Composable
internal fun XrayConnectionStage.title(): String =
    when (this) {
        XrayConnectionStage.Idle -> stringResource(R.string.xray_provider_stage_idle)
        XrayConnectionStage.ValidatingConfig -> stringResource(R.string.xray_provider_stage_validating)
        XrayConnectionStage.StartingEngine -> stringResource(R.string.xray_provider_stage_starting)
        XrayConnectionStage.ListenerReady -> stringResource(R.string.xray_provider_stage_listener_ready)
        XrayConnectionStage.ProbingOutbound -> stringResource(R.string.xray_provider_stage_probing)
        XrayConnectionStage.Connected -> stringResource(R.string.xray_provider_stage_connected)
        is XrayConnectionStage.ProviderFailed -> stringResource(R.string.xray_provider_stage_failed)
    }

@Composable
internal fun XrayProviderFailureClass.label(): String =
    when (this) {
        XrayProviderFailureClass.None -> {
            stringResource(R.string.xray_provider_failure_none)
        }

        XrayProviderFailureClass.EngineStartFailure -> {
            stringResource(R.string.xray_provider_failure_engine_start)
        }

        XrayProviderFailureClass.ListenerBindFailure -> {
            stringResource(R.string.xray_provider_failure_listener_bind)
        }

        XrayProviderFailureClass.ConfigInvalid -> {
            stringResource(R.string.xray_provider_failure_config_invalid)
        }

        XrayProviderFailureClass.ProtectFailure -> {
            stringResource(R.string.xray_provider_failure_protect)
        }

        XrayProviderFailureClass.DnsLoopSuspected -> {
            stringResource(R.string.xray_provider_failure_dns_loop)
        }

        XrayProviderFailureClass.OutboundUnreachable -> {
            stringResource(R.string.xray_provider_failure_outbound_unreachable)
        }

        XrayProviderFailureClass.OutboundDegraded -> {
            stringResource(R.string.xray_provider_failure_outbound_degraded)
        }
    }

/**
 * A short, privacy-safe subtitle describing the provider stage. For a failure it
 * surfaces the failure CLASS label plus the already-redacted detail when present;
 * never the raw config / endpoint.
 */
@Composable
internal fun XrayConnectionStage.subtitle(snapshot: XrayProviderSnapshot): String =
    when (this) {
        is XrayConnectionStage.ProviderFailed -> {
            val classLabel = failureClass.label()
            val detail = detailRedacted ?: snapshot.lastFailureDetailRedacted
            if (detail.isNullOrBlank()) classLabel else "$classLabel — $detail"
        }

        XrayConnectionStage.Connected -> {
            val rtt = snapshot.lastPingRttMs
            if (rtt != null) {
                stringResource(R.string.xray_provider_connected_rtt_format, rtt)
            } else {
                stringResource(R.string.xray_provider_connected_body)
            }
        }

        XrayConnectionStage.Idle -> {
            stringResource(R.string.xray_provider_idle_body)
        }

        else -> {
            stringResource(R.string.xray_provider_progress_body)
        }
    }

@Composable
internal fun XrayProviderProbeKind.label(): String =
    when (this) {
        XrayProviderProbeKind.Version -> stringResource(R.string.xray_provider_probe_version)
        XrayProviderProbeKind.WrapperPing -> stringResource(R.string.xray_provider_probe_wrapper_ping)
        XrayProviderProbeKind.ListenerReadiness -> stringResource(R.string.xray_provider_probe_listener)
        XrayProviderProbeKind.StatApi -> stringResource(R.string.xray_provider_probe_stat_api)
    }
