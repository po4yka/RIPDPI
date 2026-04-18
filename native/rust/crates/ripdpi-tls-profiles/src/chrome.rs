use boring::ssl::SslVersion;

use crate::profile::ProfileConfig;

pub const CHROME_LATEST: ProfileConfig = ProfileConfig {
    name: "chrome_stable",
    browser_family: "chrome",
    browser_track: "android-stable",
    alpn_template: "h2_http11",
    extension_order_family: "chromium_permuted",
    grease_style: "chromium_single_grease",
    supported_groups_profile: "x25519_p256_p384",
    key_share_profile: "x25519_primary",
    record_choreography: "host_tail_two_record",
    ech_capable: false,
    ech_bootstrap_policy: "none",
    ech_bootstrap_resolver_id: None,
    ech_outer_extension_policy: "not_applicable",
    cipher_list_tls12: "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:\
                        ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:\
                        ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305",
    ciphersuites_tls13: "TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256",
    curves: "X25519:P-256:P-384",
    sigalgs: "ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256:rsa_pkcs1_sha256:\
              ecdsa_secp384r1_sha384:rsa_pss_rsae_sha384:rsa_pkcs1_sha384:\
              rsa_pss_rsae_sha512:rsa_pkcs1_sha512",
    alpn: &[b"h2", b"http/1.1"],
    grease_enabled: true,
    permute_extensions: true,
    min_version: SslVersion::TLS1_2,
    max_version: SslVersion::TLS1_3,
    client_hello_size_hint: 512,
    ja3_parity_target: "chrome-stable",
    ja4_parity_target: "chrome-stable",
};

pub const CHROME_DESKTOP_STABLE: ProfileConfig = ProfileConfig {
    name: "chrome_desktop_stable",
    browser_family: "chrome",
    browser_track: "desktop-stable",
    alpn_template: "h2_http11",
    extension_order_family: "chromium_permuted",
    grease_style: "chromium_single_grease",
    supported_groups_profile: "x25519_p256_p384",
    key_share_profile: "x25519_primary",
    record_choreography: "host_tail_two_record",
    ech_capable: false,
    ech_bootstrap_policy: "none",
    ech_bootstrap_resolver_id: None,
    ech_outer_extension_policy: "not_applicable",
    cipher_list_tls12: "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:\
                        ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:\
                        ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305",
    ciphersuites_tls13: "TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256",
    curves: "X25519:P-256:P-384",
    sigalgs: "ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256:rsa_pkcs1_sha256:\
              ecdsa_secp384r1_sha384:rsa_pss_rsae_sha384:rsa_pkcs1_sha384:\
              rsa_pss_rsae_sha512:rsa_pkcs1_sha512",
    alpn: &[b"h2", b"http/1.1"],
    grease_enabled: true,
    permute_extensions: true,
    min_version: SslVersion::TLS1_2,
    max_version: SslVersion::TLS1_3,
    client_hello_size_hint: 509,
    ja3_parity_target: "chrome-desktop-stable",
    ja4_parity_target: "chrome-desktop-stable",
};
