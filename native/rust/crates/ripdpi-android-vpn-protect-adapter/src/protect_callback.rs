use std::io;
use std::os::fd::RawFd;

use jni::objects::{JObject, JValue};
use jni::refs::Global;
use jni::JavaVM;
use ripdpi_native_protect::ProtectCallback;

pub(crate) struct JniProtectCallback {
    pub(crate) vm: JavaVM,
    pub(crate) vpn_service: Global<JObject<'static>>,
}

// SAFETY: JavaVM is Send+Sync and Global<JObject<'static>> keeps the Java object alive across threads.
unsafe impl Send for JniProtectCallback {}
// SAFETY: protect() only attaches the current thread and reads the global object reference.
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
