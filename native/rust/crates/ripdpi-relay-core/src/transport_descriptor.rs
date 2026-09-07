//! Relay transport registry — the `relay_kind`-keyed source of truth that
//! merges a relay transport's static capability descriptor with its runtime
//! dispatch hooks.
//!
//! [`RELAY_TRANSPORT_REGISTRATIONS`] holds one [`RelayTransportRegistration`]
//! per concrete relay kind. Each registration bundles:
//!
//! * a [`RelayTransportDescriptor`] — the static, `relay_kind`-keyed capability
//!   profile (SOCKS TCP / UDP / connection reuse, outbound-bind-IP support);
//! * an optional in-process [`RelayBackendBuildFn`] — `None` for a kind that
//!   dispatches to an out-of-process subprocess (NaiveProxy);
//! * an optional `fallback_mode` marker surfaced by
//!   `runtime_validation::planned_backend_fallback_mode`.
//!
//! Adding a relay transport is a single registration here — a descriptor row
//! plus its builder — rather than parallel edits to a descriptor list and a
//! separate builder list. `runtime_validation` resolves the generic capability
//! decisions through the descriptor (`planned_backend_capabilities`, the
//! outbound-bind-IP gate); `backend::builder::build_backend` resolves the
//! in-process factory through the builder. A `relay_kind` with no registration
//! (`"off"`, an unknown kind, or any kind that resolves to the `Unsupported`
//! catch-all) yields `None`, which callers map to the historical permissive /
//! empty defaults.
//!
//! Capability facts that depend on a transport *sub-mode* rather than the
//! `relay_kind` string alone deliberately stay outside the registration table:
//! VLESS Reality's `xhttp` transport selects a different `RelayBackend` variant
//! (handled inside the `vless_reality` builder), connection-pool tuning and
//! finalmask support both vary with that sub-mode, and chain-relay upstream
//! description is backend-specific. Folding those in is the tracked future
//! refactor in `FEATURE_EXTENSION_GUIDE.md` §2 and this crate's `README.md`.

use std::io;

use crate::backend::RelayBackend;
use crate::backend::builder::{BuildContext, builders};
use crate::config::ResolvedRelayRuntimeConfig;

/// Static, `relay_kind`-keyed capability metadata for one relay transport.
///
/// One [`RELAY_TRANSPORT_REGISTRATIONS`] row carries one descriptor; the
/// `Unsupported` catch-all has no registration, so [`relay_transport_descriptor`]
/// returns `None` for an unknown `relay_kind`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RelayTransportDescriptor {
    /// The stable `relay_kind` string (`app_settings.proto` field 171). This
    /// is a config wire contract and never changes.
    pub kind_id: &'static str,
    /// Human-readable label for diagnostics and inventory surfaces.
    pub label: &'static str,
    /// The transport relays TCP `CONNECT` traffic (true for every transport).
    pub tcp: bool,
    /// The transport relays UDP `ASSOCIATE` traffic.
    pub udp: bool,
    /// Backend connections can be pooled and reused across SOCKS sessions.
    pub reusable: bool,
    /// The transport honours an `outbound_bind_ip`.
    pub supports_outbound_bind_ip: bool,
}

/// In-process relay backend factory: builds a [`RelayBackend`] for a resolved
/// runtime config. One concrete kind owns one builder.
pub(crate) type RelayBackendBuildFn = fn(&ResolvedRelayRuntimeConfig, &BuildContext) -> io::Result<RelayBackend>;

/// One relay transport's complete registration — its capability
/// [`RelayTransportDescriptor`] merged with its runtime-dispatch hooks.
///
/// Validation stays generic and descriptor-driven (`validate_runtime_config`
/// reads `supports_outbound_bind_ip`; UDP support is gated on the built
/// backend's capabilities), so no relay kind needs a bespoke validation hook
/// today and the registration carries none. Add one here if that changes.
pub(crate) struct RelayTransportRegistration {
    /// Static capability profile for this relay kind.
    pub(crate) descriptor: RelayTransportDescriptor,
    /// In-process backend factory. `None` for a subprocess-fallback kind
    /// (NaiveProxy), for which `build_backend` yields `RelayBackend::Unsupported`
    /// and the relay is driven out-of-process by the Kotlin layer.
    pub(crate) builder: Option<RelayBackendBuildFn>,
    /// Out-of-process dispatch marker surfaced by `planned_backend_fallback_mode`.
    /// `Some("subprocess")` for NaiveProxy; `None` for every in-process kind.
    pub(crate) fallback_mode: Option<&'static str>,
}

