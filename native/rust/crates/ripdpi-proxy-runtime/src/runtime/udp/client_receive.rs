use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::time::Instant;

use super::flow::{UdpFlowActivationState, UdpFlowKey};
use super::flow_selection::ensure_udp_flow_selected;
use super::parse_socks5_udp_packet_with_host;
use super::upstream_pump::send_udp_flow_payload;
use crate::runtime::state::RuntimeState;
use ripdpi_proxy_runtime_adapter::model::runtime_api::AttemptCorrelationId;

pub(super) struct UdpClientPacket<'a> {
    pub(super) sender: SocketAddr,
    pub(super) original_target: SocketAddr,
    pub(super) payload: &'a [u8],
    pub(super) host: Option<String>,
    pub(super) preserve_host_in_response: bool,
    pub(super) cache_host: bool,
}

impl UdpClientPacket<'_> {
    pub(super) fn flow_key(&self) -> UdpFlowKey {
        UdpFlowKey {
            client: self.sender,
            target: self.original_target,
            host: self.host.clone(),
            preserve_host_in_response: self.preserve_host_in_response,
        }
    }
}

pub(super) fn receive_and_forward_udp_client_packet(
    client_relay: &UdpSocket,
    client_buffer: &mut [u8],
    udp_client_addr: &mut Option<SocketAddr>,
    control_peer_ip: IpAddr,
    requested_udp_source: SocketAddr,
    flow_state: &mut HashMap<UdpFlowKey, UdpFlowActivationState>,
    flow_limit: usize,
    state: &RuntimeState,
    protect_path: Option<&str>,
    attempt_token: Option<&AttemptCorrelationId>,
) -> io::Result<bool> {
    match client_relay.recv_from(client_buffer) {
        Ok((n, sender)) => {
            let now = Instant::now();
            let Some(packet) = decode_udp_client_packet(
                &client_buffer[..n],
                sender,
                control_peer_ip,
                requested_udp_source,
                udp_client_addr,
                state,
            ) else {
                return Ok(true);
            };
            if !ensure_udp_flow_selected(state, protect_path, flow_state, flow_limit, &packet, now, attempt_token)? {
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

fn decode_udp_client_packet<'a>(
    packet: &'a [u8],
    sender: SocketAddr,
    control_peer_ip: IpAddr,
    requested_udp_source: SocketAddr,
    udp_client_addr: &mut Option<SocketAddr>,
    state: &RuntimeState,
) -> Option<UdpClientPacket<'a>> {
    // For an unspecified port, retain the first-valid-datagram pinning policy.
    if sender.ip() != control_peer_ip
        || (!requested_udp_source.ip().is_unspecified() && sender.ip() != requested_udp_source.ip())
        || (requested_udp_source.port() != 0 && sender.port() != requested_udp_source.port())
        || udp_client_addr.is_some_and(|known| known != sender)
    {
        return None;
    }

    let parsed = parse_socks5_udp_packet_with_host(packet, state)?;
    *udp_client_addr = Some(sender);
    let udp_payload = state.classify_udp_payload(parsed.payload);
    let authoritative_host = parsed.host.is_some();
    Some(UdpClientPacket {
        sender,
        original_target: parsed.target,
        payload: parsed.payload,
        host: parsed.host.or(udp_payload.host),
        preserve_host_in_response: parsed.preserve_host_in_response,
        cache_host: !authoritative_host && udp_payload.cache_host,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr};

    #[test]
    fn udp_client_pins_only_valid_packet_from_control_peer() {
        let state = RuntimeState::test_with_context(Default::default(), None);
        let target = SocketAddr::from(([127, 0, 0, 1], 53));
        let valid = RuntimeState::encode_socks5_udp_packet(target, b"dns");
        let peer_ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
        let legitimate = SocketAddr::from(([127, 0, 0, 1], 4000));
        let spoofed = SocketAddr::from(([127, 0, 0, 2], 4001));
        let mut pinned = None;
        let wildcard = SocketAddr::from(([0, 0, 0, 0], 0));
        assert!(decode_udp_client_packet(b"bad", legitimate, peer_ip, wildcard, &mut pinned, &state).is_none());
        assert_eq!(pinned, None);
        assert!(decode_udp_client_packet(&valid, spoofed, peer_ip, wildcard, &mut pinned, &state).is_none());
        assert_eq!(pinned, None);
        assert!(decode_udp_client_packet(&valid, legitimate, peer_ip, wildcard, &mut pinned, &state).is_some());
        assert_eq!(pinned, Some(legitimate));
    }

    #[test]
    fn udp_client_rejects_same_ip_wrong_declared_port() {
        let state = RuntimeState::test_with_context(Default::default(), None);
        let valid = RuntimeState::encode_socks5_udp_packet(SocketAddr::from(([127, 0, 0, 1], 53)), b"dns");
        let peer_ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
        let declared = SocketAddr::from(([127, 0, 0, 1], 4000));
        let wrong_port = SocketAddr::from(([127, 0, 0, 1], 4001));
        let mut pinned = None;
        assert!(decode_udp_client_packet(&valid, wrong_port, peer_ip, declared, &mut pinned, &state).is_none());
        assert_eq!(pinned, None);
        let wrong_ip = SocketAddr::from(([127, 0, 0, 2], 4000));
        assert!(decode_udp_client_packet(&valid, declared, peer_ip, wrong_ip, &mut pinned, &state).is_none());
        assert!(decode_udp_client_packet(&valid, declared, peer_ip, declared, &mut pinned, &state).is_some());
    }
}
