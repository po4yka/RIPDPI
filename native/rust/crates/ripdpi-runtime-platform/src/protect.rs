//! Public facade — the VPN-protect callback registry.
//!
//! A flat re-export of `ripdpi-native-protect`: the process-global slot that
//! holds the `VpnService.protect` callback plus its accessors
//! (`register_protect_callback`, `has_protect_callback`,
//! `protect_socket_via_callback`, ...). Internally `vpn_protect` consults this
//! slot before falling back to a syscall.
//!
//! Direct importers of `ripdpi-native-protect` today fall into three groups:
//! the Android adapters that own the paired `jniRegisterVpnProtect` /
//! `jniUnregisterVpnProtect` JNI entries (`ripdpi-android-vpn-protect-adapter`,
//! `ripdpi-warp-android`, `ripdpi-relay-android`, `ripdpi-amneziawg-android`)
//! install callbacks; the relay transport crates (`ripdpi-tuic`,
//! `ripdpi-hysteria2`, `ripdpi-masque`, `ripdpi-anytls`, ...) declare direct
//! dependencies to consume `SocketProtectionPolicy` and, in tests, install
//! fake callbacks; and `ripdpi-relay-core` deliberately reaches the policy only
//! through the re-export on `ripdpi-relay-tls-transports`, keeping the relay
//! core free of a direct registry edge. Runtime/platform consumers outside
//! those groups should prefer this facade, and new transport crates should
//! follow `relay-core`'s re-export pattern rather than adding fresh direct
//! edges.

pub use ripdpi_native_protect::*;
