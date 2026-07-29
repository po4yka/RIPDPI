// Under `--features loom`, in-crate `mod tests` modules are gated out so the
// loom integration tests in `tests/` get a clean runner. Test-only helpers
// then become dead in that one configuration; quiet the lint instead of
// annotating each item.
#![cfg_attr(all(test, feature = "loom"), allow(dead_code, unused_imports))]
// Crate-local unsafe-hardening floor. `ripdpi-tunnel-android` is a JNI cdylib
// whose unsafe surface is the FFI boundary: raw-fd adoption (TUN dup, pcap
// detachFd), JNI object construction (`*::from_raw`), `JavaVM::from_raw`, and
// the manual `Send`/`Sync` impls on the flow-attribution notifier. Per the
// rust-unsafe skill lint floor, every `unsafe { }` block must carry an inline
// SAFETY comment and each unsafe op must be individually justified, so the FFI
// invariants stay auditable next to the operation. Matches the opt-in already
// landed in ripdpi-vless / ripdpi-io-uring / ripdpi-privileged-ops /
// ripdpi-proxy-runtime while the rest of the workspace migrates.
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

mod config;
mod direct_dns_binding;
mod entry;
mod flow_attribution;
mod pcap;
mod session;
mod telemetry;

pub use entry::{
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniCreate,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniDestroy,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetIcmpIngressPackets,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetStats,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetTelemetry,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniRegisterDirectDnsSocketBinderNative,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniRegisterFlowAttribution,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStart,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStop,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniUnregisterDirectDnsSocketBinderNative,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniUnregisterFlowAttribution,
    Java_com_poyka_ripdpi_core_TunDeviceQualificationNativeBindings_jniProbeUnprivilegedBindToDevice,
    Java_com_poyka_ripdpi_core_TunForwardingEvidenceNativeBindings_jniGetForwardingEvidence,
    Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapListCaptures, Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapRedactToFile,
    Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapStart, Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapStop,
};

use android_support::{JNI_VERSION, init_android_logging};
use jni::JavaVM;
use jni::sys::{jint, jlong};

/// # Safety
/// Called by the JVM when the native library is loaded. Must not unwind across
/// the FFI boundary -- a panic here would be UB (extern "system" + unwind).
#[unsafe(no_mangle)]
#[allow(improper_ctypes_definitions)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    match std::panic::catch_unwind(|| {
        android_support::ignore_sigpipe();
        init_android_logging("ripdpi-tunnel-native");
        android_support::install_panic_hook();
        JNI_VERSION
    }) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}

pub(crate) fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
}
