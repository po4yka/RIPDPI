use std::sync::Arc;

use tokio::net::TcpStream;

use super::RelayRuntime;
use crate::backend::RelayBackend;
use crate::socks::{SocksSessionConfig, handle_client};

pub(super) fn spawn_socks_session(runtime: Arc<RelayRuntime>, backend: Arc<RelayBackend>, stream: TcpStream) {
    // Child of the runtime shutdown token: cancelled by `RelayRuntime::stop()`
    // so this session unwinds promptly instead of leaking its upstream
    // connection and fds until the process exits.
    let cancel = runtime.state.session_cancel_token();
    // Spawn onto the runtime's `TaskTracker` so `stop()`/`drain_sessions` can
    // join every in-flight session within a bounded grace window.
    runtime.state.clone_tracker().spawn(async move {
        runtime.state.start_session();
        let socks_config = SocksSessionConfig {
            local_socks_host: runtime.config.common.local_socks_host.clone(),
            backend_kind: runtime.config.kind_id().to_string(),
        };
        // `handle_client` owns the shutdown token and honors it at the right
        // boundaries: it abandons pre-reply negotiation by drop, but once a
        // SOCKS5 success reply is on the wire it switches to a *graceful* cancel
        // (FIN, not an abrupt drop), so shutdown can never strand a confirmed
        // CONNECT/ASSOCIATE on a relay that never started. We therefore await it
        // directly instead of racing it against `cancel` here — a drop-on-cancel
        // `select!` at this layer is exactly what created that orphan window.
        if let Err(error) = handle_client(stream, backend, socks_config, runtime.as_ref(), cancel).await {
            runtime.state.record_error(error.to_string());
        }
        runtime.state.finish_session();
    });
}
