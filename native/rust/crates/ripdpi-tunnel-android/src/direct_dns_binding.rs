//! JNI adapter for Android's generation-guarded direct-DNS underlay lease.

use std::io;
use std::os::fd::RawFd;
use std::sync::Arc;

use android_support::SharedJvm;
use android_support::{sanitize_error_message, throw_runtime_exception};
use jni::objects::{JObject, JValue};
use jni::refs::Global;
use jni::{EnvUnowned, Outcome};
use ripdpi_tunnel_core::tunnel_api::direct_dns_binding::{
    DirectDnsBinderGeneration, DirectDnsSocketBinder, register_direct_dns_socket_binder,
    unregister_direct_dns_socket_binder_if,
};

struct JniDirectDnsSocketBinder {
    vm: SharedJvm,
    bridge: Global<JObject<'static>>,
}

impl DirectDnsSocketBinder for JniDirectDnsSocketBinder {
    fn current_generation(&self) -> Option<u64> {
        self.vm
            .attach_current_thread_for_scope(|env| -> jni::errors::Result<i64> {
                env.call_method(&self.bridge, jni::jni_str!("directDnsLeaseGeneration"), jni::jni_sig!("()J"), &[])?.j()
            })
            .ok()
            .and_then(|generation| u64::try_from(generation).ok())
    }

    fn is_current(&self, generation: u64) -> bool {
        let Ok(generation) = i64::try_from(generation) else { return false };
        self.vm
            .attach_current_thread_for_scope(|env| -> jni::errors::Result<bool> {
                env.call_method(
                    &self.bridge,
                    jni::jni_str!("isDirectDnsLeaseCurrent"),
                    jni::jni_sig!("(J)Z"),
                    &[JValue::Long(generation)],
                )?
                .z()
            })
            .unwrap_or(false)
    }

    fn bind_socket(&self, fd: RawFd, generation: u64) -> io::Result<()> {
        let generation = i64::try_from(generation)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid lease generation"))?;
        let result = self.vm.attach_current_thread_for_scope(|env| -> jni::errors::Result<bool> {
            env.call_method(
                &self.bridge,
                jni::jni_str!("bindDirectDnsSocket"),
                jni::jni_sig!("(IJ)Z"),
                &[JValue::Int(fd), JValue::Long(generation)],
            )?
            .z()
        });
        match result {
            Ok(true) => Ok(()),
            Ok(false) => Err(io::Error::new(io::ErrorKind::NotConnected, "direct DNS underlay lease rejected socket")),
            Err(error) => Err(io::Error::other(format!("direct DNS Android binder failed: {error}"))),
        }
    }
}

pub(crate) fn register_from_jni(mut env: EnvUnowned<'_>, bridge: JObject<'_>) -> i64 {
    match env
        .with_env(|env| -> jni::errors::Result<i64> {
            let vm = env.get_java_vm()?;
            let class = env.get_object_class(&bridge)?;
            env.get_method_id(&class, jni::jni_str!("directDnsLeaseGeneration"), jni::jni_sig!("()J"))?;
            env.get_method_id(&class, jni::jni_str!("isDirectDnsLeaseCurrent"), jni::jni_sig!("(J)Z"))?;
            env.get_method_id(&class, jni::jni_str!("bindDirectDnsSocket"), jni::jni_sig!("(IJ)Z"))?;
            let bridge = env.new_global_ref(bridge)?;
            let binder = Arc::new(JniDirectDnsSocketBinder { vm: SharedJvm::new(&vm), bridge });
            register_direct_dns_socket_binder(binder)
                .map(DirectDnsBinderGeneration::token)
                .ok_or(jni::errors::Error::JniCall(jni::errors::JniError::InvalidArguments))
        })
        .into_outcome()
    {
        Outcome::Ok(token) => token,
        Outcome::Err(error) => {
            tracing::error!("direct DNS binder registration failed: {error}");
            throw_runtime_exception(
                &mut env,
                sanitize_error_message(&error.to_string(), "Direct DNS binder registration failed"),
            );
            0
        }
        Outcome::Panic(_) => {
            tracing::error!("direct DNS binder registration panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Direct DNS binder registration failed"));
            0
        }
    }
}

pub(crate) fn unregister(generation: DirectDnsBinderGeneration) {
    let _ = unregister_direct_dns_socket_binder_if(generation);
}
