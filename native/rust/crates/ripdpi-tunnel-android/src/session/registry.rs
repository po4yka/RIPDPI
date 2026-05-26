use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use android_support::{clear_android_log_scope_level, HandleRegistry};
use jni::sys::jlong;
use once_cell::sync::Lazy;
use ripdpi_tunnel_core::Stats;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

use crate::telemetry::TunnelTelemetryState;
use crate::to_handle;

pub(crate) static SESSIONS: Lazy<HandleRegistry<TunnelSession>> = Lazy::new(HandleRegistry::new);

pub(crate) struct TunnelSession {
    pub(crate) runtime: Arc<Runtime>,
    pub(crate) config: Arc<ripdpi_tunnel_config::Config>,
    pub(crate) last_error: Arc<Mutex<Option<String>>>,
    pub(crate) telemetry: Arc<TunnelTelemetryState>,
    pub(crate) state: Mutex<TunnelSessionState>,
}

pub(crate) enum TunnelSessionState {
    Ready,
    Starting { cancel: Arc<CancellationToken> },
    Running { cancel: Arc<CancellationToken>, stats: Arc<Stats>, worker: JoinHandle<()> },
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

/// If the session at `handle` is in the `Running` state, clone its
/// `Arc<Stats>` and return it. Returns `None` if the handle is unknown
/// OR the session is not currently running (Ready / Starting /
/// Destroyed). Used by the PCAP JNI bridge to attach a packet observer
/// onto a session that is already pumping packets.
pub(crate) fn lookup_stats_for_session(handle: jlong) -> Option<Arc<Stats>> {
    let session = lookup_tunnel_session(handle).ok()?;
    let state = session.state.lock().ok()?;
    match &*state {
        TunnelSessionState::Running { stats, .. } => Some(stats.clone()),
        _ => None,
    }
}
