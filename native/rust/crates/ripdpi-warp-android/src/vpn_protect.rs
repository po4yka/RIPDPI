use std::io;
use std::os::fd::RawFd;
use std::sync::Arc;

use jni::objects::{JObject, JValue};
use jni::refs::Global;
use jni::JavaVM;
use ripdpi_native_protect::{register_protect_callback, unregister_protect_callback, ProtectCallback};

struct JniProtectCallback {
    vm: JavaVM,
    vpn_service: Global<JObject<'static>>,
}

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

pub(crate) fn register_vpn_protect(vm: &JavaVM, vpn_service: Global<JObject<'static>>) {
    // SAFETY: JavaVM pointer is held live by JNI_OnLoad registration for the duration of the process.
    let vm_clone = unsafe { JavaVM::from_raw(vm.get_raw()) };
    register_protect_callback(Arc::new(JniProtectCallback { vm: vm_clone, vpn_service }));
}

pub(crate) fn unregister_vpn_protect() {
    unregister_protect_callback();
}
