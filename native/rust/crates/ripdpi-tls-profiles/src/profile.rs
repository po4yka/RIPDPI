use boring::ssl::SslVersion;

/// Configuration for a TLS fingerprint profile that controls ClientHello fields
/// to match a specific browser's TLS fingerprint.
pub struct ProfileConfig {
    pub name: &'static str,
    pub browser_family: &'static str,
    pub browser_track: &'static str,
    pub alpn_template: &'static str,
    pub extension_order_family: &'static str,
    pub grease_style: &'static str,
    pub supported_groups_profile: &'static str,
    pub key_share_profile: &'static str,
    pub record_choreography: &'static str,
    pub ech_capable: bool,
    pub cipher_list_tls12: &'static str,
    /// TLS 1.3 cipher suite configuration string. BoringSSL uses a fixed set of
    /// TLS 1.3 ciphers and does not expose `SSL_CTX_set_ciphersuites`, so this
    /// field is stored for documentation/reference but not applied via API.
    pub ciphersuites_tls13: &'static str,
    pub curves: &'static str,
    pub sigalgs: &'static str,
    pub alpn: &'static [&'static [u8]],
    pub grease_enabled: bool,
    pub permute_extensions: bool,
    pub min_version: SslVersion,
    pub max_version: SslVersion,
    pub client_hello_size_hint: usize,
    pub ja3_parity_target: &'static str,
    pub ja4_parity_target: &'static str,
}

pub struct ProfileCatalog {
    pub version: &'static str,
    pub default_profile_set_id: &'static str,
    pub profiles: &'static [&'static str],
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProfileInvariantStatus {
    Satisfied,
    AvoidsBlocked517ByteClientHello,
}

impl ProfileInvariantStatus {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Satisfied => "satisfied",
            Self::AvoidsBlocked517ByteClientHello => "avoids_blocked_517_byte_client_hello",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProfileParityTargets {
    pub browser_family: &'static str,
    pub browser_track: &'static str,
    pub ja3: &'static str,
    pub ja4: &'static str,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProfileTemplateMetadata {
    pub alpn_template: &'static str,
    pub extension_order_family: &'static str,
    pub grease_style: &'static str,
    pub supported_groups_profile: &'static str,
    pub key_share_profile: &'static str,
    pub record_choreography: &'static str,
    pub ech_capable: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProfileMetadata {
    pub profile_name: &'static str,
    pub catalog_version: &'static str,
    pub client_hello_size_hint: usize,
    pub invariant_status: ProfileInvariantStatus,
    pub parity_targets: ProfileParityTargets,
    pub template: ProfileTemplateMetadata,
}

/// All known profile names in selection order.
pub const AVAILABLE_PROFILES: &[&str] =
    &["chrome_stable", "chrome_desktop_stable", "firefox_stable", "firefox_ech_stable", "safari_stable", "edge_stable"];
pub const DEFAULT_PROFILE_CATALOG: ProfileCatalog =
    ProfileCatalog { version: "v1", default_profile_set_id: "browser_family_v2", profiles: AVAILABLE_PROFILES };

pub fn lookup_profile(name: &str) -> &'static ProfileConfig {
    match name {
        "chrome_stable" => &crate::chrome::CHROME_LATEST,
        "chrome_desktop_stable" => &crate::chrome::CHROME_DESKTOP_STABLE,
        "firefox_stable" => &crate::firefox::FIREFOX_LATEST,
        "firefox_ech_stable" => &crate::firefox::FIREFOX_ECH_STABLE,
        "safari_stable" => &crate::safari::SAFARI_LATEST,
        "edge_stable" => &crate::edge::EDGE_LATEST,
        _ => &crate::chrome::CHROME_LATEST,
    }
}

pub fn profile_catalog() -> &'static ProfileCatalog {
    &DEFAULT_PROFILE_CATALOG
}

pub fn profile_metadata(name: &str) -> ProfileMetadata {
    let profile = lookup_profile(name);
    ProfileMetadata {
        profile_name: profile.name,
        catalog_version: DEFAULT_PROFILE_CATALOG.version,
        client_hello_size_hint: profile.client_hello_size_hint,
        invariant_status: ProfileInvariantStatus::AvoidsBlocked517ByteClientHello,
        parity_targets: ProfileParityTargets {
            browser_family: profile.browser_family,
            browser_track: profile.browser_track,
            ja3: profile.ja3_parity_target,
            ja4: profile.ja4_parity_target,
        },
        template: ProfileTemplateMetadata {
            alpn_template: profile.alpn_template,
            extension_order_family: profile.extension_order_family,
            grease_style: profile.grease_style,
            supported_groups_profile: profile.supported_groups_profile,
            key_share_profile: profile.key_share_profile,
            record_choreography: profile.record_choreography,
            ech_capable: profile.ech_capable,
        },
    }
}
