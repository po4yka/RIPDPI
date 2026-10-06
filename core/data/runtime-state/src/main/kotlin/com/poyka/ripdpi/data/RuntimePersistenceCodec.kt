package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject

/** New records use only this composite schema. Legacy flat authority is a one-way migration input. */
@Serializable
internal data class RuntimePersistenceEnvelope(
    val schemaVersion: Int,
    val state: PauseAuthorityState,
)

internal object RuntimePersistenceCodec {
    private const val CurrentSchema = 3
    private const val PreviousCompositeSchema = 2
    private val json =
        kotlinx.serialization.json.Json(com.poyka.ripdpi.serialization.RipDpiContractJson) {
            ignoreUnknownKeys = false
        }

    fun encode(state: PauseAuthorityState): String =
        json.encodeToString(RuntimePersistenceEnvelope(CurrentSchema, state))

    fun decode(value: String): Pair<PauseAuthorityState, Boolean> {
        val fields = json.parseToJsonElement(value).jsonObject
        val decoded =
            if ("schemaVersion" in fields) {
                val envelope = json.decodeFromString<RuntimePersistenceEnvelope>(value)
                when (envelope.schemaVersion) {
                    CurrentSchema -> envelope.state to false
                    PreviousCompositeSchema -> envelope.state.copy(command = null) to true
                    else -> error("Unsupported runtime persistence schema")
                }
            } else {
                check(fields.keys.all(LegacyFields::contains) && fields.keys.containsAll(RequiredLegacyFields)) {
                    "Malformed legacy runtime persistence"
                }
                val legacy = json.decodeFromString<LegacyPauseAuthorityState>(value)
                PauseAuthorityState(
                    legacy.generation,
                    legacy.pause,
                    legacy.lastMutationId,
                    legacy.lastMutationAuthority,
                    legacy.desired,
                    legacy.desiredMode,
                    ProfileUtilityState.empty(),
                ) to true
            }
        validate(decoded.first)
        return decoded
    }

    private fun validate(state: PauseAuthorityState) {
        require(state.generation >= 0) { "Malformed runtime generation" }
        require(state.lastMutationAuthority == null || state.lastMutationAuthority.generation in 0..state.generation)
        require(state.desiredMode == null || Mode.entries.any { it.preferenceValue == state.desiredMode })
        require(state.desired != DesiredRuntimeState.Running || state.desiredMode != null)
        require(state.desired != DesiredRuntimeState.Paused || state.pause != null)
        state.command?.let { command ->
            require(command.generation == state.generation)
            when (val phase = command.phase) {
                RuntimeActivationPhase.Unbound -> {
                    require(command.activatesProfile())
                }

                RuntimeActivationPhase.Terminated -> {
                    Unit
                }

                is RuntimeActivationPhase.Pending -> {
                    require(Mode.entries.any { it.preferenceValue == phase.mode })
                }

                is RuntimeActivationPhase.Claimed -> {
                    require(Mode.entries.any { it.preferenceValue == phase.identity.mode })
                    val profileActivation = command.activatesProfile()
                    require(
                        phase.identity.mode == state.desiredMode ||
                            (
                                profileActivation && state.desired == DesiredRuntimeState.Stopped &&
                                    state.desiredMode == null
                            ),
                    )
                }

                is RuntimeActivationPhase.Applied -> {
                    require(phase.identity.mode == state.desiredMode)
                }
            }
        }
        state.pause?.let { pause ->
            require(pause.generation == state.generation && pause.token.isNotBlank())
            require(Mode.entries.any { it.preferenceValue == pause.mode })
            require(pause.createdWallMillis > 0 && pause.createdElapsedMillis >= 0 && pause.bootCount >= 0)
            require(pause.deadlineWallMillis > pause.createdWallMillis)
            require(pause.deadlineElapsedMillis > pause.createdElapsedMillis)
            require(state.desired == DesiredRuntimeState.Paused || state.desired == DesiredRuntimeState.LegacyUnknown)
        }
    }

    private val RequiredLegacyFields = setOf("generation")
    private val LegacyFields =
        RequiredLegacyFields + setOf("pause", "lastMutationId", "lastMutationAuthority", "desired", "desiredMode")
}

@Serializable
private data class LegacyPauseAuthorityState(
    val generation: Long,
    val pause: PauseIntent?,
    val lastMutationId: String?,
    val lastMutationAuthority: PauseAuthorityRef? = null,
    val desired: DesiredRuntimeState = DesiredRuntimeState.LegacyUnknown,
    val desiredMode: String? = null,
)
