//! Relay runtime composition.
//!
//! `ripdpi-relay-core` is the intentional protocol composition crate for relay
//! backends. Protocol crates stay behind `backend::builder` registration, while
//! `runtime` owns listener lifecycle, SOCKS dispatch, counters, error state, and
//! telemetry projection as separate runtime slices.

mod backend;
mod bootstrap;
mod config;
mod protocols;
mod runtime;
mod runtime_validation;
mod socks;
mod telemetry;
mod transport_descriptor;

pub use config::{
    AnyTlsRelayConfig, ChainRelayConfig, CloudflareTunnelRelayConfig, CommonRelayConfig, Hysteria2RelayConfig,
    HysteriaV1RelayConfig, MasqueRelayConfig, MieruRelayConfig, NaiveProxyRelayConfig, RelayBackendConfig,
    ResolvedChainRelayHopConfig, ResolvedRelayFinalmaskConfig, ResolvedRelayRuntimeConfig,
    ResolvedShadowTlsInnerRelayConfig, ShadowTlsRelayConfig, SshRelayConfig, TorPluggableTransportConfig,
    TorRelayConfig, TrojanGoRelayConfig, TrojanRelayConfig, TuicRelayConfig, UnsupportedRelayConfig,
    VlessRealityRelayConfig, VmessRelayConfig, CHAIN_RELAY_MAX_HOPS, CHAIN_RELAY_MIN_HOPS,
};
pub use runtime::RelayRuntime;
pub use telemetry::{RelayTelemetry, TcpConnectObservation};
pub use transport_descriptor::{relay_transport_descriptor, RelayTransportDescriptor};

#[cfg(test)]
mod tests;
