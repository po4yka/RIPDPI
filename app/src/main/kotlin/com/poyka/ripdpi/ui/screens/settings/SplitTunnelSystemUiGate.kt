package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Android 17 (API 37) added a system-owned per-app VPN exclusion screen. VPN apps fire
 * `ACTION_VPN_APP_EXCLUSION_SETTINGS` to delegate per-app exclusion to the OS, which persists the
 * exclusions across reconnects. On Android 17+ RIPDPI hands split-tunnel app exclusion to that
 * screen; below 17 it keeps the in-app editor.
 *
 * Pure with an injectable [sdkInt] so the version branch is unit-testable without a device.
 */
internal object SplitTunnelSystemUiGate {
    /**
     * Android 17. There is no `Build.VERSION_CODES` symbol for API 37 in the current SDK, so the
     * integer literal is used directly.
     */
    const val ANDROID_17_API = 37

    /**
     * Value of `android.provider.Settings.ACTION_VPN_APP_EXCLUSION_SETTINGS` (API 37+). Kept as a
     * literal — verified against the `compileSdk = 37` `android.jar` — so the code compiles on any
     * SDK level and triggers no `NewApi` lint on a field reference. The manifest declares the same
     * action string for system discovery.
     */
    const val ACTION_VPN_APP_EXCLUSION_SETTINGS = "android.settings.VPN_APP_EXCLUSION_SETTINGS"

    /** Whether the running OS version is Android 17+ (the version half of the decision). */
    fun usesSystemScreen(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= ANDROID_17_API

    /** Intent that opens the system per-app VPN exclusion settings screen. */
    fun createExclusionSettingsIntent(): Intent = Intent(ACTION_VPN_APP_EXCLUSION_SETTINGS)

    /**
     * Whether to render the "managed by system" card: Android 17+ AND the system screen actually
     * resolves on this device. When it does not resolve (e.g. an OEM build without it), the caller
     * falls back to the in-app editor instead of showing a dead button.
     */
    fun systemScreenAvailable(context: Context): Boolean =
        usesSystemScreen() && createExclusionSettingsIntent().resolveActivity(context.packageManager) != null
}
