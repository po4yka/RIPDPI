use std::time::Duration;

use ripdpi_relay_mux::RelayPoolConfig;

use super::*;
use crate::config::RelayKind;
use crate::runtime_validation::{planned_backend_fallback_mode, pool_config_for_backend};
use crate::transport_descriptor::{
    RELAY_TRANSPORT_REGISTRATIONS, relay_transport_descriptor, relay_transport_registration,
};

/// How the relay runtime dispatches a concrete `relay_kind`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RelayDispatchClass {
    /// Served in-process: the kind's `RELAY_TRANSPORT_REGISTRATIONS` entry
    /// carries a backend builder that constructs a `RelayBackend` variant.
    InProcessBackend,
    /// A registered kind with a descriptor but no in-process builder; it routes
    /// through an out-of-process subprocess. NaiveProxy is the descriptor-backed
    /// kind in this class: its registration's `fallback_mode` is `"subprocess"` and
    /// `build_backend` deliberately yields `RelayBackend::Unsupported`.
    SubprocessFallback,
    /// Not a relay transport (`"off"`, unknown kinds): no registration.
    Unsupported,
}

/// Exhaustive dispatch classification for every `RelayKind`. Adding a new
/// `RelayKind` variant fails to compile here until its dispatch path is
/// declared -- the drift guard tying the registration table to the
/// runtime-dispatch surface.
fn relay_dispatch_class(kind: RelayKind<'_>) -> RelayDispatchClass {
    match kind {
        RelayKind::Hysteria2
        | RelayKind::TuicV5
        | RelayKind::Vless { .. }
        | RelayKind::VlessReality { .. }
        | RelayKind::Mieru
        | RelayKind::Ssh
        | RelayKind::CloudflareTunnel
        | RelayKind::ChainRelay
        | RelayKind::Masque
        | RelayKind::ShadowTlsV3
        | RelayKind::Trojan
        | RelayKind::AnyTls
        | RelayKind::Shadowsocks
        | RelayKind::Tor => RelayDispatchClass::InProcessBackend,
        RelayKind::NaiveProxy => RelayDispatchClass::SubprocessFallback,
        RelayKind::Unsupported(_) => RelayDispatchClass::Unsupported,
    }
}

