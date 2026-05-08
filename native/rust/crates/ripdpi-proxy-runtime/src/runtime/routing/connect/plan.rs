use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::config::TcpRouteConnectSettings;

use super::super::super::state::RuntimeState;
use super::error::ConnectAttemptError;
use super::post_connect::{apply_group_socket_options, record_connect_telemetry};
use super::socket::connect_socket_detailed;
use super::socks::connect_via_socks;

pub(in crate::runtime::routing) fn connect_target_candidates_via_group(
    targets: &[SocketAddr],
    state: &RuntimeState,
    group_index: usize,
    payload: Option<&[u8]>,
    allow_tfo: bool,
) -> Result<TcpStream, ConnectAttemptError> {
    let settings =
        state.route_connect_settings(group_index, payload, allow_tfo).ok_or_else(|| ConnectAttemptError {
            source: io::Error::new(io::ErrorKind::NotFound, "missing desync group"),
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: false,
        })?;
    let mut last_error = None;
    for &candidate in targets {
        match connect_target_via_group_with_settings(candidate, state, group_index, &settings) {
            Ok(stream) => return Ok(stream),
            Err(err) => last_error = Some(err),
        }
    }
    Err(last_error.unwrap_or_else(|| ConnectAttemptError {
        source: io::Error::new(io::ErrorKind::AddrNotAvailable, "no target candidates available"),
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: settings.tfo_enabled,
    }))
}

fn connect_target_via_group_with_settings(
    target: SocketAddr,
    state: &RuntimeState,
    group_index: usize,
    settings: &TcpRouteConnectSettings,
) -> Result<TcpStream, ConnectAttemptError> {
    let started = std::time::Instant::now();
    let stream = if let Some(upstream_addr) = settings.upstream_socks_addr {
        connect_via_socks(
            target,
            upstream_addr,
            unspecified_ip_for(upstream_addr),
            settings.protect_path.as_deref(),
            settings.tfo_enabled,
            settings.connect_timeout,
        )
        .map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: settings.tfo_enabled,
        })
    } else {
        connect_socket_detailed(
            target,
            unspecified_ip_for(target),
            settings.protect_path.as_deref(),
            settings.tfo_enabled,
            settings.connect_timeout,
            settings.pre_connect_rcvbuf,
        )
    }?;

    apply_group_socket_options(&stream, settings)?;
    record_connect_telemetry(state, &stream, target, group_index, started);
    Ok(stream)
}

pub(super) fn unspecified_ip_for(addr: SocketAddr) -> IpAddr {
    match addr {
        SocketAddr::V4(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED),
        SocketAddr::V6(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outbound_connects_do_not_reuse_listener_bind_ip() {
        assert_eq!(unspecified_ip_for(SocketAddr::from(([203, 0, 113, 7], 443))), IpAddr::V4(Ipv4Addr::UNSPECIFIED));
        assert_eq!(
            unspecified_ip_for(SocketAddr::from(([0u16, 0, 0, 0, 0, 0, 0, 1], 443))),
            IpAddr::V6(Ipv6Addr::UNSPECIFIED),
        );
    }
}
