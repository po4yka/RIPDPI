package com.poyka.ripdpi.core

import com.poyka.ripdpi.utility.shellSplit

sealed interface RipDpiProxyPreferences {
    fun toNativeConfigJson(): String

    val localAuthToken: String? get() = null
}

data class OwnedRelayQuicMigrationConfig(
    val bindLowPort: Boolean = false,
    val migrateAfterHandshake: Boolean = false,
)

fun stripRipDpiRuntimeContext(configJson: String): String = RipDpiProxyJsonCodec.stripRuntimeContext(configJson)

fun decodeRipDpiProxyUiPreferences(configJson: String): RipDpiProxyUIPreferences? =
    RipDpiProxyJsonCodec.decodeUiPreferences(configJson)

fun RipDpiProxyPreferences.warpConfigOrNull(): RipDpiWarpConfig? =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            warp.takeIf { it.enabled }
        }

        is RipDpiProxyJsonPreferences -> {
            decodeRipDpiProxyUiPreferences(toNativeConfigJson())?.warp?.takeIf { it.enabled }
        }

        is RipDpiProxyCmdPreferences -> {
            null
        }
    }

fun RipDpiProxyPreferences.relayConfigOrNull(): RipDpiRelayConfig? =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            relay.takeIf { it.enabled }
        }

        is RipDpiProxyJsonPreferences -> {
            decodeRipDpiProxyUiPreferences(toNativeConfigJson())?.relay?.takeIf { it.enabled }
        }

        is RipDpiProxyCmdPreferences -> {
            null
        }
    }

fun RipDpiProxyPreferences.ownedRelayQuicMigrationConfig(): OwnedRelayQuicMigrationConfig =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            OwnedRelayQuicMigrationConfig(
                bindLowPort = fakePackets.quicBindLowPort,
                migrateAfterHandshake = fakePackets.quicMigrateAfterHandshake,
            )
        }

        is RipDpiProxyJsonPreferences -> {
            decodeRipDpiProxyUiPreferences(toNativeConfigJson())
                ?.let { preferences ->
                    OwnedRelayQuicMigrationConfig(
                        bindLowPort = preferences.fakePackets.quicBindLowPort,
                        migrateAfterHandshake = preferences.fakePackets.quicMigrateAfterHandshake,
                    )
                }
                ?: OwnedRelayQuicMigrationConfig()
        }

        is RipDpiProxyCmdPreferences -> {
            OwnedRelayQuicMigrationConfig()
        }
    }

class RipDpiProxyJsonPreferences(
    private val configJson: String,
    private val hostAutolearnStorePath: String? = null,
    private val networkScopeKey: String? = null,
    private val runtimeContext: RipDpiRuntimeContext? = null,
    private val logContext: RipDpiLogContext? = null,
    private val rootMode: Boolean = false,
    private val rootHelperSocketPath: String? = null,
    override val localAuthToken: String? = null,
) : RipDpiProxyPreferences {
    override fun toNativeConfigJson(): String =
        RipDpiProxyJsonCodec.rewriteJson(
            configJson = configJson,
            hostAutolearnStorePath = hostAutolearnStorePath,
            networkScopeKey = networkScopeKey,
            runtimeContext = runtimeContext,
            logContext = logContext,
            rootMode = rootMode,
            rootHelperSocketPath = rootHelperSocketPath,
            localAuthToken = localAuthToken,
        )
}

class RipDpiProxyCmdPreferences(
    val args: Array<String>,
    private val hostAutolearnStorePath: String? = null,
    val runtimeContext: RipDpiRuntimeContext? = null,
    val logContext: RipDpiLogContext? = null,
) : RipDpiProxyPreferences {
    constructor(cmd: String) : this(cmdToArgs(cmd))

    constructor(
        cmd: String,
        hostAutolearnStorePath: String?,
        runtimeContext: RipDpiRuntimeContext?,
        logContext: RipDpiLogContext? = null,
    ) : this(cmdToArgs(cmd), hostAutolearnStorePath, runtimeContext, logContext)

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
            logContext = logContext,
        )
}
