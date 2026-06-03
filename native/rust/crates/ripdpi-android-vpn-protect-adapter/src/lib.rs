//! JNI-based VPN socket protection callback.
//!
//! Implements [`ProtectCallback`] by storing a `JavaVM` + `VpnService`
//! global ref and calling `VpnService.protect(int)` via JNI. Registered
//! at VPN startup, cleared at VPN shutdown.

#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

mod entry;
mod protect_callback;

use jni::JavaVM;
use jni::objects::JObject;
use jni::refs::Global;

use ripdpi_native_protect::{ProtectGeneration, register_protect_callback_versioned, unregister_protect_callback_if};

pub use entry::{register_entry, unregister_entry};
use protect_callback::JniProtectCallback;

/// Register VPN socket protection callback via JNI.
///
/// Called from Kotlin when the VPN service starts. Stores the JavaVM and a
/// global reference to the VpnService instance. Returns the generation token
/// the registry stamped on the slot; the caller threads it back to
/// [`unregister_vpn_protect`] so a stale unregister cannot clobber a newer
/// session's callback.
pub(crate) fn register_vpn_protect(vm: &JavaVM, vpn_service: Global<JObject<'static>>) -> i64 {
    // SAFETY: `vm.get_raw()` returns the live `*mut JavaVM` invocation-interface
    // pointer the JVM owns for the whole process lifetime (it was published by
    // `JNI_OnLoad` and is never freed while native code runs), so it is valid for
    // the duration of `vm_clone`. `JavaVM::from_raw` only copies the pointer; it
    // takes no ownership and does not mutate VM state. The clone is used solely to
    // call `attach_current_thread` in `JniProtectCallback::protect`, which the JNI
    // invocation interface is explicitly designed to serve concurrently, so the
    // duplicate handle introduces no aliasing hazard.
    let vm_clone = unsafe { JavaVM::from_raw(vm.get_raw()) };
    let callback = std::sync::Arc::new(JniProtectCallback { vm: vm_clone, vpn_service });
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
    use super::protect_callback::JniProtectCallback;

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
