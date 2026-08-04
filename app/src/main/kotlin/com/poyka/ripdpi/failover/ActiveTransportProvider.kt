package com.poyka.ripdpi.failover

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow

/**
 * Exposes privacy-safe details about the currently active transport for display on the UI.
 *
 * Only the `simple` product-flavor source set supplies a real implementation;
 * every other flavor leaves the [java.util.Optional] empty
 * so the label is absent from the UI.
 *
 * The descriptor contains protocol identifiers only. It never contains a server address,
 * host name, or credential, so it is safe to surface in the UI and diagnostic archive.
 */
data class ActiveTransportDescriptor(
    val protocolKind: String,
    val vlessTransport: String? = null,
)

interface ActiveTransportProvider {
    /** Emits the active transport descriptor, or `null` when idle. */
    val activeTransport: StateFlow<ActiveTransportDescriptor?>
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ActiveTransportProviderModule {
    @BindsOptionalOf
    abstract fun bindOptionalActiveTransportProvider(): ActiveTransportProvider
}
