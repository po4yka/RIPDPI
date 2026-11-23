package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.withoutPacketStrategies
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.utility.shellSplit
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object PacketBackendRuntimeModule {
    @Provides
    @Singleton
    fun providePacketBackendRuntime(
        manager: RootHelperManager,
        settings: AppSettingsRepository,
    ): PacketBackendRuntime = ExtendedPacketBackendRuntime(manager, settings)
}

internal class ExtendedPacketBackendRuntime(
    private val manager: RootHelperManager,
    private val settingsRepository: AppSettingsRepository,
) : PacketBackendRuntime {
    override val socketPath: String? get() = manager.socketPath.takeIf { manager.state.value == RootHelperState.Ready }
    override val socketAvailability = manager.state.map { it == RootHelperState.Ready }

    override fun allowsExternalOperations(settings: AppSettings): Boolean = settings.rootModeAuthorized

    override fun acceptsCommandLine(arguments: String): Boolean = !isNfqwsCommand(arguments)

    override suspend fun prepare(
        context: Context,
        mode: Mode,
        settings: AppSettings,
        preferences: RipDpiProxyPreferences,
    ): RipDpiProxyPreferences =
        withContext(Dispatchers.IO) {
            val uiPreferences = preferences as? RipDpiProxyUIPreferences
            val nfqws = settings.enableCmdSettings && isNfqwsCommand(settings.cmdArgs)
            val usesHelper = nfqws || uiPreferences?.usesHelper() == true
            manager.claimedBy = mode
            val authorized = settings.rootModeAuthorized && settingsRepository.snapshot().rootModeAuthorized
            val path =
                if (usesHelper || !authorized) {
                    manager.syncRootMode(context, authorized)
                } else {
                    manager.stopForOrdinaryStrategy()
                    null
                }
            if (uiPreferences == null) return@withContext preferences
            if (path == null) return@withContext uiPreferences.withOrdinaryPacketStrategies()
            if (!nfqws && !uiPreferences.meetsPacketRequirements(manager, settings.ipv6Enable)) {
                manager.stopAfterFailure()
                return@withContext uiPreferences.withOrdinaryPacketStrategies()
            }
            if (nfqws) {
                try {
                    manager.syncNfqws(context, settings)
                } catch (_: IOException) {
                    manager.stopAfterFailure()
                    return@withContext uiPreferences.withOrdinaryPacketStrategies()
                }
            } else if (manager.nfqwsActive) {
                manager.syncNfqws(context, settings)
            }
            val active = if (nfqws) uiPreferences.withoutPacketStrategies() else uiPreferences
            return@withContext active.withSessionOverrides(
                runtimeContext = active.runtimeContext?.copy(strategyChainYaml = null, luaScriptBaseDir = null),
                packetBackendRequested = true,
                packetBackendSocketPath = path,
            )
        }

    override fun monitorAvailability(
        scope: CoroutineScope,
        onUnavailable: suspend () -> Unit,
    ) = manager.monitorHelper(scope, settingsRepository.settings.map { it.rootModeAuthorized }, onUnavailable)

    override fun monitor(
        scope: CoroutineScope,
        onFailure: suspend (IOException) -> Unit,
    ) = manager.monitorNfqws(scope, onFailure)

    override fun stopFor(mode: Mode) = manager.stopFor(mode)

    override fun stopOnDestroy(mode: Mode) = manager.stopOnDestroy(mode)
}

private fun isNfqwsCommand(arguments: String): Boolean = shellSplit(arguments).firstOrNull() == "nfqws2"

private val helperTcpSteps =
    setOf(
        TcpChainStepKind.SeqOverlap,
        TcpChainStepKind.MultiDisorder,
        TcpChainStepKind.Fake,
        TcpChainStepKind.FakeSplit,
        TcpChainStepKind.FakeDisorder,
        TcpChainStepKind.HostFake,
        TcpChainStepKind.FakeRst,
        TcpChainStepKind.IpFrag2,
    )

private fun RipDpiProxyUIPreferences.usesHelper(): Boolean =
    chains.tcpSteps.any { it.kind in helperTcpSteps } || chains.udpSteps.any { it.kind == UdpChainStepKind.IpFrag2Udp }

private val requiredHelperTcpSteps =
    setOf(TcpChainStepKind.FakeRst, TcpChainStepKind.IpFrag2, TcpChainStepKind.MultiDisorder)

private fun RipDpiProxyUIPreferences.meetsPacketRequirements(
    manager: RootHelperManager,
    ipv6: Boolean,
): Boolean {
    val packetOwnedTcp =
        chains.tcpSteps.any {
            it.kind == TcpChainStepKind.MultiDisorder ||
                it.kind == TcpChainStepKind.IpFrag2
        }
    val fragmentedUdp = chains.udpSteps.any { it.kind == UdpChainStepKind.IpFrag2Udp }
    return (!packetOwnedTcp && !fragmentedUdp) || manager.supportsRawPackets(ipv6, tcpRepair = packetOwnedTcp)
}

private fun RipDpiProxyUIPreferences.withOrdinaryPacketStrategies(): RipDpiProxyUIPreferences {
    val tcp = chains.tcpSteps.filterNot { it.kind in requiredHelperTcpSteps }
    val udp = chains.udpSteps.filterNot { it.kind == UdpChainStepKind.IpFrag2Udp }
    if (tcp == chains.tcpSteps && udp == chains.udpSteps && !fakePackets.md5sig) return this
    return withSessionOverrides(
        chains = chains.copy(tcpSteps = tcp, udpSteps = udp),
        fakePackets = fakePackets.copy(md5sig = false),
    )
}
