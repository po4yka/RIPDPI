//! `ripdpi-proxy-config` — proxy configuration parsing and conversion.
//!
//! Bridges the UI/JSON-facing configuration shapes and the internal
//! `RuntimeConfig` consumed by the proxy runtime. The crate's stable public
//! API is exactly the re-exports below; the `convert` and `types` modules are
//! private, so their items are reachable externally only through these
//! re-exports.
//!
//! ## Public API areas
//!
//! - **`presets`** — named preset application (`apply_preset`,
//!   `apply_runtime_preset`).
//! - **Config types** — `ProxyConfigPayload`, `ProxyUiConfig` and the
//!   `ProxyUi*` family of UI config sub-structs, `RuntimeConfigEnvelope`,
//!   `ProxyConfigError`.
//! - **Runtime context types** — `ProxyRuntimeContext`, `ProxyLogContext`,
//!   `ProxyEncryptedDnsContext`, `ProxyDirectPathCapability`,
//!   `ProxyMorphPolicy`, `ProxyPreferredEdge`.
//! - **Network snapshot types** — `NetworkSnapshot`, `WifiSnapshot`,
//!   `CellularSnapshot`.
//! - **Conversion entry points** — `parse_proxy_config_json`,
//!   `runtime_config_from_*`, `runtime_config_envelope_from_payload`, and the
//!   `parse_*_fake_profile` parsers.
//! - **Default constants** — `ADAPTIVE_FAKE_TTL_*`,
//!   `FAKE_TLS_SNI_MODE_RANDOMIZED`, `QUIC_FAKE_PROFILE_DISABLED`.
//!
//! Internal-only config types (the `SEQOVL_*` / `FAKE_*` SNI/payload default
//! constants, `HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_HOURS`,
//! `ProxySessionOverrides`) are intentionally NOT re-exported here — they stay
//! reachable as `crate::types::*` for internal callers without widening the
//! public surface.
//!
//! The low-level `convert` parsers (`parse_tcp_chain_step_kind`,
//! `parse_udp_chain_step_kind`, `parse_desync_mode`, `parse_quic_initial_mode`,
//! `normalize_fake_tls_sni_mode`) likewise have no external callers, but they
//! remain re-exported below: `convert` is a multi-level re-export pyramid, so
//! dropping a single leaf cascades unused re-exports up the chain (and
//! `parse_desync_mode` is itself unreferenced). Tightening them is a separate
//! `convert`-module re-export refactor rather than a safe one-line change.

pub mod presets;

mod convert;
mod types;

#[cfg(test)]
mod tests;

// Public API re-exports -- types.
// Only types referenced by downstream workspace crates are re-exported here;
// internal-only types stay reachable as `crate::types::*`.
pub use types::{
    CellularSnapshot, NetworkSnapshot, ProxyConfigError, ProxyConfigPayload, ProxyDirectPathCapability,
    ProxyEncryptedDnsContext, ProxyLogContext, ProxyMorphPolicy, ProxyPreferredEdge, ProxyRuntimeContext,
    ProxyUiActivationFilter, ProxyUiChainConfig, ProxyUiConfig, ProxyUiFakePacketConfig, ProxyUiHostAutolearnConfig,
    ProxyUiHostsConfig, ProxyUiListenConfig, ProxyUiNumericRange, ProxyUiParserEvasionConfig, ProxyUiProtocolConfig,
    ProxyUiQuicConfig, ProxyUiTcpChainStep, ProxyUiTcpRotationCandidate, ProxyUiTcpRotationConfig, ProxyUiUdpChainStep,
    RuntimeConfigEnvelope, WifiSnapshot, ADAPTIVE_FAKE_TTL_DEFAULT_DELTA, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
    ADAPTIVE_FAKE_TTL_DEFAULT_MAX, ADAPTIVE_FAKE_TTL_DEFAULT_MIN, FAKE_TLS_SNI_MODE_RANDOMIZED,
    QUIC_FAKE_PROFILE_DISABLED,
};

// Public API re-exports -- conversion functions.
// `normalize_fake_tls_sni_mode` / `parse_desync_mode` / `parse_quic_initial_mode`
// / `parse_tcp_chain_step_kind` / `parse_udp_chain_step_kind` have no external
// callers but stay re-exported -- see the crate docs (convert re-export pyramid).
pub use convert::{
    normalize_fake_tls_sni_mode, parse_desync_mode, parse_http_fake_profile, parse_proxy_config_json,
    parse_quic_fake_profile, parse_quic_initial_mode, parse_tcp_chain_step_kind, parse_tls_fake_profile,
    parse_udp_chain_step_kind, parse_udp_fake_profile, runtime_config_envelope_from_payload,
    runtime_config_from_command_line, runtime_config_from_payload, runtime_config_from_ui,
};

// Internal re-export for test access
#[allow(unused_imports)]
pub(crate) use types::HOSTS_DISABLE;
