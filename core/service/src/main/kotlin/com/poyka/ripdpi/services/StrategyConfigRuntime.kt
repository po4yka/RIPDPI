package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.StrategyEngineBindings
import com.poyka.ripdpi.core.StrategyEngineNativeBindings

interface StrategyConfigRuntime {
    fun validateLuaScript(path: String): String?

    fun loadLuaScript(path: String): String?

    fun reloadConfig(): String?
}

class NativeStrategyConfigRuntime(
    private val bindings: StrategyEngineBindings = StrategyEngineNativeBindings(),
) : StrategyConfigRuntime {
    override fun validateLuaScript(path: String): String? = bindings.luaValidateScript(path)

    override fun loadLuaScript(path: String): String? = bindings.luaLoadScript(path)

    override fun reloadConfig(): String? = bindings.luaReloadConfig()
}
