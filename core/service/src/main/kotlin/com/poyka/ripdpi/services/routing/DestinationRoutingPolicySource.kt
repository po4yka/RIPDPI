package com.poyka.ripdpi.services.routing

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Immutable snapshot of the current persisted destination-route authority. */
sealed interface DestinationRoutingPolicySnapshot {
    data class Available(
        val policy: DestinationRoutingPolicy,
    ) : DestinationRoutingPolicySnapshot

    data class Unavailable(
        val reason: String,
    ) : DestinationRoutingPolicySnapshot
}

/** Reads the same ordered Room rules that feed destination routing. */
fun interface DestinationRoutingPolicySource {
    suspend fun snapshot(): DestinationRoutingPolicySnapshot
}

@Singleton
internal class RoomDestinationRoutingPolicySource internal constructor(
    private val loadRules: suspend () -> List<RuleEntity>,
) : DestinationRoutingPolicySource {
    @Inject
    constructor(ruleRepository: RuleRepository) : this({ ruleRepository.enabledRules().first() })

    override suspend fun snapshot(): DestinationRoutingPolicySnapshot {
        val result = runCatching { loadRules() }
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        if (error != null) {
            Logger.w(error) { "Destination routing snapshot unavailable" }
            return DestinationRoutingPolicySnapshot.Unavailable("rule_source_unavailable")
        }
        val rules = result.getOrThrow()
        return when (val compiled = DestinationRoutingPolicyCompiler.compile(rules)) {
            is DestinationRoutingPolicyCompileResult.Success -> {
                DestinationRoutingPolicySnapshot.Available(compiled.policy)
            }

            is DestinationRoutingPolicyCompileResult.Failure -> {
                DestinationRoutingPolicySnapshot.Unavailable(
                    reason = "${compiled.error.code}:${compiled.error.ruleId ?: "snapshot"}",
                )
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DestinationRoutingPolicySourceModule {
    @Binds
    @Singleton
    abstract fun bindDestinationRoutingPolicySource(
        source: RoomDestinationRoutingPolicySource,
    ): DestinationRoutingPolicySource
}
