package com.poyka.ripdpi.core

interface StrategyEngineBindings {
    fun luaLoadScript(path: String): String?

    fun luaReloadConfig(): String?

    fun luaListStrategies(): Array<String>

    fun luaValidateScript(path: String): String?
}

class StrategyEngineNativeBindings : StrategyEngineBindings {
    init {
        RipDpiNativeLoader.ensureLoaded()
    }

    external override fun luaLoadScript(path: String): String?

    external override fun luaReloadConfig(): String?

    external override fun luaListStrategies(): Array<String>

    external override fun luaValidateScript(path: String): String?
}
