mod config;
mod diagnostics;
mod errors;
mod ffi;
mod owned_tls_http;
mod proxy;
#[cfg(test)]
mod support;
mod telemetry;
mod vpn_protect;

use android_support::{init_android_logging, JNI_VERSION};
use jni::sys::{jint, jlong};
use jni::JavaVM;
use once_cell::sync::OnceCell;

pub use ffi::*;

static JVM: OnceCell<JavaVM> = OnceCell::new();

fn jni_on_load_impl() -> jint {
    android_support::ignore_sigpipe();
    init_android_logging("ripdpi-native");
    android_support::install_panic_hook();
    ripdpi_telemetry::recorder::install();
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

pub(crate) fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    pub(crate) fn shared_test_jvm() -> &'static JavaVM {
        static TEST_JVM: OnceCell<JavaVM> = OnceCell::new();
        TEST_JVM.get_or_init(|| {
            let args = jni::InitArgsBuilder::new()
                .version(jni::JNIVersion::V9)
                .option("-Xcheck:jni")
                .build()
                .expect("build test JVM init args");
            JavaVM::new(args).expect("create in-process test JVM")
        })
    }

    pub(crate) fn shared_jni_test_mutex() -> &'static std::sync::Mutex<()> {
        static JNI_TEST_MUTEX: once_cell::sync::Lazy<std::sync::Mutex<()>> =
            once_cell::sync::Lazy::new(|| std::sync::Mutex::new(()));
        &JNI_TEST_MUTEX
    }

    #[test]
    fn jni_on_load_impl_returns_supported_jni_version() {
        assert_eq!(jni_on_load_impl(), JNI_VERSION);
    }

    #[test]
    fn to_handle_accepts_positive_values_only() {
        assert_eq!(to_handle(0), None);
        assert_eq!(to_handle(-1), None);
        assert_eq!(to_handle(7), Some(7));
    }
}
