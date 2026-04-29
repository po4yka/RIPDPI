//! Microsoft Edge TLS fingerprint profile.
//!
//! **Design rationale:** Edge is a Chromium-derived browser, so its TLS
//! fingerprint shares cipher suites, GREASE strategy (`chromium_single_grease`),
//! and extension permutation (`chromium_permuted`) with Chrome. This is expected
//! and correct — it is not an indication of an incomplete implementation.
//!
//! What distinguishes Edge from Chrome in the catalog:
//! - `browser_family: "edge"` and `browser_track: "stable"` (not `"android-stable"`).
//! - `client_hello_size_hint: 509` matches Chrome desktop, not Chrome Android (512).
//! - Distinct `ja3_parity_target` / `ja4_parity_target` (`"edge-stable"`), which
//!   are validated against the phase11 acceptance contract fixture.
//! - Rotation weight: 5% (Chrome Android 40%, Chrome desktop 15%).
//!
//! See TLS profile architecture note for the full analysis confirming this is a complete profile.

use boring::ssl::SslVersion;

use crate::profile::ProfileConfig;

/// Edge stable TLS fingerprint: Chromium-based, same ciphers as Chrome,
/// GREASE enabled, extension permutation enabled.
pub const EDGE_LATEST: ProfileConfig = ProfileConfig {
    name: "edge_stable",
    browser_family: "edge",
    browser_track: "stable",
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
    ja3_parity_target: "edge-stable",
    ja4_parity_target: "edge-stable",
};
