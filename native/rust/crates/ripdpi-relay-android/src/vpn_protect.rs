use std::io;
use std::net::IpAddr;
use std::os::fd::RawFd;
use std::sync::Arc;

use android_support::SharedJvm;
use jni::objects::{JObject, JObjectArray, JString, JValue};
use jni::refs::Global;
use jni::{EnvUnowned, JavaVM, Outcome};
use ripdpi_native_protect::{
    ProtectCallback, ProtectGeneration, register_protect_callback_versioned, unregister_protect_callback_if,
};

#[cfg(all(test, not(target_os = "android")))]
mod tests;

struct JniProtectCallback {
    vm: SharedJvm,
    vpn_service: Global<JObject<'static>>,
}

const _: fn() = || {
    fn assert_send<T: Send>() {}
    fn assert_sync<T: Sync>() {}
    assert_send::<JniProtectCallback>();
    assert_sync::<JniProtectCallback>();
};

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

    fn resolve_host(&self, host: &str) -> io::Result<Vec<IpAddr>> {
        // The service resolves on its current underlay and rejects a result if
        // that network changes during lookup. Never fall back to system DNS:
        // in VPN mode it can route the relay bootstrap lookup into its own TUN.
        let result: jni::errors::Result<Vec<String>> = self.vm.attach_current_thread_for_scope(|env| {
            let host = env.new_string(host)?;
            let value = env.call_method(
                &self.vpn_service,
                jni::jni_str!("resolveHost"),
                jni::jni_sig!("(Ljava/lang/String;)[Ljava/lang/String;"),
                &[JValue::Object(&host)],
            )?;
            let array = JObjectArray::<JString>::cast_local(env, value.l()?)?;
            let count = array.len(env)?;
            let mut addresses = Vec::with_capacity(count);
            for index in 0..count {
                addresses.push(env.with_local_frame(4, |env| -> jni::errors::Result<String> {
                    let value = array.get_element(env, index)?;
                    Ok(value.mutf8_chars(env)?.to_str().into_owned())
                })?);
            }
            Ok(addresses)
        });
        // Scoped attachment clears Java exceptions, including a stale-underlay
        // rejection. Do not expose the exception text: it can contain the host.
        let addresses = result.map_err(|_| io::Error::other("Android underlying-network hostname lookup failed"))?;
        if addresses.is_empty() {
            return Err(io::Error::new(io::ErrorKind::AddrNotAvailable, "relay hostname resolved to no addresses"));
        }
        addresses
            .into_iter()
            .map(|address| {
                address.parse().map_err(|_| {
                    io::Error::new(io::ErrorKind::InvalidData, "Android hostname lookup returned an invalid IP address")
                })
            })
            .collect()
    }
}

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
            log::error!("relay VPN protect registration failed: {err}");
            0
        }
        Outcome::Panic(payload) => {
            let msg = payload
                .downcast_ref::<String>()
                .map(String::as_str)
                .or_else(|| payload.downcast_ref::<&str>().copied())
                .unwrap_or("unknown panic");
            log::error!("relay VPN protect registration panicked: {msg}");
            0
        }
    }
}

fn register_vpn_protect(vm: &JavaVM, vpn_service: Global<JObject<'static>>) -> i64 {
    let generation =
        register_protect_callback_versioned(Arc::new(JniProtectCallback { vm: SharedJvm::new(vm), vpn_service }));
    generation.token() as i64
}

pub(crate) fn unregister_entry(token: i64) {
    let generation = ProtectGeneration::from_token(token as u64);
    if !unregister_protect_callback_if(generation) {
        log::info!("relay VPN protect unregister ignored (stale token or no registration)");
    }
}
