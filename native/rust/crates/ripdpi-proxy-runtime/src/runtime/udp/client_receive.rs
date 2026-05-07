use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Instant;

use ripdpi_proxy_runtime_adapter::config::{QuicInitialMode, RuntimeConfig};
use ripdpi_runtime_decision_ports::policy::{extract_host_info, ExtractedHost, HostSource};

use super::flow::UdpFlowActivationState;
use super::flow_selection::ensure_udp_flow_selected;
use super::parse_socks5_udp_packet;
use super::upstream_pump::send_udp_flow_payload;
use crate::runtime::state::RuntimeState;

pub(super) struct UdpClientPacket<'a> {
    pub(super) sender: SocketAddr,
    pub(super) original_target: SocketAddr,
    pub(super) payload: &'a [u8],
    pub(super) host: Option<String>,
    pub(super) cache_host: bool,
}

impl UdpClientPacket<'_> {
    pub(super) fn flow_key(&self) -> (SocketAddr, SocketAddr) {
        (self.sender, self.original_target)
    }
}

pub(super) fn receive_and_forward_udp_client_packet(
    client_relay: &UdpSocket,
    client_buffer: &mut [u8],
    udp_client_addr: &mut Option<SocketAddr>,
    flow_state: &mut HashMap<(SocketAddr, SocketAddr), UdpFlowActivationState>,
    flow_limit: usize,
    state: &RuntimeState,
    protect_path: Option<&str>,
) -> io::Result<bool> {
    match client_relay.recv_from(client_buffer) {
        Ok((n, sender)) => {
            let now = Instant::now();
            let Some(packet) = decode_udp_client_packet(&client_buffer[..n], sender, udp_client_addr, state) else {
                return Ok(true);
            };
            if !ensure_udp_flow_selected(state, protect_path, flow_state, flow_limit, &packet, now)? {
                return Ok(true);
            }
            let entry = flow_state
                .get_mut(&packet.flow_key())
                .ok_or_else(|| io::Error::other("udp flow entry missing after selection"))?;
            send_udp_flow_payload(state, entry, packet.payload, now, protect_path)?;
            Ok(true)
        }
        Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => Ok(false),
        Err(err) => Err(err),
    }
}

pub(super) fn should_cache_udp_host(config: &RuntimeConfig, host: Option<&ExtractedHost>) -> bool {
    match host.map(|value| value.source) {
        Some(HostSource::Quic) => matches!(config.quic.initial_mode, QuicInitialMode::RouteAndCache),
        Some(HostSource::Http | HostSource::Tls) => true,
        None => false,
    }
}

fn decode_udp_client_packet<'a>(
    packet: &'a [u8],
    sender: SocketAddr,
    udp_client_addr: &mut Option<SocketAddr>,
    state: &RuntimeState,
) -> Option<UdpClientPacket<'a>> {
    if !accept_udp_client_sender(udp_client_addr, sender) {
        return None;
    }

    let (original_target, payload) = parse_socks5_udp_packet(packet, state)?;
    let host_info = extract_host_info(&state.config, payload);
    let host = host_info.as_ref().map(|value| value.host.clone());
    let cache_host = should_cache_udp_host(&state.config, host_info.as_ref());
    Some(UdpClientPacket { sender, original_target, payload, host, cache_host })
}

fn accept_udp_client_sender(udp_client_addr: &mut Option<SocketAddr>, sender: SocketAddr) -> bool {
    let known_client = *udp_client_addr;
    if known_client.is_none() || known_client == Some(sender) {
        *udp_client_addr = Some(sender);
        true
    } else {
        false
    }
}
