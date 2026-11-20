//! Kotlin-boundary wire contract of the flat relay runtime config.

use super::*;

#[test]
fn socket_protection_wire_defaults_inactive_and_accepts_vpn_required() {
    let proxy = sample_config("vless_reality");
    let proxy_json = serde_json::to_value(&proxy).expect("serialize proxy relay config");
    assert_eq!(proxy_json["socketProtection"], serde_json::json!("inactive"));

    let mut vpn_json = proxy_json;
    vpn_json["socketProtection"] = serde_json::json!("vpn_required");
    let vpn: ResolvedRelayRuntimeConfig = serde_json::from_value(vpn_json).expect("deserialize VPN relay config");
    assert_eq!(vpn.common.socket_protection, crate::config::SocketProtection::VpnRequired);

    // A payload missing `socketProtection` keeps its pinned two-sided
    // contract: Kotlin encodes Inactive via `@EncodeDefault(NEVER)` and the
    // Rust side mirrors that omission with a serde default, so absence means
    // explicitly-inactive rather than silent corruption.
    let mut legacy_json = serde_json::to_value(proxy).expect("serialize legacy relay config");
    legacy_json.as_object_mut().expect("object").remove("socketProtection");
    let legacy: ResolvedRelayRuntimeConfig =
        serde_json::from_value(legacy_json).expect("deserialize legacy relay config");
    assert_eq!(legacy.common.socket_protection, crate::config::SocketProtection::Inactive);
}

#[test]
fn relay_runtime_config_round_trips_flattened_backend_fields() {
    for kind in [
        "hysteria2",
        "tuic_v5",
        "vless",
        "vless_reality",
        "mieru",
        "ssh",
        "cloudflare_tunnel",
        "chain_relay",
        "masque",
        "shadowtls_v3",
        "trojan",
        "anytls",
        "shadowsocks",
        "snell",
    ] {
        let mut config = sample_config(kind);
        if let RelayBackendConfig::ChainRelay(chain) = &mut config.backend {
            chain.entry = Some(Box::new(vless_hop("entry", 443, "entry.example")));
            chain.exit = Some(Box::new(vless_hop("exit", 443, "exit.example")));
        }
        let serialized = serde_json::to_value(&config).expect("serialize relay config");

        assert_eq!(kind, serialized["kind"].as_str().expect("kind field"));
        assert!(serialized.get("localSocksHost").is_some(), "common fields stay flattened");

        let round_trip: ResolvedRelayRuntimeConfig =
            serde_json::from_value(serialized.clone()).expect("deserialize relay config");

        assert_eq!(kind, round_trip.kind_id());
        assert_eq!(serialized, serde_json::to_value(&round_trip).expect("reserialize relay config"));
    }
}

#[test]
fn relay_runtime_config_rejects_unknown_wire_fields() {
    let mut serialized = serde_json::to_value(sample_config("trojan")).expect("serialize relay config");
    serialized["sentinelUnknownField"] = serde_json::json!(true);

    let error = serde_json::from_value::<ResolvedRelayRuntimeConfig>(serialized)
        .expect_err("unknown wire fields must fail closed instead of silently defaulting");
    assert!(error.to_string().contains("sentinelUnknownField"), "the error must name the unknown field, got: {error}");
}

/// `deny_unknown_fields` is the wire-contract guard for the Kotlin boundary.
/// It must hold on NESTED types too: a typo inside a chain hop (dozens of
/// fields, several of them secrets), a finalmask sub-payload, a Tor transport
/// entry or the common section previously failed open while the top-level
/// gate stayed green.
#[test]
fn relay_runtime_config_rejects_unknown_nested_wire_fields() {
    type UnknownFieldInjection<'a> = (&'a str, &'a str, Box<dyn Fn(&mut serde_json::Value) + 'a>);
    let cases: [UnknownFieldInjection; 3] = [
        (
            "chainEntry",
            "chain_relay",
            Box::new(|payload: &mut serde_json::Value| {
                // sample_config builds a scalar-pair chain, so the entry hop
                // travels under its dedicated wire field.
                payload["chainEntry"]["sentinelUnknownHopField"] = serde_json::json!(true);
            }),
        ),
        (
            "finalmask",
            "vless_reality",
            Box::new(|payload: &mut serde_json::Value| {
                payload["finalmask"]["sentinelUnknownFinalmaskField"] = serde_json::json!(true);
            }),
        ),
        (
            "torTransports",
            "tor",
            Box::new(|payload: &mut serde_json::Value| {
                payload["torTransports"][0]["sentinelUnknownTransportField"] = serde_json::json!(true);
            }),
        ),
    ];

    for (label, kind, inject) in cases {
        let mut payload = serde_json::to_value(sample_config(kind)).expect("serialize relay config");
        inject(&mut payload);

        if let Ok(parsed) = serde_json::from_value::<ResolvedRelayRuntimeConfig>(payload) {
            panic!("{label}: unknown nested wire field must be rejected, got {parsed:?}");
        }
    }
}
