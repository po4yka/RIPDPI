//! JNI-based VPN socket protection callback.
//!
//! Implements [`ProtectCallback`] by storing a `JavaVM` + `VpnService`
//! global ref and calling `VpnService.protect(int)` via JNI. Registered
//! at VPN startup, cleared at VPN shutdown.

mod entry;

use std::io;
use std::os::fd::RawFd;
use std::sync::Arc;

use jni::objects::{JObject, JValue};
use jni::refs::Global;
use jni::JavaVM;

use ripdpi_native_protect::{
    register_protect_callback_versioned, unregister_protect_callback_if, ProtectCallback, ProtectGeneration,
};

pub use entry::{register_entry, unregister_entry};

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
// SAFETY: see Send impl above — both fields are themselves thread-safe and
// `protect()` only reads them via Java-side synchronization.
unsafe impl Sync for JniProtectCallback {}

impl ProtectCallback for JniProtectCallback {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        let result: Result<bool, jni::errors::Error> =
            self.vm.attach_current_thread(|env| -> jni::errors::Result<bool> {
                let ret = env.call_method(
                    &self.vpn_service,
                    jni::jni_str!("protect"),
                    jni::jni_sig!("(I)Z"),
                    &[JValue::Int(fd)],
                )?;
                ret.z()
            });

        match result {
            Ok(true) => Ok(()),
            Ok(false) => Err(io::Error::new(io::ErrorKind::PermissionDenied, "VpnService.protect() returned false")),
            Err(e) => Err(io::Error::other(e.to_string())),
        }
    }
}

/// Register VPN socket protection callback via JNI.
///
/// Called from Kotlin when the VPN service starts. Stores the JavaVM and a
/// global reference to the VpnService instance. Returns the generation token
/// the registry stamped on the slot; the caller threads it back to
/// [`unregister_vpn_protect`] so a stale unregister cannot clobber a newer
/// session's callback.
pub(crate) fn register_vpn_protect(vm: &JavaVM, vpn_service: Global<JObject<'static>>) -> i64 {
    // SAFETY: JavaVM pointer is held live by JNI_OnLoad registration for the duration of the process.
    // Re-create a JavaVM handle from the raw pointer (just copies the pointer).
    let vm_clone = unsafe { JavaVM::from_raw(vm.get_raw()) };
    let callback = Arc::new(JniProtectCallback { vm: vm_clone, vpn_service });
    let generation = register_protect_callback_versioned(callback);
    tracing::info!(generation = generation.token(), "VPN protect callback registered via JNI");
    // The generation is a monotonic counter from 1; the value fits jlong for
    // the lifetime of any realistic process.
    generation.token() as i64
}

/// Unregister VPN socket protection callback.
///
/// Called from Kotlin when the VPN service stops, passing back the `token`
/// [`register_vpn_protect`] returned. The slot is cleared only if it still
/// carries that generation; a stale token (a superseded session) or a `0`
/// token (a failed register) is a safe no-op. The global reference is dropped
/// on a successful clear, allowing the Java object to be garbage collected.
pub(crate) fn unregister_vpn_protect(token: i64) {
    let generation = ProtectGeneration::from_token(token as u64);
    if unregister_protect_callback_if(generation) {
        tracing::info!(generation = generation.token(), "VPN protect callback unregistered");
    } else {
        tracing::info!(token, "VPN protect unregister ignored (stale token or no registration)");
    }
}

mod tests {
    use super::JniProtectCallback;

    const _: fn() = || {
        fn assert_send<T: Send>() {}
        fn assert_sync<T: Sync>() {}
        assert_send::<JniProtectCallback>();
        assert_sync::<JniProtectCallback>();
    };

    const _: fn() = || {
        struct Check<T>(core::marker::PhantomData<T>);
        trait AmbiguousIfCopy<A> {
            fn check() {}
        }
        impl<T> AmbiguousIfCopy<()> for Check<T> {}
        impl<T: Copy> AmbiguousIfCopy<u8> for Check<T> {}
        <Check<JniProtectCallback> as AmbiguousIfCopy<_>>::check();
    };
}
