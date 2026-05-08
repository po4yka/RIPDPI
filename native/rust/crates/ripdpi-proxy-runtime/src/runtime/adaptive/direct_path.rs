use std::io;
use std::net::SocketAddr;
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_proxy_runtime_adapter::model::decision::TransportProtocol;

use crate::runtime::state::RuntimeState;

pub(in crate::runtime) fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|value| value.as_millis() as i64).unwrap_or(0)
}

pub(in crate::runtime) fn note_direct_path_transport_attempt(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
    transport: TransportProtocol,
) -> io::Result<()> {
    state.note_direct_path_transport_attempt(host, targets, transport);
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_udp_suppressed(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    state.note_direct_path_udp_suppressed(host, targets, now_millis().max(0) as u64);
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_udp_failure(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    state.note_direct_path_udp_failure(host, targets);
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_quic_success(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    state.note_direct_path_quic_success(host, targets);
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_tcp_success(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
    strategy_family: Option<&str>,
) -> io::Result<()> {
    state.note_direct_path_tcp_success(host, targets, strategy_family);
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_tls_post_client_hello_failure(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    state.note_direct_path_tls_post_client_hello_failure(host, targets);
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_all_ips_failed(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    state.note_direct_path_all_ips_failed(host, targets);
    Ok(())
}

pub(in crate::runtime) fn emit_due_direct_path_learning_timeouts(state: &RuntimeState) -> io::Result<()> {
    state.emit_due_direct_path_learning_timeouts(now_millis().max(0) as u64);
    Ok(())
}
