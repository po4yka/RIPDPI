package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.HandoffOutcome
import com.poyka.ripdpi.core.ProviderRoute
import com.poyka.ripdpi.core.TunnelUpstream
import com.poyka.ripdpi.core.XrayProviderOrchestrator
import com.poyka.ripdpi.core.XrayRuntimeOwner
import com.poyka.ripdpi.core.XrayTunnelHandoff
import com.poyka.ripdpi.data.XrayConfigValidator
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayConfigValidationFinding
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import com.poyka.ripdpi.data.xray.XrayProviderProbeCoordinator
import com.poyka.ripdpi.data.xray.XrayProviderProbeReport
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Single integration seam for the embedded Xray provider in the VPN session.
 *
 * [VpnRuntimeCompositionCoordinator] consults this controller at start / stop /
 * handover. When the DURABLE selection
 * resolves to [VpnProviderKind.Xray], the controller drives the
 * [XrayProviderOrchestrator]. Rejected Xray profiles fail closed; only an
 * explicit Native selection uses the native path.
 *
 * The controller owns the secret discipline: it builds the route + rendered
 * config (secret-bearing) through [XrayProviderRouteBuilder] just before each
 * start and hands the opaque config string to the orchestrator via a
 * `renderedConfigProvider` that reads a held field; the config is cleared right
 * after start so it is never retained across the session. Snapshots produced for
 * telemetry are derived ([XrayProviderSnapshotDeriver]) and carry no secrets.
 *
 * Last config-render findings and the last protect-failure detail are folded
 * into the snapshot so a config-invalid / protect-loop failure renders DISTINCTLY
 * from a tunnel failure on Home / Diagnostics.
 *
 * Lifecycle calls are serialized by the composition coordinator, matching the
 * orchestrator's threading contract.
 */
