use android_support::{sanitize_error_message, throw_runtime_exception};
use jni::EnvUnowned;
use jni::Outcome;
use jni::sys::jint;

const PROBE_UNAVAILABLE: jint = 0;
pub(crate) const PROBE_BRIDGE_FAILURE: jint = -1;
#[cfg(any(target_os = "android", target_os = "linux", test))]
const PROBE_SUPPORTED: jint = 1;
#[cfg(any(target_os = "android", target_os = "linux", test))]
const PROBE_PERMISSION_DENIED: jint = 2;

/// JNI entry for the control-plane-only unprivileged `SO_BINDTODEVICE` probe.
///
/// The socket is never connected and its interface name and errno remain inside
/// this native frame. Kotlin receives only a categorical result.
pub(crate) fn bind_to_device_probe_entry(mut env: EnvUnowned<'_>) -> jint {
    bind_to_device_probe_entry_with(&mut env, probe_unprivileged_bind_to_device)
}

fn bind_to_device_probe_entry_with(env: &mut EnvUnowned<'_>, probe: impl FnOnce() -> jint) -> jint {
    match env.with_env(move |_env| -> jni::errors::Result<jint> { Ok(probe()) }).into_outcome() {
        Outcome::Ok(result) => result,
        Outcome::Err(err) => {
            log::error!("Unprivileged bind-to-device probe failed: {err}");
            throw_runtime_exception(
                env,
                sanitize_error_message(&err.to_string(), "Unprivileged bind-to-device probe failed"),
            );
            PROBE_UNAVAILABLE
        }
        Outcome::Panic(_) => {
            log::error!("Unprivileged bind-to-device probe panicked");
            PROBE_BRIDGE_FAILURE
        }
    }
}

#[cfg(all(test, not(feature = "loom")))]
pub(crate) fn bind_to_device_probe_panic_entry_for_test(mut env: EnvUnowned<'_>) -> jint {
    bind_to_device_probe_entry_with(&mut env, || panic!("injected bind-to-device probe panic"))
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn probe_unprivileged_bind_to_device() -> jint {
    use std::ffi::OsString;

    use nix::sys::socket::{AddressFamily, SockFlag, SockType, setsockopt, socket, sockopt};

    let Ok(socket) = socket(AddressFamily::Inet, SockType::Stream, SockFlag::SOCK_CLOEXEC, None) else {
        return PROBE_UNAVAILABLE;
    };
    let loopback = OsString::from("lo");
    classify_bind_result(setsockopt(&socket, sockopt::BindToDevice, &loopback))
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn probe_unprivileged_bind_to_device() -> jint {
    PROBE_UNAVAILABLE
}

#[cfg(any(target_os = "android", target_os = "linux", test))]
fn classify_bind_result(result: Result<(), nix::errno::Errno>) -> jint {
    match result {
        Ok(()) => PROBE_SUPPORTED,
        Err(nix::errno::Errno::EPERM | nix::errno::Errno::EACCES) => PROBE_PERMISSION_DENIED,
        Err(_) => PROBE_UNAVAILABLE,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bind_probe_result_is_categorical_and_does_not_export_errno() {
        assert_eq!(classify_bind_result(Ok(())), PROBE_SUPPORTED);
        assert_eq!(classify_bind_result(Err(nix::errno::Errno::EPERM)), PROBE_PERMISSION_DENIED);
        assert_eq!(classify_bind_result(Err(nix::errno::Errno::EACCES)), PROBE_PERMISSION_DENIED);
        assert_eq!(classify_bind_result(Err(nix::errno::Errno::EINVAL)), PROBE_UNAVAILABLE);
    }
}
