//! TLS-in-TLS nested-handshake exposure posture coverage.
//!
//! Extracted from `tests.rs` to keep that file under the per-file LoC budget
//! enforced by `scripts/ci/check_file_loc_limits.py`.

use super::*;

/// Pins the per-kind MUX-default posture against TLS-in-TLS fingerprinting: which
/// TLS-over-TCP relay kinds open a fresh outer TLS handshake per SOCKS session (no
/// session reuse at all), exposing the nested inner-TLS burst pattern.
///
/// The audit, the `mux-on / mux-supported-off / mux-incapable` classification, and
/// the scope limits of the source study (Xue et al., USENIX Security 2024 — a US
/// academic-ISP feasibility measurement, not a TSPU/GFW finding) live in
/// `docs/tasks/issues/audit-relay-mux-default-nested-handshake-conformance.md`.
///
/// `tls_over_tcp` (the one axis classified here) = the outer transport places TLS
/// records on a TCP stream, the substrate for the TLS-in-TLS-over-TCP fingerprint.
/// QUIC kinds (`hysteria2` / `tuic_v5` / `masque`) and non-TLS kinds (`mieru` /
/// `ssh` / `shadowsocks`) are `false`.
///
/// The exposed set is then *derived* from the authoritative registry, not from a
/// second hand-maintained list:
///
/// * `descriptor.reusable` — the descriptor's own statement that backend sessions
///   are pooled and reused across SOCKS sessions. `RelayMux` (`relay-mux/pool.rs`)
///   caches one idle session and reuses it only when `active_leases == 0`, so a
///   `reusable = false` kind re-handshakes for every session.
/// * `registration.builder.is_some()` — the kind has an in-process datapath;
///   subprocess kinds (NaiveProxy) multiplex out-of-process and are excluded.
///
/// `exposed = tls_over_tcp && !reusable && in_process`. Flipping a descriptor's
/// `reusable` flag, or adding a non-reusable TLS-over-TCP kind, changes this set
/// and trips the test — forcing the audit doc to be refreshed. The two xmux
/// transports (VLESS `xhttp` sub-mode, Cloudflare) are tied to live `build_backend`
/// output below: the only stream-multiplexing wired into a TLS-over-TCP relay
/// datapath today is xHTTP/H2 (`XmuxConfig::default()` = 8 conns × 32 streams).
#[tokio::test]
async fn relay_tls_in_tls_exposure_posture_is_pinned_for_every_kind() {
    // kind_id, tls_over_tcp (see the doc comment for the per-family rationale)
    let tls_over_tcp: [(&str, bool); 15] = [
        ("hysteria2", false),
        ("tuic_v5", false),
        ("vless", true),
        ("vless_reality", true),
        ("mieru", false),
        ("ssh", false),
        ("cloudflare_tunnel", true),
        ("chain_relay", true),
        ("masque", false),
        ("shadowtls_v3", true),
        ("trojan", true),
        ("anytls", true),
        ("shadowsocks", false),
        ("tor", true),
        ("naiveproxy", true),
    ];

    // Every registered kind is classified: a new transport cannot be added without
    // a TLS-over-TCP classification here.
    assert_eq!(
        tls_over_tcp.len(),
        crate::transport_descriptor::RELAY_TRANSPORT_REGISTRATIONS.len(),
        "every registered relay kind needs a TLS-over-TCP classification",
    );

    let mut exposed: Vec<&str> = tls_over_tcp
        .iter()
        .filter(|(kind, tls)| {
            let registration =
                crate::transport_descriptor::relay_transport_registration(kind).expect("registered relay kind");
            *tls && !registration.descriptor.reusable && registration.builder.is_some()
        })
        .map(|(kind, _)| *kind)
        .collect();
    exposed.sort_unstable();
    assert_eq!(
        exposed,
        ["chain_relay", "shadowtls_v3", "trojan", "vless_reality"],
        "the unmuxed nested-handshake (TLS-in-TLS-exposed) set drifted — update the audit doc",
    );

    // Teeth: the xmux transports build the reusable `Xhttp` backend (H2 stream mux);
    // classic VLESS Reality builds the non-reusable single-stream `VlessReality` one.
    let key = valid_reality_public_key();

    let mut classic = sample_config("vless_reality");
    vless_config_mut(&mut classic).reality_public_key = key.clone();
    let backend = build_backend(&classic).await.expect("classic VLESS Reality backend builds");
    assert!(
        matches!(backend, RelayBackend::VlessReality(_)),
        "classic VLESS Reality must build the non-reusable single-stream backend",
    );

    let mut xhttp = sample_config("vless_reality");
    let vless = vless_config_mut(&mut xhttp);
    vless.reality_public_key = key;
    vless.vless_transport = "xhttp".to_string();
    vless.vless_flow = "none".to_string();
    vless.xhttp_path = "/api/v1/stream".to_string();
    let backend = build_backend(&xhttp).await.expect("VLESS xhttp backend builds");
    assert!(matches!(backend, RelayBackend::Xhttp(_)), "VLESS xhttp sub-mode must build the xmux Xhttp backend");

    let mut cloudflare = sample_config("cloudflare_tunnel");
    cloudflare.common.server = "edge.example.com".to_string();
    cloudflare.common.server_name = "edge.example.com".to_string();
    cloudflare_config_mut(&mut cloudflare).xhttp_path = "/cdn/api".to_string();
    let backend = build_backend(&cloudflare).await.expect("cloudflare tunnel backend builds");
    assert!(matches!(backend, RelayBackend::Xhttp(_)), "cloudflare tunnel must build the xmux Xhttp backend");
}
