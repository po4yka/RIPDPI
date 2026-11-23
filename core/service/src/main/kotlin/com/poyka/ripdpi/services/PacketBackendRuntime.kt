package com.poyka.ripdpi.services

import android.content.Context
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal interface PacketBackendRuntime {
    val socketPath: String?
    val socketAvailability: Flow<Boolean?> get() = flowOf(null)

    fun allowsExternalOperations(settings: AppSettings): Boolean

    fun acceptsCommandLine(arguments: String): Boolean = true

    suspend fun prepare(
        context: Context,
        mode: Mode,
        settings: AppSettings,
        preferences: RipDpiProxyPreferences,
    ): RipDpiProxyPreferences

    fun monitorAvailability(
        scope: CoroutineScope,
        onUnavailable: suspend () -> Unit,
    ) = Unit

    fun monitor(
        scope: CoroutineScope,
        onFailure: suspend (IOException) -> Unit,
    )

    fun stopFor(mode: Mode)

    fun stopOnDestroy(mode: Mode)
}

/** Runs packet strategies in the app's VPN or proxy. */
internal object LocalPacketBackendRuntime : PacketBackendRuntime {
    override val socketPath: String? = null

    override fun allowsExternalOperations(settings: AppSettings): Boolean = false

    override suspend fun prepare(
        context: Context,
        mode: Mode,
        settings: AppSettings,
        preferences: RipDpiProxyPreferences,
    ): RipDpiProxyPreferences = preferences

    override fun monitor(
        scope: CoroutineScope,
        onFailure: suspend (IOException) -> Unit,
    ) = Unit

    override fun stopFor(mode: Mode) = Unit

    override fun stopOnDestroy(mode: Mode) = Unit
}

internal fun PacketBackendRuntime.monitorSession(
    scope: CoroutineScope,
    reload: suspend () -> Boolean,
    onFailure: suspend () -> Unit,
) {
    val onUnavailable: suspend () -> Unit = {
        // Reload can stop the process and cancel its watcher.
        scope.launch {
            val recovered =
                try {
                    reload()
                } catch (error: CancellationException) {
                    throw error
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception,
                ) {
                    Logger.w(error) { "Packet backend fallback failed" }
                    false
                }
            if (!recovered) onFailure()
        }
    }
    monitorAvailability(scope, onUnavailable)
    monitor(scope) { error ->
        Logger.w(error) { "Packet backend became unavailable" }
        onUnavailable()
    }
}

/** Holds packet preparation until the reload's policy and session are accepted. */
internal class DeferredPacketBackend : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DeferredPacketBackend>

    var prepare: suspend (ConnectionPolicyResolution) -> ConnectionPolicyResolution = { it }
}
