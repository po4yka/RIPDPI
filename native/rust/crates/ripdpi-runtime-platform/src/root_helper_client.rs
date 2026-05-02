//! IPC client for the privileged root helper process.
//!
//! Communicates over a Unix domain socket using JSON-line framing with
//! SCM_RIGHTS for file descriptor passing. Framing is owned by
//! `ripdpi-root-helper-protocol`.

use std::io;

mod capabilities;
mod experimental;
mod fragmentation;
mod tcp_payload;
mod transport;

pub use capabilities::capability_outcome_from_probe_json;
use transport::ClientTransport;

/// Client for communicating with the root helper process.
pub struct RootHelperClient {
    transport: ClientTransport,
}

impl RootHelperClient {
    pub fn new(socket_path: String) -> Self {
        Self { transport: ClientTransport::new(socket_path) }
    }

    /// Returns the socket path this client connects to.
    pub fn socket_path(&self) -> String {
        self.transport.socket_path()
    }
}

fn command_params<T: serde::Serialize>(params: T) -> io::Result<serde_json::Value> {
    serde_json::to_value(params).map_err(|error| io::Error::other(format!("serialize root-helper params: {error}")))
}
