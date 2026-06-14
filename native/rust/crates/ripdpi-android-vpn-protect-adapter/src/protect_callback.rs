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

// SAFETY: The two fields are the only state moved across the thread boundary.
// - `vm: JavaVM` is documented by the `jni` crate as safe to move between
//   threads: it represents the process-global JNI invocation interface, and the
//   thread that ends up owning the value obtains its own `JNIEnv` via
//   `attach_current_thread` (see `protect`). No `JNIEnv`/`AttachGuard` is ever
//   stored in this struct, so the non-`Send` thread-locality of those types
//   never escapes.
// - `vpn_service: Global<JObject<'static>>` is a JNI global reference: the Java
//   object it names is pinned alive by the VM regardless of which thread holds
//   the handle, and transferring the handle is a plain pointer move.
// The auto-`Send` impl is blocked only because the raw JNI handle types are
// conservatively `!Send`; the invariants above make the move sound.
unsafe impl Send for JniProtectCallback {}
// SAFETY: shared (`&self`) access goes through `protect`, which (1) calls
// `attach_current_thread` to obtain a thread-local `JNIEnv` — the JNI invocation
// interface (`JavaVM`) is explicitly designed for concurrent attach from any
// thread — and (2) only reads `vpn_service` to issue a JNI method call; it never
// mutates either field. `JObject` global references are safe to dereference
// concurrently. No interior mutability or non-`Sync` state is reachable through
// `&JniProtectCallback`, so `&self` is sound to share across threads. The
// auto-`Sync` impl is blocked only by the conservative `!Sync` raw JNI handles.
unsafe impl Sync for JniProtectCallback {}

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
            Err(e) => Err(io::Error::other(e.to_string())),
        }
    }
}
