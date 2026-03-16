use std::os::fd::AsRawFd;
use std::sync::{Arc, Mutex};

use android_support::{
    init_android_logging, throw_illegal_argument, throw_illegal_state, throw_io_exception, throw_runtime_exception,
    HandleRegistry,
};
use ciadpi_config::RuntimeConfig;
use jni::objects::JString;
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime::{runtime, EmbeddedProxyControl};

use crate::config::{parse_proxy_config_json, runtime_config_envelope_from_payload};
use crate::errors::{extract_panic_message, JniProxyError};
use crate::telemetry::{ProxyTelemetryObserver, ProxyTelemetryState};
use crate::to_handle;

pub(crate) static SESSIONS: once_cell::sync::Lazy<HandleRegistry<ProxySession>> =
    once_cell::sync::Lazy::new(HandleRegistry::new);

pub(crate) struct ProxySession {
    pub(crate) config: RuntimeConfig,
    pub(crate) runtime_context: Option<ProxyRuntimeContext>,
    pub(crate) telemetry: Arc<ProxyTelemetryState>,
    pub(crate) state: Mutex<ProxySessionState>,
}

pub(crate) enum ProxySessionState {
    Idle,
    Running { listener_fd: i32, control: Arc<EmbeddedProxyControl> },
}

pub(crate) fn proxy_create_entry(mut env: JNIEnv, config_json: JString) -> jlong {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| create_session(&mut env, config_json))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session creation panicked: {msg}"));
            0
        },
    )
}

pub(crate) fn proxy_start_entry(mut env: JNIEnv, handle: jlong) -> jint {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| start_session(&mut env, handle))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session start panicked: {msg}"));
            libc::EINVAL
        },
    )
}

pub(crate) fn proxy_stop_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stop_session(&mut env, handle))).map_err(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session stop panicked: {msg}"));
        },
    );
}

pub(crate) fn proxy_poll_telemetry_entry(mut env: JNIEnv, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| poll_proxy_telemetry(&mut env, handle))).unwrap_or_else(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy telemetry polling panicked: {msg}"));
            std::ptr::null_mut()
        },
    )
}

pub(crate) fn proxy_destroy_entry(mut env: JNIEnv, handle: jlong) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| destroy_session(&mut env, handle))).map_err(
        |panic_payload| {
            let msg = extract_panic_message(panic_payload);
            throw_runtime_exception(&mut env, format!("Proxy session destroy panicked: {msg}"));
        },
    );
}

fn create_session(env: &mut JNIEnv, config_json: JString) -> jlong {
    let json: String = match env.get_string(&config_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid proxy config payload");
            return 0;
        }
    };

    let payload = match parse_proxy_config_json(&json) {
        Ok(payload) => payload,
        Err(err) => {
            err.throw(env);
            return 0;
        }
    };

    let envelope = match runtime_config_envelope_from_payload(payload) {
        Ok(envelope) => envelope,
        Err(err) => {
            err.throw(env);
            return 0;
        }
    };
    let config = envelope.config;

    if let Err(err) = runtime::create_listener(&config) {
        JniProxyError::Io(err).throw(env);
        return 0;
    }

    let autolearn_enabled = config.host_autolearn_enabled;
    let telemetry = Arc::new(ProxyTelemetryState::new());
    telemetry.set_autolearn_state(autolearn_enabled, 0, 0);

    SESSIONS.insert(ProxySession {
        config,
        runtime_context: envelope.runtime_context,
        telemetry,
        state: Mutex::new(ProxySessionState::Idle),
    }) as jlong
}

fn start_session(env: &mut JNIEnv, handle: jlong) -> jint {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return libc::EINVAL;
        }
    };

    let config = session.config.clone();
    let (listener, listener_fd) = match open_proxy_listener(&config, &session.telemetry) {
        Ok(parts) => parts,
        Err(err) => {
            throw_io_exception(env, format!("Failed to open proxy listener: {err}"));
            return libc::EINVAL;
        }
    };

    session.telemetry.clear_last_error();
    let control = Arc::new(EmbeddedProxyControl::new_with_context(
        Some(Arc::new(ProxyTelemetryObserver { state: session.telemetry.clone() })),
        session.runtime_context.clone(),
    ));

    {
        let mut state = session.state.lock().expect("proxy session poisoned");
        if let Err(message) = try_mark_proxy_running(&mut state, listener_fd, control.clone()) {
            throw_illegal_state(env, message);
            return libc::EINVAL;
        }
    }

    let result = runtime::run_proxy_with_embedded_control(config, listener, control);

    let mut state = session.state.lock().expect("proxy session poisoned");
    *state = ProxySessionState::Idle;
    if let Err(err) = &result {
        session.telemetry.on_client_error(err.to_string());
    }

    match result {
        Ok(()) => 0,
        Err(err) => positive_os_error(&err, libc::EINVAL),
    }
}

