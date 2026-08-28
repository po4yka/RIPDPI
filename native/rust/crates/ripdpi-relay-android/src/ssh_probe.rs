//! Call-scoped SSH host-key observation. No credentials or global VPN callback
//! registration cross this boundary; the service owns the socket controller.

use std::io;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::RawFd;
use std::sync::Arc;
use std::time::Duration;

use android_support::SharedJvm;
use jni::objects::{JObject, JObjectArray, JString, JValue};
use jni::refs::Global;
use jni::{Env, EnvUnowned, Outcome};
use ripdpi_native_protect::ProtectCallback;
use ripdpi_ssh::{SshHostKeyProbeError, probe_host_key};

struct SocketController {
    vm: SharedJvm,
    controller: Global<JObject<'static>>,
}

impl ProtectCallback for SocketController {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        let result: jni::errors::Result<bool> = self.vm.attach_current_thread_for_scope(|env| {
            let result = env
                .call_method(
                    &self.controller,
                    jni::jni_str!("protectSocket"),
                    jni::jni_sig!("(I)Z"),
                    &[JValue::Int(fd)],
                )
                .and_then(jni::JValueOwned::z);
            // Callback exceptions are a denied protection attempt, never a
            // pending Java exception carried into the next JNI operation.
            if env.exception_check() {
                env.exception_clear();
                return Err(jni::errors::Error::JavaException);
            }
            result
        });
        match result {
            Ok(true) => Ok(()),
            _ => {
                Err(io::Error::new(io::ErrorKind::PermissionDenied, "SSH probe socket controller rejected protection"))
            }
        }
    }
}

struct PreparedProbe {
    address: SocketAddr,
    timeout: Duration,
    controller: Arc<dyn ProtectCallback>,
}

fn prepare(
    env: &mut Env<'_>,
    address: &JString<'_>,
    port: i32,
    timeout_millis: i32,
    controller: &JObject<'_>,
    output: &JObjectArray<'_>,
) -> jni::errors::Result<Result<PreparedProbe, SshHostKeyProbeError>> {
    if output.is_null() || output.len(env)? != 2 {
        return Ok(Err(SshHostKeyProbeError::InvalidInput));
    }
    output.set_element(env, 0, JObject::null())?;
    output.set_element(env, 1, JObject::null())?;
    if address.is_null()
        || controller.is_null()
        || !(1..=65535).contains(&port)
        || !(1..=30000).contains(&timeout_millis)
    {
        return Ok(Err(SshHostKeyProbeError::InvalidInput));
    }
    // Bound allocation before converting a foreign string. Only IP literals
    // are accepted; Android resolves using the captured underlying Network.
    let length = env.call_method(address, jni::jni_str!("length"), jni::jni_sig!("()I"), &[])?.i()?;
    if !(1..=64).contains(&length) {
        return Ok(Err(SshHostKeyProbeError::InvalidInput));
    }
    let Ok(ip) = address.try_to_string(env)?.parse::<IpAddr>() else {
        return Ok(Err(SshHostKeyProbeError::InvalidInput));
    };
    let controller = Arc::new(SocketController {
        vm: SharedJvm::new(&env.get_java_vm()?),
        controller: env.new_global_ref(controller)?,
    });
    Ok(Ok(PreparedProbe {
        address: SocketAddr::new(ip, port as u16),
        timeout: Duration::from_millis(timeout_millis as u64),
        controller,
    }))
}

pub(crate) fn probe_entry(
    mut env: EnvUnowned<'_>,
    address: JString<'_>,
    port: i32,
    timeout_millis: i32,
    controller: JObject<'_>,
    output: JObjectArray<'_>,
) -> i32 {
    let prepared =
        env.with_env(|env| prepare(env, &address, port, timeout_millis, &controller, &output)).into_outcome();
    let prepared = match prepared {
        Outcome::Ok(Ok(prepared)) => prepared,
        Outcome::Ok(Err(error)) => return status_code(error),
        _ => {
            clear_pending(&mut env);
            return 6;
        }
    };
    // No Env scope spans block_on. The owned VM/global reference is released
    // only after probe_host_key destroys all work on its private runtime.
    let observation = match probe_host_key(prepared.address, prepared.timeout, prepared.controller) {
        Ok(observation) => observation,
        Err(error) => return status_code(error),
    };
    let written = env
        .with_env(|env| -> jni::errors::Result<()> {
            let fingerprint = JString::from_str(env, &observation.fingerprint_sha256)?;
            let algorithm = JString::from_str(env, &observation.algorithm)?;
            output.set_element(env, 0, &fingerprint)?;
            output.set_element(env, 1, &algorithm)?;
            Ok(())
        })
        .into_outcome();
    if matches!(written, Outcome::Ok(())) {
        0
    } else {
        clear_pending(&mut env);
        let _ = env.with_env(|env| -> jni::errors::Result<()> {
            output.set_element(env, 0, JObject::null())?;
            output.set_element(env, 1, JObject::null())?;
            Ok(())
        });
        clear_pending(&mut env);
        6
    }
}

fn clear_pending(env: &mut EnvUnowned<'_>) {
    let _ = env.with_env(|env| -> jni::errors::Result<()> {
        if env.exception_check() {
            env.exception_clear();
        }
        Ok(())
    });
}

fn status_code(error: SshHostKeyProbeError) -> i32 {
    match error {
        SshHostKeyProbeError::InvalidInput => 1,
        SshHostKeyProbeError::Timeout => 2,
        SshHostKeyProbeError::ConnectFailed => 3,
        SshHostKeyProbeError::HandshakeFailed => 4,
        SshHostKeyProbeError::ProtectionDenied => 5,
        SshHostKeyProbeError::InternalFailure => 6,
    }
}
