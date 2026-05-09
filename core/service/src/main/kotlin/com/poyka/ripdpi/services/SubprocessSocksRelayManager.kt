@file:Suppress("ComplexCondition", "ReturnCount", "MagicNumber")

package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class SubprocessSocksRelayLaunchSpec(
    val binaryName: String,
    val commandArguments: List<String>,
    val versionArguments: List<String> = emptyList(),
    val runtimeKind: String,
    val upstreamAddress: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val managedClientBridge: ManagedClientSocksBridgeSpec? = null,
    val redactedValues: List<String> = emptyList(),
)

@Singleton
class SubprocessSocksRelayManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val binaryExtractor = SubprocessRelayBinaryExtractor(context)
        private val launchPlanner = SubprocessRelayLaunchPlanner()
        private val outputParser = SubprocessRelayOutputParser()
        private val processSupervisor = SubprocessRelayProcessSupervisor(outputParser)
        private val readinessProbe = SubprocessRelayReadinessProbe()
        private val versionProbe = SubprocessRelayVersionProbe()
        private val bridgeOrchestrator = SubprocessRelayBridgeOrchestrator()
        private val telemetryProjector = SubprocessRelayTelemetryProjector()

        @Volatile private var config: ResolvedRipDpiRelayConfig? = null

        @Volatile private var launchSpec: SubprocessSocksRelayLaunchSpec? = null

        @Volatile private var lastError: String? = null

        @Volatile private var runtimeVersion: String? = null

        @Volatile private var lastFailureClass: String? = null

        @Volatile private var runtimeStateOverride: String? = null

        suspend fun start(
            config: ResolvedRipDpiRelayConfig,
            spec: SubprocessSocksRelayLaunchSpec,
        ) {
            withContext(Dispatchers.IO) {
                stopInternal()
                runtimeStateOverride = "starting"
                val binary = binaryExtractor.extract(spec.binaryName)
                this@SubprocessSocksRelayManager.config = config
                this@SubprocessSocksRelayManager.launchSpec = spec
                lastError = null
                lastFailureClass = null
                processSupervisor.start(
                    processBuilder = launchPlanner.buildMainProcess(binary, spec),
                    spec = spec,
                    onOutputEvent = ::handleProcessOutputEvent,
                )
                runtimeVersion = versionProbe.probe(binary, spec)
            }
            val managedClientBridgeSpec = spec.managedClientBridge
            if (managedClientBridgeSpec != null) {
                val listener =
                    readinessProbe.waitForManagedClientListener(
                        bridgeSpec = managedClientBridgeSpec,
                        currentListener = bridgeOrchestrator::currentManagedClientListener,
                        isRunning = processSupervisor::isRunning,
                        onFailure = ::markFailed,
                    )
                withContext(Dispatchers.IO) {
                    bridgeOrchestrator.startBridge(
                        config = config,
                        bridgeSpec = managedClientBridgeSpec,
                        listener = listener,
                    )
                }
            }
            readinessProbe.waitUntilReady(
                config = config,
                isRunning = processSupervisor::isRunning,
                onFailure = ::markFailed,
            )
            runtimeStateOverride = null
        }

        suspend fun waitForExit(): Int =
            withContext(Dispatchers.IO) {
                processSupervisor.waitForExit()
            }

        suspend fun pollTelemetry(): NativeRuntimeSnapshot =
            withContext(Dispatchers.IO) {
                telemetryProjector.project(
                    SubprocessRelayTelemetryInputs(
                        config = config,
                        launchSpec = launchSpec,
                        running = processSupervisor.isRunning(),
                        runtimeVersion = runtimeVersion,
                        lastError = lastError,
                        lastFailureClass = lastFailureClass,
                        runtimeStateOverride = runtimeStateOverride,
                    ),
                )
            }

        suspend fun stop() {
            withContext(Dispatchers.IO) {
                stopInternal()
            }
        }

        private fun stopInternal() {
            bridgeOrchestrator.reset()
            runtimeVersion = null
            runtimeStateOverride = null
            config = null
            launchSpec = null
            processSupervisor.stop()?.let { errorMessage ->
                lastError = errorMessage
            }
        }

        fun noteRestarting(reason: String) {
            runtimeStateOverride = "restarting"
            lastError = reason
        }

        private fun handleProcessOutputEvent(event: SubprocessRelayOutputEvent) {
            when (event) {
                is SubprocessRelayOutputEvent.ManagedClientListener -> {
                    bridgeOrchestrator.noteManagedClientListener(event.listener)
                }

                is SubprocessRelayOutputEvent.Ready -> {
                    if (runtimeVersion.isNullOrBlank()) {
                        runtimeVersion = event.version
                    }
                }

                is SubprocessRelayOutputEvent.Error -> {
                    lastFailureClass = event.failureClass
                    lastError = event.message
                    runtimeStateOverride = "failed"
                }

                is SubprocessRelayOutputEvent.PlainError -> {
                    lastError = event.message
                }
            }
        }

        private fun markFailed(message: String) {
            lastError = message
            runtimeStateOverride = "failed"
        }
    }
