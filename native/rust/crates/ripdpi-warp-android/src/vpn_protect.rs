use std::io;
use std::os::fd::RawFd;
use std::sync::Arc;

use jni::objects::{JObject, JValue};
use jni::refs::Global;
use jni::{EnvUnowned, JavaVM, Outcome};
use ripdpi_native_protect::{register_protect_callback, unregister_protect_callback, ProtectCallback};
use ripdpi_warp_core::WarpPlatform;

struct JniProtectCallback {
    vm: JavaVM,
    vpn_service: Global<JObject<'static>>,
}

// SAFETY: JavaVM is Send+Sync (just a *mut sys::JavaVM wrapper).
// Global<JObject<'static>> prevents the JVM from GC-collecting the Java
// object and is safe to use from any thread via attach_current_thread.
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
            Err(error) => Err(io::Error::other(error.to_string())),
        }
    }
}

pub(crate) fn warp_platform() -> WarpPlatform {
    WarpPlatform::new().with_socket_protector(ripdpi_native_protect::protect_socket_via_callback)
}

pub(crate) fn register_from_jni(mut env: EnvUnowned<'_>, vpn_service: JObject<'_>) {
    match env
        .with_env(|env| -> jni::errors::Result<()> {
            let vm = env.get_java_vm()?;
            let global_ref: Global<JObject<'static>> = env.new_global_ref(vpn_service)?;
            register_vpn_protect(&vm, global_ref);
            Ok(())
        })
        .into_outcome()
    {
        Outcome::Ok(()) => {}
        Outcome::Err(err) => {
            log::error!("warp VPN protect registration failed: {err}");
        }
        Outcome::Panic(payload) => {
            let msg = payload
                .downcast_ref::<String>()
                .map(String::as_str)
                .or_else(|| payload.downcast_ref::<&str>().copied())
                .unwrap_or("unknown panic");
            log::error!("warp VPN protect registration panicked: {msg}");
        }
    }
}

fn register_vpn_protect(vm: &JavaVM, vpn_service: Global<JObject<'static>>) {
    // SAFETY: JavaVM pointer is held live by JNI_OnLoad registration for the duration of the process.
    // Re-creating a JavaVM from the raw pointer copies only the thin pointer wrapper; no double-free risk.
    let vm_clone = unsafe { JavaVM::from_raw(vm.get_raw()) };
    register_protect_callback(Arc::new(JniProtectCallback { vm: vm_clone, vpn_service }));
}

pub(crate) fn unregister_entry() {
    unregister_protect_callback();
}
