//! Public facade — the VPN-protect callback registry.
//!
//! A flat re-export of `ripdpi-native-protect`: the process-global slot that
//! holds the `VpnService.protect` callback plus its accessors
//! (`register_protect_callback`, `has_protect_callback`,
//! `protect_socket_via_callback`, ...). Internally `vpn_protect` consults this
//! slot before falling back to a syscall. See the follow-up note in this
//! crate's `README.md` on consumers that import `ripdpi-native-protect`
//! directly rather than through this facade.

pub use ripdpi_native_protect::*;
