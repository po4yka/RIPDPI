package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.StrategyEngineBindings
import com.poyka.ripdpi.core.StrategyEngineNativeBindings

interface StrategyConfigRuntime {
    fun validateLuaScript(path: String): String?

    fun loadLuaScript(path: String): String?

    fun listLuaStrategies(): Array<String>

    fun validateStrategyConfigText(configText: String): String?

    fun reloadConfig(): String?
}

class NativeStrategyConfigRuntime(
    private val bindings: StrategyEngineBindings = StrategyEngineNativeBindings(),
) : StrategyConfigRuntime {
    override fun validateLuaScript(path: String): String? = bindings.luaValidateScript(path)

    override fun loadLuaScript(path: String): String? = bindings.luaLoadScript(path)

    override fun listLuaStrategies(): Array<String> = bindings.luaListStrategies()

    override fun validateStrategyConfigText(configText: String): String? =
        bindings.validateStrategyConfigText(configText)

    override fun reloadConfig(): String? = bindings.luaReloadConfig()
}
