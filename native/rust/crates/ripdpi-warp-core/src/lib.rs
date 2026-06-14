#![forbid(unsafe_code)]

mod amneziawg;
mod amneziawg_runtime;
mod config;
mod endpoint_probe;
mod observe;
mod platform;
mod ports;
mod runtime;
mod socks;
mod support;
mod virtual_iface;
mod wireguard;

pub use amneziawg_runtime::{AmneziaWgObfuscation, AmneziaWgProfileConfig, AmneziaWgRuntime, AmneziaWgTelemetry};
pub use config::{
    ResolvedWarpRuntimeConfig, ResolvedWarpRuntimeEndpoint, WarpAmneziaConfig, WarpEndpointProbeRequest,
    WarpEndpointProbeResult, WarpManualEndpoint, WarpTelemetry,
};
pub use endpoint_probe::{WarpProbeError, probe_endpoint, probe_endpoint_with_platform};
pub use observe::TcpConnectObservation;
pub use platform::{WarpPlatform, WarpSocketProtector};
pub use runtime::WarpRuntime;
