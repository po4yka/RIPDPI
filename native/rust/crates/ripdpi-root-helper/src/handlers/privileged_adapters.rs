use std::net::IpAddr;
use std::os::fd::RawFd;

use ripdpi_privileged_ops as platform;
use ripdpi_privileged_ops::{IcmpWrappedUdpRecvFilter, IcmpWrappedUdpSpec, SynHideTcpSpec};
use ripdpi_root_helper_protocol::{HelperResponse, RawIpPacketParams};
use tracing::{debug, error};

pub fn handle_send_syn_hide_tcp(params: SynHideTcpSpec) -> (HelperResponse, Option<RawFd>) {
    debug!(source = %params.source, target = %params.target, marker = ?params.marker_kind, "send_syn_hide_tcp");
    match platform::send_syn_hide_tcp(params, None) {
        Ok(()) => (HelperResponse::success(serde_json::Value::Null), None),
        Err(error) => {
            error!(%error, "send_syn_hide_tcp failed");
            (HelperResponse::error(error.to_string()), None)
        }
    }
}

pub fn handle_send_icmp_wrapped_udp(params: IcmpWrappedUdpSpec) -> (HelperResponse, Option<RawFd>) {
    debug!(
        peer = %params.peer,
        service_port = params.service_port,
        role = ?params.role,
        len = params.payload.len(),
        "send_icmp_wrapped_udp"
    );
    match platform::send_icmp_wrapped_udp(&params, None) {
        Ok(()) => (HelperResponse::success(serde_json::Value::Null), None),
        Err(error) => {
            error!(%error, "send_icmp_wrapped_udp failed");
            (HelperResponse::error(error.to_string()), None)
        }
    }
}

pub fn handle_recv_icmp_wrapped_udp(mut params: IcmpWrappedUdpRecvFilter) -> (HelperResponse, Option<RawFd>) {
    params.timeout_ms = super::bounded_icmp_recv_timeout_ms(params.timeout_ms);
    debug!(
        bind_ip = %params.bind_ip,
        session_id = ?params.session_id,
        expected_code = ?params.expected_code,
        expected_role = ?params.expected_role,
        timeout_ms = params.timeout_ms,
        "recv_icmp_wrapped_udp"
    );
    match platform::recv_icmp_wrapped_udp(params, None) {
        Ok(message) => match serde_json::to_value(message) {
            Ok(data) => (HelperResponse::success(data), None),
            Err(error) => {
                (HelperResponse::error(format!("failed to serialize recv_icmp_wrapped_udp response: {error}")), None)
            }
        },
        Err(error) => {
            error!(%error, "recv_icmp_wrapped_udp failed");
            (HelperResponse::error(error.to_string()), None)
        }
    }
}

pub fn handle_send_raw_ip_packet(params: RawIpPacketParams) -> (HelperResponse, Option<RawFd>) {
    let target: std::net::SocketAddr = match params.target_addr.parse() {
        Ok(addr) => addr,
        Err(error) => return (HelperResponse::error(format!("invalid target_addr: {error}")), None),
    };
    debug!(target = %target, len = params.packet.len(), "send_raw_ip_packet");
    if let Err(reason) = check_raw_ip_packet(target.ip(), &params.packet, is_local_address) {
        return (HelperResponse::error(format!("raw IP packet refused: {reason}")), None);
    }
    match platform::send_raw_ip_packet(target, &params.packet, None) {
        Ok(()) => (HelperResponse::success(serde_json::Value::Null), None),
        Err(error) => {
            error!(%error, "send_raw_ip_packet failed");
            (HelperResponse::error(error.to_string()), None)
        }
    }
}

/// Checks a caller-built packet before the uid-0 helper sends it with a raw
/// socket. The IP header must match the buffer and the requested target, and
/// one of its addresses must belong to this host: the app injects packets of
/// its own flows (TUN egress, SYN-ACK ingress), never packets between two
/// foreign hosts.
fn check_raw_ip_packet(target: IpAddr, packet: &[u8], is_local: impl Fn(IpAddr) -> bool) -> Result<(), &'static str> {
    let (source, destination) = match (packet.first().map(|byte| byte >> 4), target) {
        (Some(4), IpAddr::V4(_)) => {
            let header = packet.get(..20).ok_or("short IPv4 header")?;
            let header_len = usize::from(header[0] & 0x0f) * 4;
            if header_len < 20 || header_len > packet.len() {
                return Err("invalid IPv4 header length");
            }
            if usize::from(u16::from_be_bytes([header[2], header[3]])) != packet.len() {
                return Err("IPv4 total length does not match the packet");
            }
            let address = |at: usize| IpAddr::from([header[at], header[at + 1], header[at + 2], header[at + 3]]);
            (address(12), address(16))
        }
        (Some(6), IpAddr::V6(_)) => {
            let header = packet.get(..40).ok_or("short IPv6 header")?;
            if 40 + usize::from(u16::from_be_bytes([header[4], header[5]])) != packet.len() {
                return Err("IPv6 payload length does not match the packet");
            }
            let address = |at: usize| {
                let mut octets = [0u8; 16];
                octets.copy_from_slice(&header[at..at + 16]);
                IpAddr::from(octets)
            };
            (address(8), address(24))
        }
        _ => return Err("IP version does not match the target"),
    };
    if destination != target {
        return Err("destination does not match the target");
    }
    if !is_local(source) && !is_local(destination) {
        return Err("neither address belongs to this host");
    }
    Ok(())
}