/// Drift guard for the merged relay transport registry, tying
/// `RELAY_TRANSPORT_REGISTRATIONS` to the runtime-dispatch surface:
///
/// * every concrete `RelayKind` has exactly one registration;
/// * every registration carries a descriptor whose `kind_id` round-trips and
///   whose planned capabilities it reproduces;
/// * exactly NaiveProxy has no in-process builder (subprocess fallback);
/// * `"off"` / unknown kinds do not register.
///
/// `relay_dispatch_class` and `relay_backend_kind_id` are both exhaustive
/// matches, so a new `RelayKind` or `RelayBackend` variant fails to compile
/// until the registry is extended.
#[test]
fn relay_transport_registry_is_consistent() {
    // One config per concrete RelayKind, both VLESS sub-modes, plus "off" and an
    // unknown kind. `from_config` drives the classification.
    let mut vless_xhttp = sample_config("vless_reality");
    vless_config_mut(&mut vless_xhttp).vless_transport = "xhttp".to_string();
    let configs = [
        sample_config("hysteria2"),
        sample_config("tuic_v5"),
        sample_config("vless"),
        sample_config("vless_reality"),
        vless_xhttp,
        sample_config("mieru"),
        sample_config("ssh"),
        sample_config("cloudflare_tunnel"),
        sample_config("chain_relay"),
        sample_config("masque"),
        sample_config("shadowtls_v3"),
        sample_config("trojan"),
        sample_config("anytls"),
        sample_config("shadowsocks"),
        sample_config("tor"),
        sample_config("naiveproxy"),
        sample_config("off"),
        sample_config("totally_unknown"),
    ];

    // Forward: every dispatched (in-process or subprocess) kind resolves to
    // exactly one registration; "off"/unknown kinds resolve to none.
    let mut registered = std::collections::BTreeSet::new();
    for config in &configs {
        let kind_id = config.kind_id();
        let registration = relay_transport_registration(kind_id);
        match relay_dispatch_class(RelayKind::from_config(config)) {
            RelayDispatchClass::InProcessBackend | RelayDispatchClass::SubprocessFallback => {
                let registration =
                    registration.unwrap_or_else(|| panic!("dispatched relay kind {kind_id} must have a registration"));
                assert_eq!(kind_id, registration.descriptor.kind_id, "registration kind_id round-trip for {kind_id}");
                let rows =
                    RELAY_TRANSPORT_REGISTRATIONS.iter().filter(|entry| entry.descriptor.kind_id == kind_id).count();
                assert_eq!(1, rows, "{kind_id} must have exactly one registration");
                registered.insert(kind_id.to_string());
            }
            RelayDispatchClass::Unsupported => {
                assert!(registration.is_none(), "unsupported/off relay kind {kind_id} must not register");
            }
        }
    }

    // NaiveProxy is the one documented exception: a concrete, descriptor-backed
    // kind with no in-process builder -- it dispatches to the subprocess
    // fallback rather than a `RelayBackend` variant.
    assert_eq!(RelayDispatchClass::SubprocessFallback, relay_dispatch_class(RelayKind::NaiveProxy));

    // Reverse: every registration is a dispatched kind with a well-formed
    // descriptor that round-trips and reproduces its planned capabilities;
    // exactly NaiveProxy is the builderless subprocess-fallback kind.
    for registration in RELAY_TRANSPORT_REGISTRATIONS {
        let descriptor = registration.descriptor;
        assert!(descriptor.tcp, "{} registration: every relay transport relays TCP", descriptor.kind_id);
        assert!(!descriptor.label.is_empty(), "{} registration needs a label", descriptor.kind_id);
        assert_eq!(
            Some(&descriptor),
            relay_transport_descriptor(descriptor.kind_id),
            "{} descriptor lookup must round-trip",
            descriptor.kind_id,
        );
        assert_ne!(
            RelayDispatchClass::Unsupported,
            relay_dispatch_class(RelayKind::from_config(&sample_config(descriptor.kind_id))),
            "registration kind {} must map to a dispatched RelayKind",
            descriptor.kind_id,
        );
        assert!(registered.contains(descriptor.kind_id), "registration kind {} is unreachable", descriptor.kind_id);

        let mut capability_config = sample_config(descriptor.kind_id);
        if descriptor.kind_id == "vless_reality" {
            // The descriptor says the kind can carry UDP; the effective
            // profile capability additionally requires the existing opt-in.
            capability_config.common.udp_enabled = true;
        }
        let capabilities = planned_backend_capabilities(&capability_config);
        assert_eq!(
            (descriptor.tcp, descriptor.udp, descriptor.reusable),
            (capabilities.tcp, capabilities.udp, capabilities.reusable),
            "{} planned capabilities must match its registered descriptor",
            descriptor.kind_id,
        );

        if descriptor.kind_id == "naiveproxy" {
            assert!(registration.builder.is_none(), "naiveproxy is subprocess-backed: no in-process builder");
            assert_eq!(Some("subprocess"), registration.fallback_mode, "naiveproxy keeps the subprocess fallback");
        } else {
            assert!(registration.builder.is_some(), "{} must register an in-process builder", descriptor.kind_id);
            assert_eq!(None, registration.fallback_mode, "{} is in-process: no fallback mode", descriptor.kind_id);
        }
    }
    assert_eq!(
        RELAY_TRANSPORT_REGISTRATIONS.len(),
        registered.len(),
        "registration count must equal the number of dispatched relay kinds",
    );

    // Every in-process `RelayBackend` dispatch variant maps to a registered
    // kind. `relay_backend_kind_id` is exhaustive: a new variant breaks
    // compilation until mapped.
    assert_eq!(None, relay_backend_kind_id(&RelayBackend::Unsupported { kind: "off".to_string() }));
    for kind_id in [
        "hysteria2",
        "tuic_v5",
        "vless",
        "vless_reality",
        "mieru",
        "ssh",
        "chain_relay",
        "masque",
        "shadowtls_v3",
        "trojan",
        "anytls",
        "shadowsocks",
        "tor",
    ] {
        assert!(
            relay_transport_registration(kind_id).is_some(),
            "runtime-dispatch backend kind {kind_id} must have a registration",
        );
    }

    assert!(relay_transport_registration("off").is_none(), "\"off\" is not a relay transport");
    assert!(relay_transport_registration("totally_unknown").is_none(), "unknown kinds have no registration");
}

/// The plain `vless` registration is native only for xHTTP/TLS; unsupported
/// transports fail closed instead of building an inert backend.
#[tokio::test]
async fn relay_transport_registry_dispatches_plain_vless_xhttp_only() {
    let mut xhttp = sample_config("vless");
    plain_vless_config_mut(&mut xhttp).vless_flow = "none".to_string();
    match build_backend(&xhttp).await.expect("plain vless xhttp backend") {
        RelayBackend::Xhttp(_) => {}
        other => panic!("plain vless xhttp must build Xhttp, got {:?}", std::mem::discriminant(&other)),
    }

    let mut tcp = sample_config("vless");
    plain_vless_config_mut(&mut tcp).vless_transport = "tcp".to_string();
    let Err(error) = build_backend(&tcp).await else {
        panic!("plain vless tcp has no native backend");
    };
    assert_eq!(io::ErrorKind::Unsupported, error.kind());
}

