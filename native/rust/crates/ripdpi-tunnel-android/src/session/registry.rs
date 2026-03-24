use std::io;
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use android_support::{clear_android_log_scope_level, HandleRegistry};
use jni::sys::jlong;
use once_cell::sync::{Lazy, OnceCell};
use ripdpi_tunnel_core::Stats;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

use crate::telemetry::TunnelTelemetryState;
use crate::to_handle;

pub(crate) static SESSIONS: Lazy<HandleRegistry<TunnelSession>> = Lazy::new(HandleRegistry::new);
static SHARED_TUNNEL_RUNTIME: OnceCell<Arc<Runtime>> = OnceCell::new();

pub(crate) struct TunnelSession {
    pub(crate) runtime: Arc<Runtime>,
    pub(crate) config: Arc<ripdpi_tunnel_config::Config>,
    pub(crate) last_error: Arc<Mutex<Option<String>>>,
    pub(crate) telemetry: Arc<TunnelTelemetryState>,
    pub(crate) state: Mutex<TunnelSessionState>,
}

pub(crate) enum TunnelSessionState {
    Ready,
    Starting,
    Running { cancel: Arc<CancellationToken>, stats: Arc<Stats>, worker: JoinHandle<()> },
}

fn build_shared_tunnel_runtime() -> io::Result<Arc<Runtime>> {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_stack_size(1024 * 1024)
        .thread_name("ripdpi-tunnel-tokio")
        .enable_all()
        .build()
        .map(Arc::new)
}

pub(crate) fn shared_tunnel_runtime() -> io::Result<Arc<Runtime>> {
    SHARED_TUNNEL_RUNTIME.get_or_try_init(build_shared_tunnel_runtime).map(Arc::clone)
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
