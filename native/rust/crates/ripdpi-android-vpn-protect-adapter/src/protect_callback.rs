use std::io;
use std::net::IpAddr;
use std::os::fd::RawFd;

use android_support::SharedJvm;
use jni::objects::{JObject, JObjectArray, JString, JValue};
use jni::refs::Global;
use ripdpi_native_protect::ProtectCallback;

pub(crate) struct JniProtectCallback {
    pub(crate) vm: SharedJvm,
    pub(crate) vpn_service: Global<JObject<'static>>,
}

// `JniProtectCallback` auto-derives `Send + Sync`: both fields — `vm: SharedJvm`
// (`Arc<JavaVM>`) and `vpn_service: Global<JObject<'static>>` — are themselves
// `Send + Sync` in jni 0.22. `protect()` only calls `attach_current_thread`
// (obtaining its own thread-local `JNIEnv` per call) and reads the global ref; no
// `JNIEnv`/`AttachGuard` is ever stored. Relying on the auto-derive instead of a
// manual `unsafe impl` keeps the compiler's `!Send`/`!Sync`-field tripwire intact —
// a future non-thread-safe field breaks the `assert_send`/`assert_sync` guards in
// `lib.rs` instead of being silently forced thread-safe.

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

    fn resolve_host(&self, host: &str) -> io::Result<Vec<IpAddr>> {
        let result: Result<Vec<IpAddr>, jni::errors::Error> = self.vm.attach_current_thread_for_scope(|env| {
            let host = env.new_string(host)?;
            let value = env.call_method(
                &self.vpn_service,
                jni::jni_str!("resolveHost"),
                jni::jni_sig!("(Ljava/lang/String;)[Ljava/lang/String;"),
                &[JValue::Object(&host)],
            )?;
            let array = JObjectArray::<JString>::cast_local(env, value.l()?)?;
            let mut addresses = Vec::with_capacity(array.len(env)?);
            for index in 0..array.len(env)? {
                let value = array.get_element(env, index)?;
                let text = value.mutf8_chars(env)?.to_str().into_owned();
                if let Ok(ip) = text.parse() {
                    addresses.push(ip);
                }
            }
            Ok(addresses)
        });
        result.map_err(|error| io::Error::other(format!("resolve relay hostname via Android network: {error}")))
    }
}
