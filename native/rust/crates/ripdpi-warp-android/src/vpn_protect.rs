use std::io;
use std::os::fd::RawFd;
use std::sync::Arc;

use android_support::SharedJvm;
use jni::objects::{JObject, JValue};
use jni::refs::Global;
use jni::{EnvUnowned, JavaVM, Outcome};
use ripdpi_native_protect::{
    ProtectCallback, ProtectGeneration, register_protect_callback_versioned, unregister_protect_callback_if,
};
use ripdpi_warp_core::WarpPlatform;

struct JniProtectCallback {
    vm: SharedJvm,
    vpn_service: Global<JObject<'static>>,
}

// `JniProtectCallback` auto-derives `Send + Sync`: `SharedJvm` (`Arc<JavaVM>`) and
// `Global<JObject<'static>>` are both `Send + Sync` in jni 0.22. Relying on the
// auto-derive rather than a manual `unsafe impl` keeps the compiler tripwire — a
// future non-thread-safe field breaks the assertion below instead of being
// silently forced thread-safe.

// Compile-fail regression for soundness issue #8: any future field change
// that breaks the Send/Sync claim above fails to compile here.
const _: fn() = || {
    fn assert_send<T: Send>() {}
    fn assert_sync<T: Sync>() {}
    assert_send::<JniProtectCallback>();
    assert_sync::<JniProtectCallback>();
};

// Compile-fail regression for soundness issue #14: see the matching
// block in `ripdpi-android-vpn-protect-adapter::JniProtectCallback`.
// The two callbacks are sibling shims; the soundness argument is the
// same — Copy on a JNI global-ref wrapper would let safe code drop
// `DeleteGlobalRef` twice.
const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfCopy<A> {
        fn check() {}
    }
    impl<T> AmbiguousIfCopy<()> for Check<T> {}
    impl<T: Copy> AmbiguousIfCopy<u8> for Check<T> {}
    <Check<JniProtectCallback> as AmbiguousIfCopy<_>>::check();
};

impl ProtectCallback for JniProtectCallback {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        let result: Result<bool, jni::errors::Error> =
            // Scoped attach (jni 0.22 has no daemon variant): detaches when the callback
            // returns, so this runtime thread is never left permanently attached and can't
            // block JVM teardown.
            self.vm.attach_current_thread_for_scope(|env| -> jni::errors::Result<bool> {
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
            Err(error) => Err(io::Error::other(error.to_string())),
        }
    }
}

pub(crate) fn warp_platform() -> WarpPlatform {
    WarpPlatform::new().with_socket_protector(ripdpi_native_protect::protect_socket_via_callback)
}

/// JNI entry for the WARP `jniRegisterVpnProtect`.
///
/// Returns the generation token the registry stamped on the slot, or `0` on
/// failure. Kotlin threads it back to [`unregister_entry`] so a stale
/// unregister cannot clobber a newer session's callback.
pub(crate) fn register_from_jni(mut env: EnvUnowned<'_>, vpn_service: JObject<'_>) -> i64 {
    match env
        .with_env(|env| -> jni::errors::Result<i64> {
            let vm = env.get_java_vm()?;
            let global_ref: Global<JObject<'static>> = env.new_global_ref(vpn_service)?;
            Ok(register_vpn_protect(&vm, global_ref))
        })
        .into_outcome()
    {
        Outcome::Ok(token) => token,
        Outcome::Err(err) => {
            log::error!("warp VPN protect registration failed: {err}");
            0
        }
        Outcome::Panic(payload) => {
            let msg = payload
                .downcast_ref::<String>()
                .map(String::as_str)
                .or_else(|| payload.downcast_ref::<&str>().copied())
                .unwrap_or("unknown panic");
            log::error!("warp VPN protect registration panicked: {msg}");
            0
        }
    }
}

fn register_vpn_protect(vm: &JavaVM, vpn_service: Global<JObject<'static>>) -> i64 {
    // The single auditable `JavaVM::from_raw` site lives in `SharedJvm::new`.
    let generation =
        register_protect_callback_versioned(Arc::new(JniProtectCallback { vm: SharedJvm::new(vm), vpn_service }));
    // The generation is a monotonic counter from 1; the value fits jlong for
    // the lifetime of any realistic process.
    generation.token() as i64
}

/// JNI entry for the WARP `jniUnregisterVpnProtect`.
///
/// `token` is the value [`register_from_jni`] returned. Clearing is
/// generation-checked: a stale token (a superseded session) or a `0` token (a
/// failed register) is a safe no-op.
pub(crate) fn unregister_entry(token: i64) {
    let generation = ProtectGeneration::from_token(token as u64);
    if !unregister_protect_callback_if(generation) {
        log::info!("warp VPN protect unregister ignored (stale token or no registration)");
    }
}
