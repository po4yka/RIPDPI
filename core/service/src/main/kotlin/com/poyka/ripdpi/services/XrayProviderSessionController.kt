package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.HandoffOutcome
import com.poyka.ripdpi.core.ProviderRoute
import com.poyka.ripdpi.core.XrayProviderOrchestrator
import com.poyka.ripdpi.core.XrayRuntimeOwner
import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayConfigValidationFinding
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import com.poyka.ripdpi.data.xray.XrayProviderProbeCoordinator
import com.poyka.ripdpi.data.xray.XrayProviderProbeReport
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
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
 * ([XrayProviderSelectionStore]) resolves to [VpnProviderKind.Xray] AND a usable
 * route renders, the controller drives the [XrayProviderOrchestrator]; otherwise
 * the composition coordinator runs the EXISTING native path BYTE-IDENTICAL.
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
    private val selectionStore: XrayProviderSelectionStore,
    private val profileStore: DurableXrayProfileStore,
    private val routeBuilder: XrayProviderRouteBuilder,
    private val orchestrator: XrayProviderOrchestrator,
    private val snapshotDeriver: XrayProviderSnapshotDeriver,
    private val probeRunner: XrayProviderDiagnosticsProbeRunner,
    private val startParamsHolder: XrayTunnelStartParamsHolder,
    private val runtimeOwner: XrayRuntimeOwner,
    private val renderedConfigSink: (String?) -> Unit,
    private val lastProtectFailureDetail: () -> String?,
    private val recoverPendingProfileMutations: suspend () -> Unit = {},
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

    private val _snapshots = MutableSharedFlow<XrayProviderSnapshot>(replay = 1, extraBufferCapacity = 1)

    /** Latest derived provider snapshot stream (replay 1). Secret-free. */
    val snapshots: SharedFlow<XrayProviderSnapshot> = _snapshots.asSharedFlow()

    /** True when a session is currently bound to the Xray provider. */
    val isActive: Boolean
        get() = orchestrator.currentRoute?.kind == VpnProviderKind.Xray

    /**
     * Whether the durable selection currently chooses the Xray provider. The
     * composition coordinator calls this BEFORE starting so it can keep the
     * native path untouched when Native is selected.
     */
    suspend fun isXraySelected(): Boolean {
        recoverPendingProfileMutations()
        return selectionStore.current().kind == VpnProviderKind.Xray
    }

    /**
     * Attempt to start the Xray provider for the durable selection. Returns the
     * start outcome; [HandoffOutcome.Failed] when no profile / config rejected /
     * engine/tunnel start failed. The composition coordinator treats a
     * [HandoffOutcome.Failed] as a provider-failed start (distinct from a tunnel
     * failure) and does NOT fall through to the native path.
     */
    suspend fun start(params: XrayTunnelStartParams): HandoffOutcome {
        recoverPendingProfileMutations()
        val selection = selectionStore.current()
        if (selection.kind != VpnProviderKind.Xray) {
            return HandoffOutcome.Stopped
        }
        return bringUp(selection.activeProfileId, params)
    }

    /** Restart the Xray session after a network handover (dual restart). */
    suspend fun restart(params: XrayTunnelStartParams): HandoffOutcome {
        recoverPendingProfileMutations()
        val selection = selectionStore.current()
        orchestrator.releaseProviderForNativeReplacement()
        clearStoppedSessionState()
        emitSnapshot()
        if (selection.kind != VpnProviderKind.Xray) {
            return HandoffOutcome.Stopped
        }
        return bringUp(selection.activeProfileId, params)
    }

    /** Stop the Xray session. Idempotent. */
    suspend fun stop(): HandoffOutcome {
        val outcome = orchestrator.stop()
        if (!isActive) clearStoppedSessionState()
        emitSnapshot()
        return outcome
    }

    fun revokeProtection() = orchestrator.revokeProtection()

    fun closeServiceOwner() = orchestrator.closeServiceOwner()

    private fun clearStoppedSessionState() {
        renderedConfigSink(null)
        startParamsHolder.current = null
        probeCoordinator?.clear()
    }

    /** Transfer the still-established TUN to native composition; retain ownership on stop failure. */
    suspend fun releaseForNativeReplacement() {
        orchestrator.releaseProviderForNativeReplacement()
        clearStoppedSessionState()
        emitSnapshot()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun bringUp(
        profileId: String,
        params: XrayTunnelStartParams,
    ): HandoffOutcome {
        // Publish the per-start tunnel params so XrayManagedTunnel can read them
        // synchronously when the orchestrator points the tunnel at the inbound.
        startParamsHolder.current = params
        val outcome =
            try {
                when (val resolved = routeBuilder.build(profileId)) {
                    is XrayProviderRouteBuilder.Result.Resolved -> {
                        lastFindings = emptyList()
                        cacheProfileLabels(profileId)
                        activeConfig = resolved.route.xrayConfig
                        // Hand the secret-bearing config to the orchestrator's provider,
                        // then clear it right after the start returns.
                        renderedConfigSink(resolved.renderedConfig)
                        val result =
                            try {
                                startOrchestrator(resolved.route)
                            } finally {
                                renderedConfigSink(null)
                            }
                        if (result is HandoffOutcome.Running) {
                            probeCoordinator?.register(::runProbes)
                        } else {
                            probeCoordinator?.clear()
                        }
                        result
                    }

                    is XrayProviderRouteBuilder.Result.Rejected -> {
                        lastFindings = resolved.findings
                        renderedConfigSink(null)
                        log.e { "xray config rejected: ${resolved.findings.size} finding(s)" }
                        HandoffOutcome.Failed("xray config rejected (${resolved.findings.size} findings)")
                    }

                    XrayProviderRouteBuilder.Result.NoProfile -> {
                        lastFindings = emptyList()
                        renderedConfigSink(null)
                        log.e { "xray selected but no durable profile persisted for id=$profileId" }
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
    private suspend fun startOrchestrator(route: ProviderRoute): HandoffOutcome =
        try {
            orchestrator.start(route)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // start() throws only on an already-active session; surface as failed.
            currentCoroutineContext().ensureActive()
            HandoffOutcome.Failed("Xray orchestrator start failed")
        }

    private suspend fun cacheProfileLabels(profileId: String) {
        val profile = profileStore.load(profileId) ?: return
        activeProfileName = profile.name
        activeProfileProtocol = "vless"
        activeProfileSecurity =
            profile.outbound.security.name
                .lowercase()
    }

    /** Derive a privacy-safe snapshot for the current provider state. */
    fun currentSnapshot(): XrayProviderSnapshot {
        val observed = orchestrator.observe()
        return snapshotDeriver.derive(
            providerState = orchestrator.xrayState,
            config = activeConfig,
            xrayVersion = observed.version,
            listenerReady = observed.listenerReady,
            isAlive = observed.alive && !observed.failed,
            configFindings = lastFindings,
            protectFailureDetail = lastProtectFailureDetail(),
            profileName = activeProfileName,
            outboundProtocol = activeProfileProtocol,
            outboundSecurity = activeProfileSecurity,
        )
    }

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