fn stop_session(env: &mut JNIEnv, handle: jlong) {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return;
        }
    };

    let (listener_fd, control) = {
        let state = session.state.lock().expect("proxy session poisoned");
        match listener_fd_for_proxy_stop(&state) {
            Ok(parts) => parts,
            Err(message) => {
                throw_illegal_state(env, message);
                return;
            }
        }
    };

    control.request_shutdown();
    if let Err(err) = shutdown_proxy_listener(listener_fd) {
        throw_io_exception(env, format!("Failed to stop proxy listener: {err}"));
        session.telemetry.on_client_error(err.to_string());
    }
    session.telemetry.push_event("proxy", "info", "stop requested".to_string());
}

fn destroy_session(env: &mut JNIEnv, handle: jlong) {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return;
        }
    };
    let state = session.state.lock().expect("proxy session poisoned");
    if let Err(message) = ensure_proxy_destroyable(&state) {
        throw_illegal_state(env, message);
        return;
    }
    drop(state);
    let _ = remove_proxy_session(handle);
}

pub(crate) fn lookup_proxy_session(handle: jlong) -> Result<Arc<ProxySession>, JniProxyError> {
    let handle = to_handle(handle).ok_or_else(|| JniProxyError::InvalidArgument("Invalid proxy handle".to_string()))?;
    SESSIONS.get(handle).ok_or_else(|| JniProxyError::InvalidArgument("Unknown proxy handle".to_string()))
}

fn poll_proxy_telemetry(env: &mut JNIEnv, handle: jlong) -> jstring {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return std::ptr::null_mut();
        }
    };
    match serde_json::to_string(&session.telemetry.snapshot()) {
        Ok(value) => env.new_string(value).map(jni::objects::JString::into_raw).unwrap_or(std::ptr::null_mut()),
        Err(err) => {
            JniProxyError::Serialization(err).throw(env);
            std::ptr::null_mut()
        }
    }
}

pub(crate) fn remove_proxy_session(handle: jlong) -> Result<Arc<ProxySession>, JniProxyError> {
    let handle = to_handle(handle).ok_or_else(|| JniProxyError::InvalidArgument("Invalid proxy handle".to_string()))?;
    SESSIONS.remove(handle).ok_or_else(|| JniProxyError::InvalidArgument("Unknown proxy handle".to_string()))
}

pub(crate) fn try_mark_proxy_running(
    state: &mut ProxySessionState,
    listener_fd: i32,
    control: Arc<EmbeddedProxyControl>,
) -> Result<(), &'static str> {
    match *state {
        ProxySessionState::Idle => {
            *state = ProxySessionState::Running { listener_fd, control };
            Ok(())
        }
        ProxySessionState::Running { .. } => Err("Proxy session is already running"),
    }
}

pub(crate) fn listener_fd_for_proxy_stop(
    state: &ProxySessionState,
) -> Result<(i32, Arc<EmbeddedProxyControl>), &'static str> {
    match state {
        ProxySessionState::Idle => Err("Proxy session is not running"),
        ProxySessionState::Running { listener_fd, control } => Ok((*listener_fd, control.clone())),
    }
}

pub(crate) fn ensure_proxy_destroyable(state: &ProxySessionState) -> Result<(), &'static str> {
    if matches!(*state, ProxySessionState::Running { .. }) {
        Err("Cannot destroy a running proxy session")
    } else {
        Ok(())
    }
}

fn positive_os_error(err: &std::io::Error, fallback: i32) -> i32 {
    err.raw_os_error().unwrap_or(fallback)
}

pub(crate) fn open_proxy_listener(
    config: &RuntimeConfig,
    telemetry: &ProxyTelemetryState,
) -> Result<(std::net::TcpListener, i32), std::io::Error> {
    match runtime::create_listener(config) {
        Ok(listener) => {
            let listener_fd = listener.as_raw_fd();
            Ok((listener, listener_fd))
        }
        Err(err) => {
            telemetry.on_client_error(format!("listener open failed: {err}"));
            Err(err)
        }
    }
}

pub(crate) fn shutdown_proxy_listener(listener_fd: i32) -> Result<(), std::io::Error> {
    let rc = unsafe { libc::shutdown(listener_fd, libc::SHUT_RDWR) };
    if rc == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}
