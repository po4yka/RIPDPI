use std::sync::Arc;

use tokio::net::TcpStream;

use super::RelayRuntime;
use crate::backend::RelayBackend;
use crate::socks::{handle_client, SocksSessionConfig};

pub(super) fn spawn_socks_session(runtime: Arc<RelayRuntime>, backend: Arc<RelayBackend>, stream: TcpStream) {
    tokio::spawn(async move {
        runtime.state.start_session();
        let socks_config = SocksSessionConfig {
            local_socks_host: runtime.config.common.local_socks_host.clone(),
            backend_kind: runtime.config.kind_id().to_string(),
        };
        if let Err(error) = handle_client(stream, backend, socks_config, runtime.as_ref()).await {
            runtime.state.record_error(error.to_string());
        }
        runtime.state.finish_session();
    });
}
