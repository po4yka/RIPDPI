//! Apple Safari TLS fingerprint profile.
//!
//! **Design rationale:** Safari has a fingerprint that is genuinely distinct
//! from any Chromium-based browser. Key differences from Chrome:
//!
//! | Parameter                | Safari                      | Chrome Android       |
//! |--------------------------|-----------------------------|----------------------|
//! | `extension_order_family` | `"safari_fixed"`            | `"chromium_permuted"`|
//! | `grease_style`           | `"none"`                    | `"chromium_single_grease"` |
//! | `grease_enabled`         | `false`                     | `true`               |
//! | `permute_extensions`     | `false`                     | `true`               |
//! | `supported_groups`       | X25519, P-256, P-384, P-521 | X25519, P-256, P-384 |
//! | `record_choreography`    | `"single_record"`           | `"host_tail_two_record"` |
//! | TLS 1.2 cipher priority  | AES-256 before AES-128      | AES-128 before AES-256 |
//! | `client_hello_size_hint` | 498                         | 512                  |
//!
//! These differences are not cosmetic — they affect JA3/JA4 fingerprint hashes,
//! extension ordering, and ClientHello byte layout. Safari is the only profile
//! in the catalog that sends no GREASE values and uses a fixed extension order,
//! making it valuable for servers that apply GREASE-based detection heuristics.
//!
//! Rotation weight: 10%. See TLS profile architecture note for the full analysis.

use boring::ssl::SslVersion;

use crate::profile::ProfileConfig;

/// Safari stable TLS fingerprint: AES-GCM prioritized, includes P-521,
/// no GREASE, no extension permutation.
pub const SAFARI_LATEST: ProfileConfig = ProfileConfig {
    name: "safari_stable",
    browser_family: "safari",
    browser_track: "stable",
    alpn_template: "h2_http11",
    extension_order_family: "safari_fixed",
    grease_style: "none",
    supported_groups_profile: "x25519_p256_p384_p521",
    key_share_profile: "x25519_primary",
    record_choreography: "single_record",
    ech_capable: false,
    ech_bootstrap_policy: "none",
    ech_bootstrap_resolver_id: None,
    ech_outer_extension_policy: "not_applicable",
    cipher_list_tls12: "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256:\
                        ECDHE-RSA-AES256-GCM-SHA384:ECDHE-RSA-AES128-GCM-SHA256:\
                        ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305",
    ciphersuites_tls13: "TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256",
    curves: "X25519:P-256:P-384:P-521",
    sigalgs: "ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256:rsa_pkcs1_sha256:\
              ecdsa_secp384r1_sha384:rsa_pss_rsae_sha384:rsa_pkcs1_sha384:\
              rsa_pss_rsae_sha512:rsa_pkcs1_sha512",
    alpn: &[b"h2", b"http/1.1"],
    grease_enabled: false,
    permute_extensions: false,
    min_version: SslVersion::TLS1_2,
    max_version: SslVersion::TLS1_3,
    client_hello_size_hint: 498,
    ja3_parity_target: "safari-stable",
    ja4_parity_target: "safari-stable",
};
