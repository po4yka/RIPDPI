use std::collections::BTreeMap;
use std::io;
use std::net::SocketAddr;

use ripdpi_runtime_policy::runtime_policy::{RetrySelectionPenalty, TransportProtocol};

/// Retry pacing port for reconnect backoff and retry-selection penalties.
pub trait RetryPacingPort: Send + Sync {
    fn note_retry_success(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
    ) -> io::Result<()>;

    fn note_retry_failure(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<()>;

    fn build_retry_penalties(
        &self,
        target: SocketAddr,
        host: Option<&str>,
        payload: Option<&[u8]>,
        transport: TransportProtocol,
        now_ms: u64,
    ) -> io::Result<BTreeMap<usize, RetrySelectionPenalty>>;

    fn apply_retry_pacing(
        &self,
        target: SocketAddr,
        group_index: usize,
        host: Option<&str>,
        payload: Option<&[u8]>,
        now_ms: u64,
        on_paced: &dyn Fn(SocketAddr, usize, &'static str, u64),
    ) -> io::Result<()>;
}