/// Inventory of every concrete relay transport, one registration per
/// `relay_kind`.
///
/// `runtime_validation` resolves `planned_backend_capabilities`, the
/// outbound-bind-IP gate, and `planned_backend_fallback_mode` through this
/// table; `backend::builder::build_backend` resolves the in-process factory
/// through it. The `relay_transport_registry_*` and
/// `relay_planned_*_are_pinned_for_every_kind` tests in `tests.rs` pin it
/// against every `RelayKind`.
pub(crate) static RELAY_TRANSPORT_REGISTRATIONS: &[RelayTransportRegistration] = &[
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "hysteria2",
            label: "Hysteria2",
            tcp: true,
            udp: true,
            reusable: true,
            supports_outbound_bind_ip: false,
        },
        builder: Some(builders::build_hysteria2),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "tuic_v5",
            label: "TUIC v5",
            tcp: true,
            udp: true,
            reusable: true,
            supports_outbound_bind_ip: true,
        },
        builder: Some(builders::build_tuic),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "vless",
            label: "VLESS",
            tcp: true,
            udp: false,
            reusable: true,
            supports_outbound_bind_ip: true,
        },
        builder: Some(builders::build_vless),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "vless_reality",
            label: "VLESS Reality",
            tcp: true,
            udp: true,
            // Per-kind flag; sub-mode truth lives in the factory and
            // `planned_backend_capabilities` (xhttp pools its mux carrier,
            // reality_tcp does not).
            reusable: false,
            supports_outbound_bind_ip: true,
        },
        builder: Some(builders::build_vless_reality),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "mieru",
            label: "Mieru",
            tcp: true,
            // The native Mieru session factory supports TCP only.
            // Keep UDP disabled until its relay path is implemented and tested.
            udp: false,
            reusable: false,
            supports_outbound_bind_ip: false,
        },
        builder: Some(builders::build_mieru),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "ssh",
            label: "SSH",
            tcp: true,
            udp: false,
            reusable: false,
            supports_outbound_bind_ip: false,
        },
        builder: Some(builders::build_ssh),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "cloudflare_tunnel",
            label: "Cloudflare Tunnel",
            tcp: true,
            udp: false,
            reusable: true,
            supports_outbound_bind_ip: true,
        },
        builder: Some(builders::build_cloudflare_tunnel),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "chain_relay",
            label: "Chain relay",
            tcp: true,
            udp: false,
            reusable: false,
            supports_outbound_bind_ip: true,
        },
        builder: Some(builders::build_chain_relay),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "masque",
            label: "MASQUE",
            tcp: true,
            udp: true,
            reusable: true,
            supports_outbound_bind_ip: false,
        },
        builder: Some(builders::build_masque),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "shadowtls_v3",
            label: "ShadowTLS v3",
            tcp: true,
            udp: false,
            reusable: false,
            supports_outbound_bind_ip: true,
        },
        builder: Some(builders::build_shadowtls),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "trojan",
            label: "Trojan",
            tcp: true,
            udp: true,
            reusable: false,
            supports_outbound_bind_ip: true,
        },
        builder: Some(builders::build_trojan),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "anytls",
            label: "AnyTLS",
            tcp: true,
            udp: true,
            reusable: true,
            supports_outbound_bind_ip: true,
        },
        builder: Some(builders::build_anytls),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "shadowsocks",
            label: "Shadowsocks",
            tcp: true,
            udp: true,
            reusable: false,
            supports_outbound_bind_ip: true,
        },
        builder: Some(builders::build_shadowsocks),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "tor",
            label: "Tor",
            tcp: true,
            udp: false,
            reusable: true,
            supports_outbound_bind_ip: false,
        },
        builder: Some(builders::build_tor),
        fallback_mode: None,
    },
    RelayTransportRegistration {
        descriptor: RelayTransportDescriptor {
            kind_id: "naiveproxy",
            label: "NaiveProxy",
            tcp: true,
            udp: false,
            reusable: false,
            supports_outbound_bind_ip: true,
        },
        // NaiveProxy is the one documented subprocess-fallback kind: a concrete,
        // descriptor-backed transport with no in-process builder.
        builder: None,
        fallback_mode: Some("subprocess"),
    },
];

/// Look up the [`RelayTransportRegistration`] for a `relay_kind` string.
///
/// Returns `None` for `"off"`, an unknown kind, or any `relay_kind` that
/// resolves to the `Unsupported` catch-all.
pub(crate) fn relay_transport_registration(kind_id: &str) -> Option<&'static RelayTransportRegistration> {
    RELAY_TRANSPORT_REGISTRATIONS.iter().find(|registration| registration.descriptor.kind_id == kind_id)
}

/// Look up the [`RelayTransportDescriptor`] for a `relay_kind` string.
///
/// Returns `None` for `"off"`, an unknown kind, or any `relay_kind` that
/// resolves to the `Unsupported` catch-all.
pub fn relay_transport_descriptor(kind_id: &str) -> Option<&'static RelayTransportDescriptor> {
    relay_transport_registration(kind_id).map(|registration| &registration.descriptor)
}
