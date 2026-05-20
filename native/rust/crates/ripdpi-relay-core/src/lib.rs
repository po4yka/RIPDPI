//! Relay runtime composition.
//!
//! `ripdpi-relay-core` is the intentional protocol composition crate for relay
//! backends. Protocol crates stay behind `backend::builder` registration, while
//! `runtime` owns listener lifecycle, SOCKS dispatch, counters, error state, and
//! telemetry projection as separate runtime slices.

mod backend;
mod config;
mod protocols;
mod runtime;
mod runtime_validation;
mod socks;
mod telemetry;
mod transport_descriptor;

pub use config::{
    ChainRelayConfig, CloudflareTunnelRelayConfig, CommonRelayConfig, Hysteria2RelayConfig, MasqueRelayConfig,
    NaiveProxyRelayConfig, RelayBackendConfig, ResolvedRelayFinalmaskConfig, ResolvedRelayRuntimeConfig,
    ResolvedShadowTlsInnerRelayConfig, ShadowTlsRelayConfig, TuicRelayConfig, UnsupportedRelayConfig,
    VlessRealityRelayConfig,
};
pub use runtime::RelayRuntime;
pub use telemetry::RelayTelemetry;
pub use transport_descriptor::{relay_transport_descriptor, RelayTransportDescriptor, RELAY_TRANSPORT_DESCRIPTORS};

#[cfg(test)]
mod tests;
