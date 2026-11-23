package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.proto.AppSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object PacketBackendRuntimeModule {
    @Provides
    @Singleton
    fun providePacketBackendRuntime(manager: RootHelperManager): PacketBackendRuntime =
        ExtendedPacketBackendRuntime(manager)
}

private class ExtendedPacketBackendRuntime(
    private val manager: RootHelperManager,
) : PacketBackendRuntime {
    override val socketPath: String? get() = manager.socketPath
    override val ownsPacketStrategies: Boolean get() = manager.nfqwsActive

    override fun allowsExternalOperations(settings: AppSettings): Boolean = settings.rootModeAuthorized

    override suspend fun prepare(
        context: Context,
        mode: Mode,
        settings: AppSettings,
    ) {
        manager.claimedBy = mode
        manager.syncRootMode(context, settings.rootModeAuthorized)
        manager.syncNfqws(context, settings)
    }

    override fun monitor(
        scope: CoroutineScope,
        onFailure: suspend (IOException) -> Unit,
    ) = manager.monitorNfqws(scope, onFailure)

    override fun stopFor(mode: Mode) = manager.stopFor(mode)

    override fun stopOnDestroy(mode: Mode) = manager.stopOnDestroy(mode)
}
