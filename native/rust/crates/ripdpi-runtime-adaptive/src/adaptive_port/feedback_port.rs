use std::io;
use std::net::SocketAddr;

use ripdpi_config::RuntimeConfig;
use ripdpi_failure_classifier::FailureClass;

/// Feedback port for adaptive tuning, fake-TTL, and strategy evolution.
pub trait AdaptiveFeedbackPort: Send + Sync {
    fn note_tcp_success(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_tcp_failure(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_udp_success(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_udp_failure(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        payload: &[u8],
    ) -> io::Result<()>;

    fn note_fake_ttl_success(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
    ) -> io::Result<()>;

    fn note_fake_ttl_failure(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
    ) -> io::Result<()>;

    fn note_server_ttl(
        &self,
        scope_key: Option<&str>,
        group_index: usize,
        target: SocketAddr,
        host: Option<&str>,
        observed_ttl: u8,
    ) -> io::Result<()>;

    fn note_evolver_failure(&self, class: FailureClass);
    fn note_evolver_success(&self);
    fn note_evolver_connect_failure(&self);
    fn reset_evolver(&self);
    fn clear_adaptive_tuning(&self);
    fn flush_adaptive_store(&self, config: &RuntimeConfig);
}
