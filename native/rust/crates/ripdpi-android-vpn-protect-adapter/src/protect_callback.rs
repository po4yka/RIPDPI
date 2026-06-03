use std::io;
use std::os::fd::RawFd;

use jni::JavaVM;
use jni::objects::{JObject, JValue};
use jni::refs::Global;
use ripdpi_native_protect::ProtectCallback;

pub(crate) struct JniProtectCallback {
    pub(crate) vm: JavaVM,
    pub(crate) vpn_service: Global<JObject<'static>>,
}

// SAFETY: The only shared state across threads is `vm: JavaVM` and
// `vpn_service: Global<JObject<'static>>`.
// - `JavaVM` is documented by the `jni` crate as safe to move and share between
//   threads: it represents the process-global JNI invocation interface, and each
//   thread obtains its own `JNIEnv` via `attach_current_thread` (see `protect`
//   below). No `JNIEnv`/`AttachGuard` is ever stored in this struct, so the
//   non-`Send` thread-locality of those types never escapes.
// - `Global<JObject<'static>>` is a JNI global reference: it is created with the
//   VM and remains valid (the Java object is pinned alive) regardless of which
//   thread dereferences it, and reading it is a plain pointer copy.
// `protect` attaches the calling thread on every invocation and only reads the
// global reference, so concurrent use from multiple threads is sound. Hence the
// manual `Send`/`Sync` impls hold (the auto-impls are blocked only because raw
// JNI handle types are conservatively `!Send`/`!Sync`).
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
            Err(e) => Err(io::Error::other(e.to_string())),
        }
    }
}
