package com.poyka.ripdpi.failover

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow

/**
 * Exposes the currently active transport protocol kind for display on the UI.
 *
 * Only the `simple` product-flavor source set supplies a real implementation
 * (via [FailoverCoordinator]); every other flavor leaves the [java.util.Optional] empty
 * so the label is absent from the UI.
 *
 * The kind string is a protocol identifier such as `"vless_reality"`, `"hysteria2"`,
 * or `"amneziawg"`. It is NOT a server address or host name — safe to surface on the UI
 * and safe to include verbatim in the diagnostic archive.
 */
interface ActiveTransportProvider {
    /** Emits the raw protocol kind of the currently active transport, or `null` when idle. */
    val activeKind: StateFlow<String?>
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ActiveTransportProviderModule {
    @BindsOptionalOf
    abstract fun bindOptionalActiveTransportProvider(): ActiveTransportProvider
}
