package com.poyka.ripdpi.failover

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow

/**
 * Exposes privacy-safe details about the currently active transport for display on the UI.
 *
 * No implementation is bound: the [java.util.Optional] is empty and the label is absent from
 * the UI. SVC-1795015549149698 decides whether a full-app transport failover fills it.
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
