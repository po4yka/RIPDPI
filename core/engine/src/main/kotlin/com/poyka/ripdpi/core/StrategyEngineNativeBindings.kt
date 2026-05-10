package com.poyka.ripdpi.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StrategyProbeResultDto(
    @SerialName("strategy_id")
    val strategyId: String,
    val domain: String,
    val success: Boolean,
    @SerialName("latency_ms")
    val latencyMs: Long,
)

interface StrategyEngineBindings {
    fun luaLoadScript(path: String): String?

    fun luaReloadConfig(): String?

    fun luaListStrategies(): Array<String>

    fun luaValidateScript(path: String): String?

    fun validateStrategyConfigText(configText: String): String?

    fun injectProbeResults(results: Array<StrategyProbeResultDto>): String?
}

class StrategyEngineNativeBindings : StrategyEngineBindings {
    init {
        RipDpiNativeLoader.ensureLoaded()
    }

    external override fun luaLoadScript(path: String): String?

    external override fun luaReloadConfig(): String?

    external override fun luaListStrategies(): Array<String>

    external override fun luaValidateScript(path: String): String?

    external override fun validateStrategyConfigText(configText: String): String?

    override fun injectProbeResults(results: Array<StrategyProbeResultDto>): String? =
        injectProbeResultsJson(StrategyProbeResultJson.encodeToString(results.toList()))

    private external fun injectProbeResultsJson(resultsJson: String): String?
}

private val StrategyProbeResultJson =
    Json {
        encodeDefaults = true
    }
