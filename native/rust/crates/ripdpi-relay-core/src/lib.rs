//! Relay runtime composition.
//!
//! `ripdpi-relay-core` is the intentional protocol composition crate for relay
//! backends. Protocol crates stay behind `backend::builder` registration, while
//! `runtime` owns listener lifecycle, SOCKS dispatch, counters, error state, and
//! telemetry projection as separate runtime slices.

#![forbid(unsafe_code)]

mod backend;
mod config;
mod error;
mod protocols;
mod runtime;
mod runtime_validation;
mod socks;
mod telemetry;
mod transport_descriptor;

pub use config::{
    AnyTlsRelayConfig, CHAIN_RELAY_MAX_HOPS, CHAIN_RELAY_MIN_HOPS, ChainRelayConfig, CloudflareTunnelRelayConfig,
    CommonRelayConfig, Hysteria2RelayConfig, MasqueRelayConfig, MieruRelayConfig, NaiveProxyRelayConfig,
    RelayBackendConfig, ResolvedChainRelayHopConfig, ResolvedRelayFinalmaskConfig, ResolvedRelayRuntimeConfig,
    ResolvedShadowTlsInnerRelayConfig, ShadowTlsRelayConfig, SshRelayConfig, TorPluggableTransportConfig,
    TorRelayConfig, TrojanRelayConfig, TuicRelayConfig, UnsupportedRelayConfig, VlessRealityRelayConfig,
    VlessRelayConfig,
};
pub use runtime::RelayRuntime;
pub use telemetry::{RelayTelemetry, TcpConnectObservation, XudpTelemetrySnapshot};
pub use transport_descriptor::{RelayTransportDescriptor, relay_transport_descriptor};

#[cfg(test)]
mod tests;
