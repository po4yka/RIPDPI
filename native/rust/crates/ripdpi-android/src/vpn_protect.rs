//! JNI-based VPN socket protection callback.
//!
//! Implements [`ProtectCallback`] by storing a `JavaVM` + `VpnService`
//! global ref and calling `VpnService.protect(int)` via JNI. Registered
//! at VPN startup, cleared at VPN shutdown.

use std::io;
use std::os::fd::RawFd;
use std::sync::Arc;

use jni::objects::{JObject, JValue};
use jni::refs::Global;
use jni::JavaVM;

use ripdpi_runtime::platform::protect::{register_protect_callback, unregister_protect_callback, ProtectCallback};

/// JNI-based socket protection callback.
///
/// Calls `VpnService.protect(int)` on each invocation by attaching
/// the current thread to the JVM and invoking the method on the stored
/// global reference.
struct JniProtectCallback {
    vm: JavaVM,
    vpn_service: Global<JObject<'static>>,
}

// SAFETY: JavaVM is Send+Sync (just a *mut sys::JavaVM wrapper).
// Global<JObject<'static>> prevents GC from collecting the Java object
// and is safe to use from any thread via attach_current_thread.
unsafe impl Send for JniProtectCallback {}
unsafe impl Sync for JniProtectCallback {}

impl ProtectCallback for JniProtectCallback {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        let result: Result<bool, jni::errors::Error> =
            self.vm.attach_current_thread(|env| -> jni::errors::Result<bool> {
                let ret = env.call_method(
                    &self.vpn_service,
                    jni::jni_str!("protect"),
                    jni::jni_sig!("(I)Z"),
                    &[JValue::Int(fd as i32)],
                )?;
                ret.z()
            });

        match result {
            Ok(true) => Ok(()),
            Ok(false) => Err(io::Error::new(io::ErrorKind::PermissionDenied, "VpnService.protect() returned false")),
            Err(e) => Err(io::Error::new(io::ErrorKind::Other, e.to_string())),
        }
    }
}

/// Register VPN socket protection callback via JNI.
///
/// Called from Kotlin when the VPN service starts. Stores the JavaVM
/// and a global reference to the VpnService instance.
pub(crate) fn register_vpn_protect(vm: &JavaVM, vpn_service: Global<JObject<'static>>) {
    // Re-create a JavaVM handle from the raw pointer (just copies the pointer).
    let vm_clone = unsafe { JavaVM::from_raw(vm.get_raw()) };
    let callback = Arc::new(JniProtectCallback { vm: vm_clone, vpn_service });
    register_protect_callback(callback);
    tracing::info!("VPN protect callback registered via JNI");
}

/// Unregister VPN socket protection callback.
///
/// Called from Kotlin when the VPN service stops. The global reference
/// is dropped, allowing the Java object to be garbage collected.
pub(crate) fn unregister_vpn_protect() {
    unregister_protect_callback();
    tracing::info!("VPN protect callback unregistered");
}
