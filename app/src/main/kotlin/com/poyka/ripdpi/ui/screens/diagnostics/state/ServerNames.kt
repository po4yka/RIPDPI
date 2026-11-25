package com.poyka.ripdpi.ui.screens.diagnostics.state

import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.exitName
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.services.opaqueRelayProfileToken
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** Names the saved server behind a relay profile token on this device. Names never enter the event store or exports. */
fun interface ServerNames {
    fun nameOf(event: DiagnosticEvent): String?

    companion object {
        val None = ServerNames { null }
    }
}

/** Relay health events carry `opaqueRelayProfileToken(memberId)`; the saved selector members give the names. */
@Singleton
internal class SavedServerNames
    @Inject
    constructor(
        groups: ProxyGroupRepository,
        @ApplicationScope scope: CoroutineScope,
    ) : ServerNames {
        private val names: StateFlow<Map<String, String>> =
            groups
                .groups()
                .map { saved ->
                    saved
                        .flatMap { it.members }
                        .associate { opaqueRelayProfileToken(it.id) to it.exitName() }
                }.catch {
                    if (it is CancellationException) throw it
                    // An unreadable catalog leaves events unnamed, as before.
                    emit(emptyMap())
                }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

        override fun nameOf(event: DiagnosticEvent): String? = event.relayProfileToken?.let(names.value::get)
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ServerNamesModule {
    @Binds
    abstract fun bindServerNames(names: SavedServerNames): ServerNames
}
