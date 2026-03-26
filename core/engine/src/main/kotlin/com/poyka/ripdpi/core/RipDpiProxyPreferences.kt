package com.poyka.ripdpi.core

import com.poyka.ripdpi.utility.shellSplit

sealed interface RipDpiProxyPreferences {
    fun toNativeConfigJson(): String
}

fun stripRipDpiRuntimeContext(configJson: String): String = RipDpiProxyJsonCodec.stripRuntimeContext(configJson)

fun decodeRipDpiProxyUiPreferences(configJson: String): RipDpiProxyUIPreferences? =
    RipDpiProxyJsonCodec.decodeUiPreferences(configJson)

class RipDpiProxyJsonPreferences(
    private val configJson: String,
    private val hostAutolearnStorePath: String? = null,
    private val networkScopeKey: String? = null,
    private val runtimeContext: RipDpiRuntimeContext? = null,
) : RipDpiProxyPreferences {
    override fun toNativeConfigJson(): String =
        RipDpiProxyJsonCodec.rewriteJson(
            configJson = configJson,
            hostAutolearnStorePath = hostAutolearnStorePath,
            networkScopeKey = networkScopeKey,
            runtimeContext = runtimeContext,
        )
}

class RipDpiProxyCmdPreferences(
    val args: Array<String>,
    private val hostAutolearnStorePath: String? = null,
    val runtimeContext: RipDpiRuntimeContext? = null,
) : RipDpiProxyPreferences {
    constructor(cmd: String) : this(cmdToArgs(cmd))

    constructor(
        cmd: String,
        hostAutolearnStorePath: String?,
        runtimeContext: RipDpiRuntimeContext?,
    ) : this(cmdToArgs(cmd), hostAutolearnStorePath, runtimeContext)

    companion object {
        private fun cmdToArgs(cmd: String): Array<String> {
            val firstArgIndex = cmd.indexOf("-")
            val argsStr = (if (firstArgIndex > 0) cmd.substring(firstArgIndex) else cmd).trim()
            return arrayOf("ripdpi") + shellSplit(argsStr)
        }
    }

    override fun toNativeConfigJson(): String =
        RipDpiProxyJsonCodec.encodeCommandLinePreferences(
            args = args.toList(),
            hostAutolearnStorePath = hostAutolearnStorePath,
            runtimeContext = runtimeContext,
        )
}