/// The single `vless_reality` registration's builder dispatches by transport
/// sub-mode: classic Reality builds the TCP `VlessReality` backend, `xhttp`
/// builds the multiplexed `Xhttp` backend. This sub-mode split deliberately
/// stays explicit inside the builder rather than becoming a registry key.
#[tokio::test]
async fn relay_transport_registry_dispatches_vless_sub_modes() {
    let mut reality = sample_config("vless_reality");
    vless_config_mut(&mut reality).reality_public_key = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=".to_string();
    match build_backend(&reality).await.expect("vless reality_tcp backend") {
        RelayBackend::VlessReality(_) => {}
        other => panic!("reality_tcp must build VlessReality, got {:?}", std::mem::discriminant(&other)),
    }

    let mut xhttp = reality.clone();
    let vless = vless_config_mut(&mut xhttp);
    vless.vless_transport = "xhttp".to_string();
    vless.vless_flow = "none".to_string();
    match build_backend(&xhttp).await.expect("vless xhttp backend") {
        RelayBackend::Xhttp(_) => {}
        other => panic!("xhttp must build Xhttp, got {:?}", std::mem::discriminant(&other)),
    }
}

/// Pins `planned_backend_fallback_mode` and `pool_config_for_backend` for every
/// concrete `RelayKind`. `planned_backend_fallback_mode` now reads each kind's
/// registration `fallback_mode`; `pool_config_for_backend` stays a `match
/// RelayKind` decision -- pool sizing is transport-family tuning that varies by
/// VLESS sub-mode. This test pins both against literal expectations so a wrong
/// value is caught here.
///
/// (`planned_backend_capabilities` and outbound-bind-IP validation are pinned
/// for every kind by `relay_planned_capabilities_are_pinned_for_every_kind`.)
#[test]
fn relay_planned_runtime_policy_is_pinned_for_every_kind() {
    // kind_id, fallback_mode, pool max_active_leases, pool idle_timeout (secs)
    let pinned: [(&str, Option<&str>, usize, u64); 15] = [
        ("hysteria2", None, 64, 45),
        ("tuic_v5", None, 64, 45),
        ("vless", None, 48, 20),
        ("vless_reality", None, 16, 5), // reality_tcp sub-mode
        ("mieru", None, 16, 5),
        ("ssh", None, 16, 5),
        ("cloudflare_tunnel", None, 48, 20),
        ("chain_relay", None, 16, 5),
        ("masque", None, 64, 45),
        ("shadowtls_v3", None, 16, 5),
        ("trojan", None, 16, 5),
        ("anytls", None, 64, 45),
        ("shadowsocks", None, 16, 5),
        ("tor", None, 16, 5),
        ("naiveproxy", Some("subprocess"), 16, 5),
    ];

    for (kind_id, fallback, leases, idle_secs) in pinned {
        let config = sample_config(kind_id);
        assert_eq!(
            fallback.map(str::to_string),
            planned_backend_fallback_mode(&config),
            "planned fallback mode drifted for {kind_id}",
        );
        let pool = pool_config_for_backend(&config);
        assert_eq!(leases, pool.max_active_leases, "pool leases drifted for {kind_id}");
        assert_eq!(Duration::from_secs(idle_secs), pool.idle_timeout, "pool idle timeout drifted for {kind_id}");
    }

    // VLESS Reality's `xhttp` sub-mode keeps the capability-bearing (None)
    // fallback mode but takes the xhttp-family pool policy -- a `match
    // RelayKind` sub-mode decision that the single `vless_reality` descriptor
    // cannot express.
    let mut vless_xhttp = sample_config("vless_reality");
    vless_config_mut(&mut vless_xhttp).vless_transport = "xhttp".to_string();
    assert_eq!(None, planned_backend_fallback_mode(&vless_xhttp));
    let xhttp_pool = pool_config_for_backend(&vless_xhttp);
    assert_eq!(48, xhttp_pool.max_active_leases, "vless xhttp pool leases");
    assert_eq!(Duration::from_secs(20), xhttp_pool.idle_timeout, "vless xhttp pool idle timeout");

    // The Unsupported catch-all reports an `unsupported:<kind>` fallback and the
    // default pool config.
    let unsupported = sample_config("totally_unknown");
    assert_eq!(
        Some("unsupported:totally_unknown".to_string()),
        planned_backend_fallback_mode(&unsupported),
        "unsupported relay kind must report an unsupported fallback mode",
    );
    let default_pool = pool_config_for_backend(&unsupported);
    assert_eq!(RelayPoolConfig::default().max_active_leases, default_pool.max_active_leases);
    assert_eq!(RelayPoolConfig::default().idle_timeout, default_pool.idle_timeout);
}
