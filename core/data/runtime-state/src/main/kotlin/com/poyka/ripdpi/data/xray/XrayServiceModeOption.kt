package com.poyka.ripdpi.data.xray

/**
 * Selectable service-mode option for the Mode Editor / onboarding picker.
 *
 * RIPDPI's runtime mode axis ([com.poyka.ripdpi.data.Mode]) only distinguishes
 * `Proxy` vs `VPN`. The *provider* that backs a VPN session is a second axis
 * ([VpnProviderKind]). This model flattens the two axes into the small,
 * mutually-exclusive set of choices a user actually picks between in the UI:
 *
 * - [NativeDirect] — the native Rust VPN provider, direct (no relay).
 * - [NativeProxy] — the native provider routed through the configured relay.
 * - [XrayVpn] — the embedded Xray provider, full-tunnel VPN.
 *
 * Keeping this as a pure enum (no Android types) lets the picker logic and its
 * tests run offline; the UI resolves [titleKey]/[descriptionKey] against
 * `:app`'s `strings.xml` across all 7 locales.
 */
enum class XrayServiceModeOption(
    val providerKind: VpnProviderKind,
    val titleKey: String,
    val descriptionKey: String,
) {
    /** Native Rust provider, direct full-tunnel VPN. */
    NativeDirect(
        providerKind = VpnProviderKind.Native,
        titleKey = "service_mode_native_direct_title",
        descriptionKey = "service_mode_native_direct_desc",
    ),

    /** Native Rust provider routed through the configured relay. */
    NativeProxy(
        providerKind = VpnProviderKind.Native,
        titleKey = "service_mode_native_proxy_title",
        descriptionKey = "service_mode_native_proxy_desc",
    ),

    /** Embedded Xray provider, full-tunnel VPN backed by an imported Xray profile. */
    XrayVpn(
        providerKind = VpnProviderKind.Xray,
        titleKey = "service_mode_xray_vpn_title",
        descriptionKey = "service_mode_xray_vpn_desc",
    ),
    ;

    /** True when this option requires a validated [XrayProfile] before it can start. */
    val requiresXrayProfile: Boolean
        get() = providerKind == VpnProviderKind.Xray

    companion object {
        /** Every option offered by the picker, in display order. */
        val all: List<XrayServiceModeOption> = entries.toList()

        /**
         * The default selection when no provider has been chosen yet: the
         * native direct provider (the pre-existing behaviour).
         */
        val default: XrayServiceModeOption = NativeDirect
    }
}
