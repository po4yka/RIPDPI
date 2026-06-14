package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ProcessGlobalStrategyEngineBindings
import com.poyka.ripdpi.core.StrategyEngineBindings

interface StrategyConfigRuntime {
    fun validateLuaScript(path: String): String?

    /**
     * Loads the Lua script at [path], confined to the absolute `<filesDir>/lua`
     * [baseDir] jail (seeded first-wins from [baseDir]). Returns `null` on
     * success or an error string.
     */
    fun loadLuaScript(
        baseDir: String,
        path: String,
    ): String?

    fun listLuaStrategies(): Array<String>

    fun validateStrategyConfigText(configText: String): String?

    fun reloadConfig(): String?
}

class NativeStrategyConfigRuntime(
    private val bindings: StrategyEngineBindings = ProcessGlobalStrategyEngineBindings(),
) : StrategyConfigRuntime {
    override fun validateLuaScript(path: String): String? = bindings.luaValidateScript(path)

    override fun loadLuaScript(
        baseDir: String,
        path: String,
    ): String? = bindings.luaLoadScript(baseDir, path)

    override fun listLuaStrategies(): Array<String> = bindings.luaListStrategies() ?: emptyArray()

    override fun validateStrategyConfigText(configText: String): String? =
        bindings.validateStrategyConfigText(configText)

    override fun reloadConfig(): String? = bindings.luaReloadConfig()
}
