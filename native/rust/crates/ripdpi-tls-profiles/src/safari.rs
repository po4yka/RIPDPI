use boring::ssl::SslVersion;

use crate::profile::ProfileConfig;

/// Safari stable TLS fingerprint: AES-GCM prioritized, includes P-521,
/// no GREASE, no extension permutation.
pub const SAFARI_LATEST: ProfileConfig = ProfileConfig {
    name: "safari_stable",
    browser_family: "safari",
    browser_track: "stable",
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
