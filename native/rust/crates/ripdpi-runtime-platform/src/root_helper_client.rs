//! Runtime-adaptation — IPC client for the privileged root helper process.
//!
//! Communicates over a Unix domain socket using length-prefixed JSON frames with
//! SCM_RIGHTS for file descriptor passing. Framing is owned by
//! `ripdpi-root-helper-protocol`. The runtime-adaptation modules
//! (`fake_send`, `ip_fragmentation`, `experimental_tier3`) reach a live
//! `RootHelperClient` only through the [`root_helper`](crate::root_helper)
//! registry, never by constructing one directly. Linux/Android only.

use std::io;

mod capabilities;
mod experimental;
mod fragmentation;
mod injection_breaker;
mod tcp_payload;
mod transport;

pub use capabilities::capability_outcome_from_probe_json;
pub(crate) use injection_breaker::is_injection_paused;
use injection_breaker::{INJECTION_BREAKER_COOLDOWN, InjectionBreaker};
use transport::ClientTransport;
pub(crate) use transport::is_not_delivered;

/// Client for communicating with the root helper process.
pub struct RootHelperClient {
    transport: ClientTransport,
    injection_breaker: InjectionBreaker,
}

impl RootHelperClient {
    pub fn new(socket_path: String) -> Self {
        Self::with_injection_cooldown(socket_path, INJECTION_BREAKER_COOLDOWN)
    }

    fn with_injection_cooldown(socket_path: String, cooldown: std::time::Duration) -> Self {
        Self { transport: ClientTransport::new(socket_path), injection_breaker: InjectionBreaker::new(cooldown) }
    }

    /// Returns the socket path this client connects to.
    pub fn socket_path(&self) -> String {
        self.transport.socket_path()
    }
}

fn command_params<T: serde::Serialize>(params: T) -> io::Result<serde_json::Value> {
    serde_json::to_value(params).map_err(|error| io::Error::other(format!("serialize root-helper params: {error}")))
}
