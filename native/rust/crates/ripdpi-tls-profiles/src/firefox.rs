use boring::ssl::SslVersion;

use crate::profile::ProfileConfig;

pub const FIREFOX_LATEST: ProfileConfig = ProfileConfig {
    name: "firefox_stable",
    browser_family: "firefox",
    browser_track: "stable",
    alpn_template: "h2_http11",
    extension_order_family: "firefox_fixed",
    grease_style: "none",
    supported_groups_profile: "x25519_p256_p384_p521",
    key_share_profile: "x25519_primary",
    record_choreography: "single_record",
    ech_capable: false,
    cipher_list_tls12: "ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:\
                        ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:\
                        ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384",
    ciphersuites_tls13: "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_256_GCM_SHA384",
    curves: "X25519:P-256:P-384:P-521",
    sigalgs: "ecdsa_secp256r1_sha256:ecdsa_secp384r1_sha384:ecdsa_secp521r1_sha512:\
              rsa_pss_rsae_sha256:rsa_pss_rsae_sha384:rsa_pss_rsae_sha512:\
              rsa_pkcs1_sha256:rsa_pkcs1_sha384:rsa_pkcs1_sha512",
    alpn: &[b"h2", b"http/1.1"],
    grease_enabled: false,
    permute_extensions: false,
    min_version: SslVersion::TLS1_2,
    max_version: SslVersion::TLS1_3,
    client_hello_size_hint: 505,
    ja3_parity_target: "firefox-stable",
    ja4_parity_target: "firefox-stable",
};

pub const FIREFOX_ECH_STABLE: ProfileConfig = ProfileConfig {
    name: "firefox_ech_stable",
    browser_family: "firefox",
    browser_track: "ech-stable",
    alpn_template: "h2_http11",
    extension_order_family: "firefox_fixed",
    grease_style: "firefox_ech_grease",
    supported_groups_profile: "x25519_p256_p384_p521",
    key_share_profile: "x25519_hybrid_ready",
    record_choreography: "single_record",
    ech_capable: true,
    cipher_list_tls12: "ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:\
                        ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:\
                        ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384",
    ciphersuites_tls13: "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_256_GCM_SHA384",
    curves: "X25519:P-256:P-384:P-521",
    sigalgs: "ecdsa_secp256r1_sha256:ecdsa_secp384r1_sha384:ecdsa_secp521r1_sha512:\
              rsa_pss_rsae_sha256:rsa_pss_rsae_sha384:rsa_pss_rsae_sha512:\
              rsa_pkcs1_sha256:rsa_pkcs1_sha384:rsa_pkcs1_sha512",
    alpn: &[b"h2", b"http/1.1"],
    grease_enabled: true,
    permute_extensions: false,
    min_version: SslVersion::TLS1_3,
    max_version: SslVersion::TLS1_3,
    client_hello_size_hint: 526,
    ja3_parity_target: "firefox-ech-stable",
    ja4_parity_target: "firefox-ech-stable",
};
