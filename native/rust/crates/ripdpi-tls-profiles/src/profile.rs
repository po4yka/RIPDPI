use boring::ssl::SslVersion;

/// Configuration for a TLS fingerprint profile that controls ClientHello fields
/// to match a specific browser's TLS fingerprint.
pub struct ProfileConfig {
    pub name: &'static str,
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
}

pub struct ProfileCatalog {
    pub version: &'static str,
    pub default_profile_set_id: &'static str,
    pub profiles: &'static [&'static str],
}

/// All known profile names in selection order.
pub const AVAILABLE_PROFILES: &[&str] = &["chrome_stable", "firefox_stable", "safari_stable", "edge_stable"];
pub const DEFAULT_PROFILE_CATALOG: ProfileCatalog =
    ProfileCatalog { version: "v1", default_profile_set_id: "default", profiles: AVAILABLE_PROFILES };

pub fn lookup_profile(name: &str) -> &'static ProfileConfig {
    match name {
        "chrome_stable" => &crate::chrome::CHROME_LATEST,
        "firefox_stable" => &crate::firefox::FIREFOX_LATEST,
        "safari_stable" => &crate::safari::SAFARI_LATEST,
        "edge_stable" => &crate::edge::EDGE_LATEST,
        _ => &crate::chrome::CHROME_LATEST,
    }
}

pub fn profile_catalog() -> &'static ProfileCatalog {
    &DEFAULT_PROFILE_CATALOG
}
