//! Public facade — socket-protection entry points.
//!
//! Re-exports the `vpn_protect` runtime-adaptation module. `protect_socket`
//! routes a socket through the registered `VpnService.protect` callback when
//! one is present, and otherwise falls back to `ripdpi-privileged-ops` (or a
//! no-op on non-Linux targets).

pub use super::vpn_protect::{protect_diagnostics_socket, protect_proxy_socket, protect_socket};
