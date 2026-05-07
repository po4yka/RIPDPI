use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
use ripdpi_proxy_runtime_adapter::platform;

use super::super::super::state::RuntimeState;
use super::error::ConnectAttemptError;

pub(super) fn apply_group_socket_options(
    stream: &TcpStream,
    group: &DesyncGroup,
    tfo_enabled: bool,
) -> Result<(), ConnectAttemptError> {
    if group.actions.drop_sack {
        platform::socket::attach_drop_sack(stream).map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: tfo_enabled,
        })?;
    }
    let effective_clamp = group.actions.wsize.map(|w| w.window).or(group.actions.window_clamp);
    if let Some(clamp) = effective_clamp {
        let _ = platform::socket::set_tcp_window_clamp(stream, clamp);
    }
    if group.actions.strip_timestamps {
        let _ = platform::socket::attach_strip_timestamps(stream);
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
    let group_label = format!("{group_index}");
    metrics::histogram!("ripdpi_connection_setup_duration_seconds", "group" => group_label).record(elapsed);
    if let Some(telemetry) = &state.telemetry {
        let upstream_addr = stream.peer_addr().unwrap_or(target);
        let upstream_rtt_ms = platform::tcp::tcp_round_trip_time_ms(stream)
            .ok()
            .flatten()
            .or_else(|| Some(started.elapsed().as_millis() as u64));
        telemetry.on_upstream_connected(upstream_addr, upstream_rtt_ms);
    }
}
