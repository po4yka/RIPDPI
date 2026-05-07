use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::config::{
    connect_timeout, group_requests_direct_syn_data_tfo, protect_path, tcp_fast_open_enabled, DesyncGroup,
};

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
    let group = state.config.groups.get(group_index).ok_or_else(|| ConnectAttemptError {
        source: io::Error::new(io::ErrorKind::NotFound, "missing desync group"),
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: false,
    })?;
    let tfo_enabled = group_uses_tcp_fast_open(state, group, payload, allow_tfo);
    let mut last_error = None;
    for &candidate in targets {
        match connect_target_via_group_with_tfo(candidate, state, group_index, tfo_enabled) {
            Ok(stream) => return Ok(stream),
            Err(err) => last_error = Some(err),
        }
    }
    Err(last_error.unwrap_or_else(|| ConnectAttemptError {
        source: io::Error::new(io::ErrorKind::AddrNotAvailable, "no target candidates available"),
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: tfo_enabled,
    }))
}

fn connect_target_via_group_with_tfo(
    target: SocketAddr,
    state: &RuntimeState,
    group_index: usize,
    tfo_enabled: bool,
) -> Result<TcpStream, ConnectAttemptError> {
    let started = std::time::Instant::now();
    let group = state.config.groups.get(group_index).ok_or_else(|| ConnectAttemptError {
        source: io::Error::new(io::ErrorKind::NotFound, "missing desync group"),
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: false,
    })?;
    let connect_timeout = connect_timeout(&state.config);
    let pre_connect_rcvbuf = group.actions.wsize.map(|w| match w.scale {
        Some(scale) if (scale as u32) < 32 => w.window.checked_shl(scale as u32).unwrap_or(u32::MAX),
        Some(_) => u32::MAX,
        None => w.window,
    });
    let stream = if let Some(upstream) = group.policy.ext_socks {
        connect_via_socks(
            target,
            upstream.addr,
            unspecified_ip_for(upstream.addr),
            protect_path(&state.config),
            tfo_enabled,
            connect_timeout,
        )
        .map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: tfo_enabled,
        })
    } else {
        connect_socket_detailed(
            target,
            unspecified_ip_for(target),
            protect_path(&state.config),
            tfo_enabled,
            connect_timeout,
            pre_connect_rcvbuf,
        )
    }?;

    apply_group_socket_options(&stream, group, tfo_enabled)?;
    record_connect_telemetry(state, &stream, target, group_index, started);
    Ok(stream)
}

fn group_uses_tcp_fast_open(
    state: &RuntimeState,
    group: &DesyncGroup,
    payload: Option<&[u8]>,
    allow_tfo: bool,
) -> bool {
    allow_tfo && (tcp_fast_open_enabled(&state.config) || group_requests_direct_syn_data_tfo(group, payload))
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
    use ripdpi_proxy_runtime_adapter::model::config::{
        OffsetExpr, TcpChainStep, TcpChainStepKind, UpstreamSocksConfig,
    };

    #[test]
    fn outbound_connects_do_not_reuse_listener_bind_ip() {
        assert_eq!(unspecified_ip_for(SocketAddr::from(([203, 0, 113, 7], 443))), IpAddr::V4(Ipv4Addr::UNSPECIFIED));
        assert_eq!(
            unspecified_ip_for(SocketAddr::from(([0u16, 0, 0, 0, 0, 0, 0, 1], 443))),
            IpAddr::V6(Ipv6Addr::UNSPECIFIED),
        );
    }

    #[test]
    fn direct_syn_data_tfo_requires_payload_and_direct_upstream() {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SynData, OffsetExpr::absolute(1)));

        assert!(group_requests_direct_syn_data_tfo(&group, Some(b"GET / HTTP/1.1\r\n\r\n")));
        assert!(!group_requests_direct_syn_data_tfo(&group, None));
        assert!(!group_requests_direct_syn_data_tfo(&group, Some(&[])));

        group.policy.ext_socks = Some(UpstreamSocksConfig { addr: SocketAddr::from(([127, 0, 0, 1], 1080)) });
        assert!(!group_requests_direct_syn_data_tfo(&group, Some(b"GET / HTTP/1.1\r\n\r\n")));
    }
}
