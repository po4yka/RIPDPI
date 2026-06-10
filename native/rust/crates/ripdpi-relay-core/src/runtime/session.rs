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
        // Race the session against shutdown. On cancel the `handle_client`
        // future is dropped at its current `.await` (cancel-safe — at worst a
        // half-finished `copy_bidirectional` loses in-flight bytes, acceptable
        // on shutdown); the SOCKS5 success reply is already fully written
        // before `copy_bidirectional` begins, so no half-sent reply is
        // stranded.
        tokio::select! {
            biased;
            () = cancel.cancelled() => {}
            result = handle_client(stream, backend, socks_config, runtime.as_ref()) => {
                if let Err(error) = result {
                    runtime.state.record_error(error.to_string());
                }
            }
        }
        runtime.state.finish_session();
    });
}
