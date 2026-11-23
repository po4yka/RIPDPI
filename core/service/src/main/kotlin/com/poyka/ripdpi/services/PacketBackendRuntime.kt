package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CoroutineScope
import java.io.IOException

internal interface PacketBackendRuntime {
    val socketPath: String?
    val ownsPacketStrategies: Boolean

    fun allowsExternalOperations(settings: AppSettings): Boolean

    suspend fun prepare(
        context: Context,
        mode: Mode,
        settings: AppSettings,
    )

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
    override val ownsPacketStrategies = false

    override fun allowsExternalOperations(settings: AppSettings): Boolean = false

    override suspend fun prepare(
        context: Context,
        mode: Mode,
        settings: AppSettings,
    ) = Unit

    override fun monitor(
        scope: CoroutineScope,
        onFailure: suspend (IOException) -> Unit,
    ) = Unit

    override fun stopFor(mode: Mode) = Unit

    override fun stopOnDestroy(mode: Mode) = Unit
}
