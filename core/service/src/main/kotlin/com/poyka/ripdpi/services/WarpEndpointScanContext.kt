package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.normalizeWarpScannerMode
import com.poyka.ripdpi.proto.AppSettings

/** Inputs belong to one profile revision and must not follow live settings during a scan. */
class WarpEndpointScanContext(
    val credentials: WarpCredentials,
    val config: RipDpiWarpConfig,
    val scannerMode: String = WarpScannerModeAutomatic,
    val requiredProtection: RelaySocketProtection? = null,
) {
    override fun toString(): String = "WarpEndpointScanContext([REDACTED])"

    internal fun withCredentials(updated: WarpCredentials): WarpEndpointScanContext =
        WarpEndpointScanContext(updated, config, scannerMode, requiredProtection)

    internal fun bind(probe: WarpEndpointProbe): WarpEndpointProbe =
        object : WarpEndpointProbe {
            override suspend fun probe(
                candidate: WarpEndpointCacheEntry,
                timeoutMillis: Int,
            ): WarpEndpointCacheEntry? = probe.probe(candidate, timeoutMillis, this@WarpEndpointScanContext)
        }

    companion object {
        fun capture(
            credentials: WarpCredentials,
            settings: AppSettings,
        ): WarpEndpointScanContext =
            WarpEndpointScanContext(
                credentials,
                RipDpiProxyUIPreferences.fromSettings(settings).warp,
                normalizeWarpScannerMode(settings.warpLastScannerMode),
            )
    }
}
