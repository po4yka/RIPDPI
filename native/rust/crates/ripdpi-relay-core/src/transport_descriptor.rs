//! Relay transport descriptor — an additive, read-only inventory of the relay
//! transports the runtime can build.
//!
//! Each [`RelayTransportDescriptor`] records the *static, `relay_kind`-keyed*
//! facts about one relay transport: its stable `relay_kind` string, a
//! human-readable label, the SOCKS capability profile (TCP / UDP / connection
//! reuse), and whether it honours an outbound bind IP.
//!
//! This descriptor is **additive metadata for documentation, diagnostics, and
//! inventory** — it is intentionally *not* wired into runtime dispatch. Relay
//! backend selection, capability planning, pool sizing, and config parsing
//! continue to flow through the `match RelayKind` statements in
//! `runtime_validation.rs` and the `BUILDERS` slice. A crate test keeps this
//! table consistent with that source of truth so the two cannot drift.
//!
//! Two facts are deliberately **excluded** from the descriptor because they
//! depend on a transport sub-mode rather than the `relay_kind` string alone:
//! finalmask support and connection-pool tuning both vary with VLESS Reality's
//! `xhttp` transport (`RelayKind::VlessReality { xhttp }` splits one
//! `relay_kind` string into two profiles). Folding those in — and migrating
//! the runtime matches onto the descriptor — is the tracked future refactor in
//! `FEATURE_EXTENSION_GUIDE.md` §2 and this crate's `README.md`.

/// Static, `relay_kind`-keyed metadata for one relay transport.
///
/// One [`RELAY_TRANSPORT_DESCRIPTORS`] row exists per concrete relay kind; the
/// `Unsupported` catch-all has no row, so [`relay_transport_descriptor`]
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

/// Inventory of every concrete relay transport, one row per `relay_kind`.
///
/// Kept consistent with `runtime_validation::planned_backend_capabilities` and
/// `RelayKind::supports_outbound_bind_ip` by a crate test — see
/// `relay_transport_descriptors_match_runtime_capability_planning` in
/// `tests.rs`.
pub static RELAY_TRANSPORT_DESCRIPTORS: &[RelayTransportDescriptor] = &[
    RelayTransportDescriptor {
        kind_id: "hysteria2",
        label: "Hysteria2",
        tcp: true,
        udp: true,
        reusable: true,
        supports_outbound_bind_ip: false,
    },
    RelayTransportDescriptor {
        kind_id: "tuic_v5",
        label: "TUIC v5",
        tcp: true,
        udp: true,
        reusable: true,
        supports_outbound_bind_ip: true,
    },
    RelayTransportDescriptor {
        kind_id: "vless_reality",
        label: "VLESS Reality",
        tcp: true,
        udp: false,
        reusable: false,
        supports_outbound_bind_ip: true,
    },
    RelayTransportDescriptor {
        kind_id: "cloudflare_tunnel",
        label: "Cloudflare Tunnel",
        tcp: true,
        udp: false,
        reusable: true,
        supports_outbound_bind_ip: true,
    },
    RelayTransportDescriptor {
        kind_id: "chain_relay",
        label: "Chain relay",
        tcp: true,
        udp: false,
        reusable: false,
        supports_outbound_bind_ip: true,
    },
    RelayTransportDescriptor {
        kind_id: "masque",
        label: "MASQUE",
        tcp: true,
        udp: true,
        reusable: true,
        supports_outbound_bind_ip: false,
    },
    RelayTransportDescriptor {
        kind_id: "shadowtls_v3",
        label: "ShadowTLS v3",
        tcp: true,
        udp: false,
        reusable: false,
        supports_outbound_bind_ip: true,
    },
    RelayTransportDescriptor {
        kind_id: "naiveproxy",
        label: "NaiveProxy",
        tcp: true,
        udp: false,
        reusable: false,
        supports_outbound_bind_ip: true,
    },
];

/// Look up the [`RelayTransportDescriptor`] for a `relay_kind` string.
///
/// Returns `None` for `"off"`, an unknown kind, or any `relay_kind` that
/// resolves to the `Unsupported` catch-all.
pub fn relay_transport_descriptor(kind_id: &str) -> Option<&'static RelayTransportDescriptor> {
    RELAY_TRANSPORT_DESCRIPTORS.iter().find(|descriptor| descriptor.kind_id == kind_id)
}
