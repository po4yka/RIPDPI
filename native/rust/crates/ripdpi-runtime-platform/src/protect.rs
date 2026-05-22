//! Public facade — the VPN-protect callback registry.
//!
//! A flat re-export of `ripdpi-native-protect`: the process-global slot that
//! holds the `VpnService.protect` callback plus its accessors
//! (`register_protect_callback`, `has_protect_callback`,
//! `protect_socket_via_callback`, ...). Internally `vpn_protect` consults this
//! slot before falling back to a syscall.
//!
//! Socket-protecting consumers route through this facade. The only crates that
//! import `ripdpi-native-protect` directly are the Android adapters that own
//! the paired `jniRegisterVpnProtect` / `jniUnregisterVpnProtect` JNI entries
//! (`ripdpi-android-vpn-protect-adapter`, `ripdpi-warp-android`); they install
//! the callback this slot stores.

pub use ripdpi_native_protect::*;
