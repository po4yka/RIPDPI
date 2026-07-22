package com.poyka.ripdpi.ui.screens.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

internal data class StrategyConfigDraftState(
    val initialized: Boolean = false,
    val source: StrategyConfigSource = StrategyConfigSource.BuiltIn,
    val configText: String = "",
    val luaPath: String = "",
    val luaFunction: String = "",
)

/** Retains the user-editable strategy draft across Activity recreation without putting it in a Bundle. */
@HiltViewModel
class StrategyConfigDraftViewModel
    @Inject
    constructor() : ViewModel() {
        private val mutableState = MutableStateFlow(StrategyConfigDraftState())
        internal val state = mutableState.asStateFlow()

        internal fun synchronizeBuiltIn(chainDsl: String) {
            mutableState.update { current ->
                if (!current.initialized || current.source == StrategyConfigSource.BuiltIn) {
                    current.copy(
                        initialized = true,
                        configText = chainDsl.boundedUtf8(StrategyConfigMaxImportBytes),
                    )
                } else {
                    current
                }
            }
        }

        internal fun selectSource(
            source: StrategyConfigSource,
            builtInText: String,
        ) {
            mutableState.update { current ->
                current.copy(
                    initialized = true,
                    source = source,
                    configText =
                        if (source == StrategyConfigSource.BuiltIn) {
                            builtInText.boundedUtf8(StrategyConfigMaxImportBytes)
                        } else {
                            current.configText
                        },
                )
            }
        }

        internal fun updateConfigText(value: String) {
            mutableState.update {
                it.copy(
                    initialized = true,
                    configText = value.boundedUtf8(StrategyConfigMaxImportBytes),
                )
            }
        }

        internal fun applyImportedConfig(value: String) {
            mutableState.update {
                it.copy(
                    initialized = true,
                    source = StrategyConfigSource.CustomYaml,
                    configText = value.boundedUtf8(StrategyConfigMaxImportBytes),
                )
            }
        }

        internal fun updateLuaPath(value: String) {
            mutableState.update { it.copy(luaPath = value) }
        }

        internal fun updateLuaFunction(value: String) {
            mutableState.update { it.copy(luaFunction = value) }
        }
    }
