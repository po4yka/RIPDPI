//! End-to-end ShadowTLS version-mismatch coverage.
//!
//! Extracted from `tests.rs` to keep that file under the per-file LoC budget
//! enforced by `scripts/ci/check_file_loc_limits.py`.

use super::*;

/// A ShadowTLS inner relay config for the version-mismatch tests. It is never
/// connected — the handshake fails at the outer ShadowTLS layer first — but
/// `build_shadowtls` requires a present inner config to construct the factory.
fn shadowtls_inner_fixture() -> ResolvedShadowTlsInnerRelayConfig {
    ResolvedShadowTlsInnerRelayConfig {
        kind: "vless_reality".to_string(),
        profile_id: "inner".to_string(),
        server: "inner.example".to_string(),
        server_port: 443,
        server_name: "inner.example".to_string(),
        reality_public_key: valid_reality_public_key(),
        reality_short_id: String::new(),
        vless_flow: "xtls-rprx-vision".to_string(),
        vless_transport: "reality_tcp".to_string(),
        xhttp_mode: "auto".to_string(),
        vless_uuid: Some("11111111-1111-1111-1111-111111111111".to_string()),
        tls_fingerprint_profile: "firefox_stable".to_string(),
    }
}

/// End-to-end service-telemetry path: a direct ShadowTLS backend dialing a v2
/// server must surface the `shadowtls_version_mismatch` token through
/// `connect_tcp` — the value `record_handshake_error` records into
/// `last_handshake_error` — not a generic TLS handshake error.
#[tokio::test]
async fn shadowtls_v2_server_surfaces_version_mismatch_token_through_backend() {
    let server = ripdpi_shadowtls::ShadowTlsV2RejectLoopback::start().await.expect("start v2-reject loopback");
    let addr = server.local_addr();

    let mut config = sample_config("shadowtls_v3");
    config.common.server = addr.ip().to_string();
    config.common.server_port = i32::from(addr.port());
    config.common.server_name = "localhost".to_string();
    let shadowtls = shadowtls_config_mut(&mut config);
    shadowtls.password = Some("any-password".to_string());
    shadowtls.inner = Some(shadowtls_inner_fixture());

    let backend = build_backend(&config).await.expect("build ShadowTLS backend");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443));
    let error = backend.connect_tcp(&target).await.err().expect("a v2 server must fail the ShadowTLS handshake");

    assert_eq!(
        Some(FailureClass::ShadowTlsVersionMismatch),
        crate::error::relay_failure_class(&error),
        "the failure class must survive the backend boundary as typed data, got: {error}",
    );
    assert!(
        error.to_string().starts_with(FailureClass::ShadowTlsVersionMismatch.as_str()),
        "telemetry display text must keep leading with the failure-class token, got: {error}",
    );

    server.shutdown().await;
}

/// The `ChainRelay` arm of `connect_tcp` must map the version mismatch too: a
/// chain whose entry hop is a v2 ShadowTLS server surfaces the same token, so a
/// chained relay is not silently downgraded to a generic TLS error.
#[tokio::test]
async fn chain_relay_shadowtls_entry_v2_server_surfaces_version_mismatch_token() {
    let server = ripdpi_shadowtls::ShadowTlsV2RejectLoopback::start().await.expect("start v2-reject loopback");
    let addr = server.local_addr();

    let mut config = sample_config("chain_relay");
    chain_config_mut(&mut config).hops = vec![
        ResolvedChainRelayHopConfig {
            kind: "shadowtls_v3".to_string(),
            profile_id: "shadowtls-entry".to_string(),
            server: addr.ip().to_string(),
            server_port: i32::from(addr.port()),
            server_name: "localhost".to_string(),
            shadow_tls_password: Some("any-password".to_string()),
            shadow_tls_inner: Some(shadowtls_inner_fixture()),
            ..ResolvedChainRelayHopConfig::default()
        },
        shadowsocks_hop("ss-exit", 9443, "exit-secret"),
    ];

    let backend = build_backend(&config).await.expect("build chain backend with ShadowTLS entry");
    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443));
    let error = backend.connect_tcp(&target).await.err().expect("the v2 entry hop must fail the chain connect");

    assert_eq!(
        Some(FailureClass::ShadowTlsVersionMismatch),
        crate::error::relay_failure_class(&error),
        "the failure class must survive the chain-hop remapping as typed data, got: {error}",
    );
    assert!(
        error.to_string().starts_with(FailureClass::ShadowTlsVersionMismatch.as_str()),
        "chain telemetry display text must keep leading with the failure-class token, got: {error}",
    );

    server.shutdown().await;
}