fn is_local_address(address: IpAddr) -> bool {
    let Ok(interfaces) = nix::ifaddrs::getifaddrs() else {
        return false;
    };
    interfaces.filter_map(|interface| interface.address).any(|storage| {
        storage.as_sockaddr_in().map(|v4| IpAddr::V4(*std::net::SocketAddrV4::from(*v4).ip())) == Some(address)
            || storage.as_sockaddr_in6().map(|v6| IpAddr::V6(*std::net::SocketAddrV6::from(*v6).ip())) == Some(address)
    })
}

#[cfg(test)]
mod tests {
    use ripdpi_root_helper_protocol::RawIpPacketParams;

    use super::handle_send_raw_ip_packet;

    fn ipv4_packet(source: [u8; 4], destination: [u8; 4], total_length: u16) -> Vec<u8> {
        let mut packet = vec![0u8; 28];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&total_length.to_be_bytes());
        packet[8] = 64;
        packet[9] = 17;
        packet[12..16].copy_from_slice(&source);
        packet[16..20].copy_from_slice(&destination);
        packet
    }

    fn refusal(target: &str, packet: Vec<u8>) -> Option<String> {
        let (response, fd) = handle_send_raw_ip_packet(RawIpPacketParams { target_addr: target.to_owned(), packet });
        assert!(fd.is_none());
        assert!(!response.ok, "the helper must not send this packet");
        response.error
    }

    #[test]
    fn an_icmp_receive_ends_at_the_helper_bound_not_the_client_timeout() {
        use std::time::{Duration, Instant};

        let filter = ripdpi_privileged_ops::IcmpWrappedUdpRecvFilter {
            bind_ip: std::net::IpAddr::from([127, 0, 0, 1]),
            session_id: Some(0x5eed_c0de),
            expected_code: None,
            expected_role: None,
            timeout_ms: 8_000,
        };
        let started = Instant::now();
        let (response, fd) = super::handle_recv_icmp_wrapped_udp(filter);
        let waited = started.elapsed();
        assert!(fd.is_none());
        let error = response.error.unwrap_or_default();
        // The receive needs a raw ICMP socket (CAP_NET_RAW on Linux or Android).
        if !error.contains("timed out") {
            eprintln!("skipped: no raw ICMP receive here: {error}");
            return;
        }
        assert!(waited < Duration::from_secs(7), "the 8 s client timeout was not bounded: {waited:?}");
    }

    #[test]
    fn spoofed_or_inconsistent_raw_packets_are_refused_before_sending() {
        let cases = [
            // Neither address belongs to this host: a spoofed packet for a third party.
            ("198.51.100.2:443", ipv4_packet([203, 0, 113, 1], [198, 51, 100, 2], 28)),
            // The packet goes elsewhere than the requested target.
            ("198.51.100.2:443", ipv4_packet([127, 0, 0, 1], [198, 51, 100, 3], 28)),
            // The header length does not match the buffer.
            ("198.51.100.2:443", ipv4_packet([127, 0, 0, 1], [198, 51, 100, 2], 1_500)),
            // An IPv4 packet for an IPv6 target.
            ("[2001:db8::2]:443", ipv4_packet([127, 0, 0, 1], [198, 51, 100, 2], 28)),
        ];
        for (target, packet) in cases {
            let error = refusal(target, packet).unwrap_or_default();
            assert!(error.starts_with("raw IP packet refused"), "{target}: {error}");
        }
    }

    #[test]
    fn packets_of_the_apps_own_flows_pass_the_check() {
        use std::net::IpAddr;

        let tun = IpAddr::from([10, 10, 10, 10]);
        let is_tun = |address: IpAddr| address == tun;
        let server = IpAddr::from([198, 51, 100, 2]);
        // TUN egress: the source is the local TUN address.
        super::check_raw_ip_packet(server, &ipv4_packet([10, 10, 10, 10], [198, 51, 100, 2], 28), is_tun)
            .expect("egress packet");
        // SYN-ACK ingress: the destination is the local TUN address.
        super::check_raw_ip_packet(tun, &ipv4_packet([198, 51, 100, 2], [10, 10, 10, 10], 28), is_tun)
            .expect("ingress packet");

        let mut ipv6 = vec![0u8; 48];
        ipv6[0] = 0x60;
        ipv6[4..6].copy_from_slice(&8_u16.to_be_bytes());
        ipv6[8..24].copy_from_slice(&std::net::Ipv6Addr::LOCALHOST.octets());
        let destination: std::net::Ipv6Addr = "2001:db8::2".parse().expect("address");
        ipv6[24..40].copy_from_slice(&destination.octets());
        super::check_raw_ip_packet(IpAddr::V6(destination), &ipv6, super::is_local_address).expect("IPv6 packet");
        assert!(super::is_local_address(IpAddr::from([127, 0, 0, 1])));
    }
}
