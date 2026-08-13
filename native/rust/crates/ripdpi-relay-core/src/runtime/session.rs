use std::sync::Arc;

use tokio::net::TcpStream;
use tokio::sync::OwnedSemaphorePermit;
use tracing::Instrument;

use super::RelayRuntime;
use crate::backend::RelayBackend;
use crate::socks::{SocksSessionConfig, handle_client};

pub(super) fn spawn_socks_session(
    runtime: Arc<RelayRuntime>,
    backend: Arc<RelayBackend>,
    stream: TcpStream,
    permit: OwnedSemaphorePermit,
) {
    // Child of the runtime shutdown token: cancelled by `RelayRuntime::stop()`
    // so this session unwinds promptly instead of leaking its upstream
    // connection and fds until the process exits.
    let cancel = runtime.state.session_cancel_token();
    // Register both the join and abort handles so shutdown can escalate from
    // cooperative cancellation to forced task abortion after the grace window.
    let session_runtime = Arc::clone(&runtime);
    let runtime_span = tracing::Span::current();
    runtime.state.spawn_session_task(
        async move {
            // Cancellation or task exit drops the owned permit and releases admission capacity.
            let _permit = permit;
            let _active_session = session_runtime.state.start_session();
            let socks_config = SocksSessionConfig {
                local_socks_host: session_runtime.config.common.local_socks_host.clone(),
                backend_kind: session_runtime.config.kind_id().to_string(),
                confirm_good_eligible: session_runtime.confirm_good_dpi_eligible(),
            };
            // `handle_client` owns the shutdown token and honors it at the right
            // boundaries: it abandons pre-reply negotiation by drop, but once a
            // SOCKS5 success reply is on the wire it switches to a *graceful* cancel
            // (FIN, not an abrupt drop), so shutdown can never strand a confirmed
            // CONNECT/ASSOCIATE on a relay that never started. We therefore await it
            // directly instead of racing it against `cancel` here — a drop-on-cancel
            // `select!` at this layer is exactly what created that orphan window.
            match handle_client(stream, backend, socks_config, session_runtime.as_ref(), cancel).await {
                Ok(()) => session_runtime.state.record_session_success(),
                Err(error) => session_runtime.state.record_error(error.to_string()),
            }
        }
        .instrument(runtime_span),
    );
}
