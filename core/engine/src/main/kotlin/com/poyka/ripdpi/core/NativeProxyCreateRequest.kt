package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.EnvironmentKind

/**
 * Named bundle of inputs for a native proxy-create JSON request.
 *
 * Collects the loose parameters previously passed individually to
 * [RipDpiProxyJsonCodec.encodeUiPreferences] into a single value object so the
 * UI proxy-create path threads one argument instead of ~10. The field set,
 * names, types and defaults mirror that codec entry exactly — this is a
 * parameter-bundling refactor with no behavioral change.
 *
 * This is a plain Kotlin value object, deliberately NOT `@Serializable`: it
 * feeds the codec, it is not itself a wire type. The codec converts it into the
 * `kind = "ui"` `NativeProxyConfig` payload that is emitted as JSON.
 *
 * Contracts this request ultimately participates in:
 *  - `docs/architecture/CONFIG_CONTRACTS.md` — the config-mapping contract that
 *    governs how these inputs map onto the native config JSON schema.
 *  - `docs/architecture/JNI_CONTRACT.md` — the `jniCreate` boundary the emitted
 *    JSON is handed across into the Rust core.
 */
internal data class NativeProxyCreateRequest(
    val preferences: RipDpiProxyUIPreferences,
    val strategyPreset: String? = null,
    val rootMode: Boolean = false,
    val rootHelperSocketPath: String? = null,
    val geoipDbPath: String? = null,
    val geositeDbPath: String? = null,
    val listenAuthToken: String? = null,
    val localListenPortOverride: Int? = null,
    val localAuthToken: String? = null,
    val environmentKind: EnvironmentKind = EnvironmentKind.Unknown,
)
