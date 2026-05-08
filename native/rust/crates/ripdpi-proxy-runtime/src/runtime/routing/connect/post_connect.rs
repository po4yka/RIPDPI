use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::model::config::TcpRouteConnectSettings;
use ripdpi_proxy_runtime_adapter::platform::connect as connect_platform;

use super::super::super::state::RuntimeState;
use super::error::ConnectAttemptError;

pub(super) fn apply_group_socket_options(
    stream: &TcpStream,
    settings: &TcpRouteConnectSettings,
) -> Result<(), ConnectAttemptError> {
    if settings.drop_sack {
        connect_platform::attach_drop_sack(stream).map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: settings.tfo_enabled,
        })?;
    }
    if let Some(clamp) = settings.window_clamp {
        let _ = connect_platform::set_tcp_window_clamp(stream, clamp);
    }
    if settings.strip_timestamps {
        let _ = connect_platform::attach_strip_timestamps(stream);
    }
    Ok(())
}

pub(super) fn record_connect_telemetry(
    state: &RuntimeState,
    stream: &TcpStream,
    target: SocketAddr,
    group_index: usize,
    started: Instant,
) {
    let elapsed = started.elapsed().as_secs_f64();
    connect_platform::record_connection_setup_duration(group_index, elapsed);
    let upstream_addr = stream.peer_addr().unwrap_or(target);
    let upstream_rtt_ms = connect_platform::tcp_round_trip_time_ms(stream)
        .ok()
        .flatten()
        .or_else(|| Some(started.elapsed().as_millis() as u64));
    state.note_upstream_connected(upstream_addr, upstream_rtt_ms);
}