internal class XrayProviderSessionController(
    private val readSelectedProfile: suspend () -> XraySelectedProfile,
    private val routeBuilder: XrayProviderRouteBuilder,
    private val orchestrator: XrayProviderOrchestrator,
    private val snapshotDeriver: XrayProviderSnapshotDeriver,
    private val probeRunner: XrayProviderDiagnosticsProbeRunner,
    private val startParamsHolder: XrayTunnelStartParamsHolder,
    private val runtimeOwner: XrayRuntimeOwner,
    private val renderedConfigSink: (String?) -> Unit,
    private val lastProtectFailureDetail: () -> String?,
    /**
     * Process-wide probe seam the `:app` Diagnostics surface triggers through.
     * The controller registers [runProbes] here on a successful Xray start and
     * clears it on stop, so a UI-issued probe only reaches a live session.
     */
    private val probeCoordinator: XrayProviderProbeCoordinator? = null,
) {
    private companion object {
        private val log = Logger.withTag("XrayProvider")
    }

    private var lastFindings: List<XrayConfigValidationFinding> = emptyList()
    private var activeProfileName: String? = null
    private var activeProfileProtocol: String? = null
    private var activeProfileSecurity: String? = null
    private var activeConfig: XrayProviderConfig = XrayProviderConfig()
    private var lastStartupFailed = false
    private var hasProviderSnapshot = false

    private val _snapshots = MutableSharedFlow<XrayProviderSnapshot>(replay = 1, extraBufferCapacity = 1)

    /** Latest derived provider snapshot stream (replay 1). Secret-free. */
    val snapshots: SharedFlow<XrayProviderSnapshot> = _snapshots.asSharedFlow()

    /** True when a session is currently bound to the Xray provider. */
    val isActive: Boolean
        get() = orchestrator.currentRoute?.kind == VpnProviderKind.Xray

    /**
     * Attempt to start the Xray provider for the durable selection. Returns the
     * start outcome; [HandoffOutcome.Failed] when no profile / config rejected /
     * engine/tunnel start failed. The composition coordinator treats a
     * [HandoffOutcome.Failed] as a provider-failed start (distinct from a tunnel
     * failure) and does NOT fall through to the native path.
     */
    suspend fun start(params: XrayTunnelStartParams): HandoffOutcome {
        val selected = readSelectedProfile()
        val selection = selected.selection
        if (selection.kind != VpnProviderKind.Xray) {
            return HandoffOutcome.Stopped
        }
        hasProviderSnapshot = true
        return bringUp(selected, params, forceReplacement = false)
    }

    /** Restart the Xray session after a network handover or policy refresh. */
    suspend fun restart(params: XrayTunnelStartParams): HandoffOutcome {
        val selected = readSelectedProfile()
        val selection = selected.selection
        if (selection.kind != VpnProviderKind.Xray) {
            releaseForNativeReplacement()
            return HandoffOutcome.Stopped
        }
        hasProviderSnapshot = true
        return bringUp(selected, params, forceReplacement = true)
    }

    /** Stop the Xray session. Idempotent. */
    suspend fun stop(): HandoffOutcome {
        val outcome = orchestrator.stop()
        if (!isActive) {
            lastStartupFailed = false
            clearStoppedSessionState(clearSnapshotState = true)
        }
        emitSnapshot()
        return outcome
    }

    fun revokeProtection() = orchestrator.revokeProtection()

    fun closeServiceOwner() = orchestrator.closeServiceOwner()

    private fun clearStoppedSessionState(clearSnapshotState: Boolean = false) {
        renderedConfigSink(null)
        startParamsHolder.current = null
        probeCoordinator?.clear()
        if (clearSnapshotState) {
            lastFindings = emptyList()
            activeProfileName = null
            activeProfileProtocol = null
            activeProfileSecurity = null
            activeConfig = XrayProviderConfig()
        }
    }

    /** Transfer the still-established TUN to native composition; retain ownership on stop failure. */
    private suspend fun releaseForNativeReplacement() {
        orchestrator.releaseProviderForNativeReplacement()
        lastStartupFailed = false
        clearStoppedSessionState(clearSnapshotState = true)
        emitSnapshot()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun bringUp(
        selected: XraySelectedProfile,
        params: XrayTunnelStartParams,
        forceReplacement: Boolean,
    ): HandoffOutcome {
        val outcome =
            try {
                when (val resolved = routeBuilder.build(selected.profile)) {
                    is XrayProviderRouteBuilder.Result.Resolved -> {
                        // Publish the per-start tunnel params only after the route is resolved, so
                        // failed validation or cancelled profile load leaves the live session untouched.
                        startParamsHolder.current = params
                        // Hand the secret-bearing config to the orchestrator's provider,
                        // then clear it right after the start returns.
                        renderedConfigSink(resolved.renderedConfig)
                        val result =
                            try {
                                startOrchestrator(resolved.route, forceReplacement)
                            } finally {
                                renderedConfigSink(null)
                            }
                        if (result is HandoffOutcome.Running) {
                            lastStartupFailed = false
                            lastFindings = emptyList()
                            activeConfig = resolved.route.xrayConfig
                            activeProfileName = selected.profile?.name
                            activeProfileProtocol = "vless"
                            activeProfileSecurity =
                                selected.profile
                                    ?.outbound
                                    ?.security
                                    ?.name
                                    ?.lowercase()
                            probeCoordinator?.register(::runProbes)
                        } else {
                            lastStartupFailed = result is HandoffOutcome.Failed
                            probeCoordinator?.clear()
                        }
                        result
                    }

                    is XrayProviderRouteBuilder.Result.Rejected -> {
                        lastStartupFailed = false
                        lastFindings = resolved.findings
                        renderedConfigSink(null)
                        log.e { "xray config rejected: ${resolved.findings.size} finding(s)" }
                        HandoffOutcome.Failed("xray config rejected (${resolved.findings.size} findings)")
                    }

                    XrayProviderRouteBuilder.Result.NoProfile -> {
                        lastStartupFailed = false
                        lastFindings =
                            listOf(
                                XrayConfigValidationFinding.from(
                                    XrayConfigValidator.ValidationError(
                                        XrayConfigValidator.ErrorCode.PROFILE_INVALID,
                                        "profile",
                                        "Selected provider profile is unavailable.",
                                    ),
                                ),
                            )
                        renderedConfigSink(null)
                        log.e { "xray selected but no durable profile persisted" }
                        HandoffOutcome.Failed("no xray profile persisted")
                    }
                }
            } catch (cancellation: CancellationException) {
                if (!isActive) clearStoppedSessionState()
                emitSnapshot()
                throw cancellation
            } catch (error: Exception) {
                if (!isActive) clearStoppedSessionState()
                emitSnapshot()
                throw error
            }
        if (!isActive) {
            clearStoppedSessionState()
        }
        emitSnapshot()
        return outcome
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun startOrchestrator(
        route: ProviderRoute,
        forceReplacement: Boolean,
    ): HandoffOutcome =
        try {
            when {
                forceReplacement -> orchestrator.replace(route)
                orchestrator.currentRoute == null -> orchestrator.start(route)
                else -> orchestrator.handover(route)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // start() throws only on an already-active session; surface as failed.
            currentCoroutineContext().ensureActive()
            HandoffOutcome.Failed("Xray orchestrator start failed")
        }

    /** Derive a privacy-safe snapshot for the current provider state. */
    fun currentSnapshot(): XrayProviderSnapshot {
        val observed = orchestrator.observe()
        val startupFailed = lastStartupFailed && orchestrator.xrayState == VpnProviderState.Stopped
        return snapshotDeriver.derive(
            providerState = if (startupFailed) VpnProviderState.Starting else orchestrator.xrayState,
            config = activeConfig,
            xrayVersion = observed.version,
            listenerReady = if (startupFailed) false else observed.listenerReady,
            isAlive = if (startupFailed) false else observed.alive && !observed.failed,
            configFindings = lastFindings,
            protectFailureDetail = lastProtectFailureDetail(),
            profileName = activeProfileName,
            outboundProtocol = activeProfileProtocol,
            outboundSecurity = activeProfileSecurity,
        )
    }

    fun currentSnapshotOrNull(): XrayProviderSnapshot? =
        currentSnapshot().takeIf {
            hasProviderSnapshot ||
                isActive ||
                lastStartupFailed ||
                lastFindings.isNotEmpty() ||
                lastProtectFailureDetail() != null
        }

    fun currentLocalProxyEndpoint(): LocalProxyEndpoint? =
        orchestrator.currentRoute?.takeIf { isActive }?.let { route ->
            when (val upstream = XrayTunnelHandoff.resolveUpstream(route.kind, route.xrayConfig)) {
                TunnelUpstream.Native -> null
                is TunnelUpstream.Xray -> LocalProxyEndpoint(host = upstream.host, port = upstream.port)
            }
        }

    fun currentGenerationIfActive(): Long? = orchestrator.generation?.takeIf { isActive }

    fun ensureStartAvailable() {
        check(!runtimeOwner.isOccupied) { "Xray native cleanup is still owned" }
    }

    val failedGeneration: Long?
        get() = orchestrator.generation?.takeIf { orchestrator.observe().failed }

    fun ownsGeneration(generation: Long): Boolean = orchestrator.generation == generation

    /** Run the user-triggered provider-path probes against the active provider. */
    fun runProbes(): XrayProviderProbeReport =
        probeRunner.run(
            providerState = orchestrator.xrayState,
            baseSnapshot = currentSnapshot(),
        )

    private fun emitSnapshot() {
        _snapshots.tryEmit(currentSnapshot())
    }

    /** Provider state passthrough for the snapshot deriver tests / callers. */
    val providerState: VpnProviderState
        get() = orchestrator.xrayState
}

/** Secret-bearing snapshot read atomically after journal recovery; never log its profile. */
internal class XraySelectedProfile(
    val selection: XrayProviderSelectionRecord,
    val profile: XrayProfile?,
)
