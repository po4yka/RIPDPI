use std::sync::{Arc, LazyLock, Mutex};
use std::thread::JoinHandle;

use android_support::{HandleRegistry, clear_android_log_scope_level};
use jni::sys::jlong;
use ripdpi_tunnel_core::Stats;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

use crate::telemetry::TunnelTelemetryState;
use crate::to_handle;

pub(crate) static SESSIONS: LazyLock<HandleRegistry<TunnelSession>> = LazyLock::new(HandleRegistry::new);

pub(crate) struct TunnelSession {
    pub(crate) runtime: Arc<Runtime>,
    pub(crate) config: Arc<ripdpi_tunnel_config::Config>,
    pub(crate) last_error: Arc<Mutex<Option<String>>>,
    pub(crate) telemetry: Arc<TunnelTelemetryState>,
    pub(crate) state: Mutex<TunnelSessionState>,
}

pub(crate) enum TunnelSessionState {
    Ready,
    Starting {
        cancel: Arc<CancellationToken>,
    },
    /// Startup timed out or failed before readiness. The worker join is owned
    /// by a runtime reaper, so JNI teardown remains bounded while its TUN-fd
    /// duplicate is still closed exactly once by the worker.
    CleanupPending {
        cancel: Arc<CancellationToken>,
    },
    Running {
        cancel: Arc<CancellationToken>,
        stats: Arc<Stats>,
        worker: JoinHandle<()>,
    },
    Destroyed,
}

pub(crate) fn lookup_tunnel_session(handle: jlong) -> Result<Arc<TunnelSession>, &'static str> {
    let handle = to_handle(handle).ok_or("Invalid tunnel handle")?;
    SESSIONS.get(handle).ok_or("Unknown tunnel handle")
}

pub(crate) fn remove_tunnel_session(handle: jlong) -> Result<Arc<TunnelSession>, &'static str> {
    let handle = to_handle(handle).ok_or("Invalid tunnel handle")?;
    let session = SESSIONS.remove(handle).ok_or("Unknown tunnel handle")?;
    clear_android_log_scope_level(session.telemetry.log_scope());
    Ok(session)
}
