use std::sync::Arc;
use std::time::Duration;

use tokio::net::TcpListener;
use tokio::time::timeout;

use super::RelayRuntime;
use super::session::spawn_socks_session;
use crate::backend::RelayBackend;

pub(super) async fn run_accept_loop(
    runtime: Arc<RelayRuntime>,
    backend: Arc<RelayBackend>,
    listener: TcpListener,
    accept_poll_interval: Duration,
) {
    while !runtime.state.stop_requested() {
        match timeout(accept_poll_interval, listener.accept()).await {
            Ok(Ok((stream, _))) => spawn_socks_session(Arc::clone(&runtime), Arc::clone(&backend), stream),
            Ok(Err(error)) => runtime.state.record_error(error.to_string()),
            Err(_) => {}
        }
    }
}
