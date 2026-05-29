// Under `--features loom`, in-crate `mod tests` modules are gated out so the
// loom integration tests in `tests/` get a clean runner. Test-only helpers
// then become dead in that one configuration; quiet the lint instead of
// annotating each item.
#![cfg_attr(all(test, feature = "loom"), allow(dead_code, unused_imports))]

mod config;
mod entry;
mod flow_attribution;
mod pcap;
mod session;
mod telemetry;

pub use entry::{
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniCreate,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniDestroy,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetStats,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetTelemetry,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniRegisterFlowAttribution,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStart,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStop,
    Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniUnregisterFlowAttribution,
    Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapListCaptures, Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapRedactToFile,
    Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapStart, Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapStop,
};

use android_support::{init_android_logging, JNI_VERSION};
use jni::sys::{jint, jlong};
use jni::JavaVM;

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
