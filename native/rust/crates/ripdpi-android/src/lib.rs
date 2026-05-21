mod ffi;

use android_support::{init_android_logging, JNI_VERSION};
use jni::sys::jint;
use jni::JavaVM;
use once_cell::sync::OnceCell;

pub use ffi::*;

static JVM: OnceCell<JavaVM> = OnceCell::new();

fn jni_on_load_impl() -> jint {
    android_support::ignore_sigpipe();
    init_android_logging("ripdpi-native");
    android_support::install_panic_hook();
    ripdpi_android_telemetry_adapter::install_recorder();
    JNI_VERSION
}

/// # Safety
/// Called by the JVM when the native library is loaded. Must not unwind across
/// the FFI boundary -- a panic here would be UB (extern "system" + unwind).
#[unsafe(no_mangle)]
#[allow(improper_ctypes_definitions)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    let _ = JVM.set(vm);
    match std::panic::catch_unwind(jni_on_load_impl) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jni_on_load_impl_returns_supported_jni_version() {
        assert_eq!(jni_on_load_impl(), JNI_VERSION);
    }

    #[test]
    fn jni_facade_keeps_handle_contract_in_shared_support() {
        assert_eq!(ripdpi_android_bridge_support::to_handle(0), None);
        assert_eq!(ripdpi_android_bridge_support::to_handle(-1), None);
        assert_eq!(ripdpi_android_bridge_support::to_handle(7), Some(7));
    }

    #[test]
    fn jni_facade_exports_stable_native_entrypoints() {
        use jni::objects::{JObject, JString};
        use jni::sys::{jboolean, jint, jlong, jstring};
        use jni::EnvUnowned;

        type VoidHandle = extern "system" fn(EnvUnowned<'_>, JObject<'_>, jlong);
        type Long = extern "system" fn(EnvUnowned<'_>, JObject<'_>) -> jlong;
        type Bool = extern "system" fn(EnvUnowned<'_>, JObject<'_>) -> jboolean;
        type StringHandle = extern "system" fn(EnvUnowned<'_>, JObject<'_>, jlong) -> jstring;

        let _symbols = (
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniCreate
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>) -> jlong,
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStart
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, jlong) -> jint,
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStop as VoidHandle,
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniPollTelemetry as StringHandle,
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniDestroy as VoidHandle,
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUpdateNetworkSnapshot
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, jlong, JString<'_>),
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStartPcapRecording
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, jlong, JString<'_>, jlong) -> jboolean,
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStopPcapRecording as StringHandle,
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniIsPcapRecording
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, jlong) -> jboolean,
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniGeoDatabaseVersions
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>, JString<'_>) -> jstring,
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniGeoIpMetadata
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>, JString<'_>, JString<'_>) -> jstring,
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniRegisterVpnProtect
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JObject<'_>) -> jlong,
            Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUnregisterVpnProtect as VoidHandle,
            Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCreate as Long,
            Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniStartScan
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, jlong, JString<'_>, JString<'_>),
            Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCancelScan as VoidHandle,
            Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollProgress as StringHandle,
            Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniTakeReport as StringHandle,
            Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollPassiveEvents as StringHandle,
            Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniDestroy as VoidHandle,
            Java_com_poyka_ripdpi_core_NativeOwnedTlsHttpFetcherNativeBindings_jniExecute
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>) -> jstring,
            Java_com_poyka_ripdpi_diagnostics_dpi_NativeDoqQuicClientNativeBindings_jniExchange
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>) -> jstring,
            Java_com_poyka_ripdpi_diagnostics_dpi_NativeQuicInitialPacketBindings_jniCreate
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>) -> jstring,
            Java_com_poyka_ripdpi_core_detection_checker_JniNativeSignsBridge_jniSnapshot
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>) -> jstring,
            Java_com_poyka_ripdpi_core_RipDpiPlatformCapabilities_jniSeqovlSupported as Bool,
            Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniRefreshCdnEch
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>) -> jstring,
            Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniSnapshotCdnEch
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>) -> jstring,
            Java_com_poyka_ripdpi_core_RipDpiCdnEchNativeBindings_jniSeedCdnEch
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>, jlong) -> jstring,
            Java_com_poyka_ripdpi_core_RipDpiSharedPriorsNativeBindings_jniApplySharedPriors
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>, JString<'_>) -> jstring,
            Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaLoadScript
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>) -> jstring,
            Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaReloadConfig
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>) -> jstring,
            Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaListStrategies
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>) -> jni::sys::jobjectArray,
            Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaLoadedScriptPaths
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>) -> jni::sys::jobjectArray,
            Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaValidateScript
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>) -> jstring,
            Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_validateStrategyConfigText
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>) -> jstring,
            Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_injectProbeResultsJson
                as extern "system" fn(EnvUnowned<'_>, JObject<'_>, JString<'_>) -> jstring,
        );
    }

    /// Verifies that the checked-in baseline lists at least as many JNI symbols
    /// as are declared in this crate.  A mismatch means someone added or removed
    /// a `#[no_mangle]` export without regenerating the baseline.
    #[test]
    fn jni_baseline_is_non_empty_and_contains_expected_symbols() {
        let baseline_src = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/jni-symbols.baseline"));

        let symbols: Vec<&str> = baseline_src.lines().map(str::trim).filter(|l| !l.is_empty()).collect();

        assert!(!symbols.is_empty(), "jni-symbols.baseline must not be empty");

        // Every Java_* / JNI_* name known at compile-time must appear in the
        // baseline so that a silent removal is caught at test time too.
        let required = [
            "JNI_OnLoad",
            "Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniCreate",
            "Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStart",
            "Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStop",
            "Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniDestroy",
            "Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCreate",
            "Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniDestroy",
            "Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaLoadScript",
        ];
        for sym in required {
            assert!(symbols.contains(&sym), "jni-symbols.baseline is missing required symbol: {sym}");
        }
    }
}
