use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::Duration;

use anyhow::{anyhow, Context};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use boringtun::noise::{Tunn, TunnResult};
use bytes::Bytes;
use smoltcp::wire::{IpProtocol, IpVersion, Ipv4Packet, Ipv6Packet};
use socket2::{Domain, Protocol, Socket, Type};
use tokio::net::UdpSocket;

use crate::amnezia::{fill_random, rand_u32, AmneziaCodec};
use crate::config::WarpAmneziaConfig;
use crate::platform::{protect_socket_if_configured, WarpPlatform};
use crate::ports::PortProtocol;
use crate::virtual_iface::{Bus, Event};
use crate::MAX_PACKET;

pub(crate) struct WireGuardTunnel {
    peer: tokio::sync::Mutex<Box<Tunn>>,
    udp: UdpSocket,
    endpoint: SocketAddr,
    source_peer_ip: IpAddr,
    reserved: [u8; 3],
    amnezia: Option<AmneziaCodec>,
}

fn bind_tunnel_socket(endpoint: SocketAddr, platform: &WarpPlatform) -> anyhow::Result<UdpSocket> {
    let bind_addr = if endpoint.is_ipv4() {
        SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))
    } else {
        "[::]:0".parse().expect("ipv6 bind addr")
    };
    let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    socket.bind(&bind_addr.into())?;
    protect_socket_if_configured(&socket, platform);
    socket.set_nonblocking(true)?;
    Ok(UdpSocket::from_std(socket.into())?)
}

impl WireGuardTunnel {
    pub(crate) async fn new(
        private_key: &str,
        peer_public_key: &str,
        endpoint: SocketAddr,
        reserved: [u8; 3],
        source_peer_ip: IpAddr,
        amnezia_cfg: &WarpAmneziaConfig,
        platform: &WarpPlatform,
    ) -> anyhow::Result<Self> {
        let private_key = decode_key(private_key).context("invalid WARP private key")?;
        let peer_public_key = decode_key(peer_public_key).context("invalid WARP peer public key")?;
        let peer = Box::new(Tunn::new(
            boringtun::x25519::StaticSecret::from(private_key),
            boringtun::x25519::PublicKey::from(peer_public_key),
            None,
            Some(25),
            0,
            None,
        ));
        let udp = bind_tunnel_socket(endpoint, platform)?;
        let amnezia = amnezia_cfg.enabled.then(|| AmneziaCodec::new(amnezia_cfg));
        Ok(Self { peer: tokio::sync::Mutex::new(peer), udp, endpoint, source_peer_ip, reserved, amnezia })
    }

    pub(crate) async fn send_amnezia_junk(&self, cfg: &WarpAmneziaConfig) {
        let jc = cfg.jc.max(0) as usize;
        let jmin = cfg.jmin.max(1) as usize;
        let jmax = cfg.jmax.max(jmin as i32) as usize;
        for _ in 0..jc {
            let range = (jmax - jmin + 1) as u32;
            let size = jmin + (rand_u32() % range) as usize;
            let mut junk = vec![0u8; size];
            fill_random(&mut junk);
            let _ = self.udp.send_to(&junk, self.endpoint).await;
        }
    }

    async fn send_ip_packet(&self, packet: &[u8]) {
        let mut send_buf = [0u8; MAX_PACKET];
        let result = { self.peer.lock().await.encapsulate(packet, &mut send_buf) };
        self.send_tunn_result(result).await;
    }

    fn encode_outbound_packet(&self, packet: &[u8]) -> Vec<u8> {
        match &self.amnezia {
            Some(codec) => {
                let mut wg_packet = packet.to_vec();
                apply_reserved_bytes(&mut wg_packet, self.reserved);
                codec.encode(&wg_packet)
            }
            None => {
                let mut payload = packet.to_vec();
                apply_reserved_bytes(&mut payload, self.reserved);
                payload
            }
        }
    }

    async fn send_tunn_result<'a>(&self, result: TunnResult<'a>) {
        match result {
            TunnResult::WriteToNetwork(packet) => {
                let payload = self.encode_outbound_packet(packet);
                let _ = self.udp.send_to(&payload, self.endpoint).await;
            }
            TunnResult::Done => {}
            TunnResult::Err(error) => tracing::warn!("WARP tunnel write failed: {error:?}"),
            _ => {}
        }
    }

    pub(crate) async fn produce_task(&self, bus: Bus) -> ! {
        let mut endpoint = bus.new_endpoint();
        loop {
            if let Event::OutboundInternetPacket(packet) = endpoint.recv().await {
                self.send_ip_packet(&packet).await;
            }
        }
    }

    pub(crate) async fn routine_task(&self) -> ! {
        loop {
            let mut send_buf = [0u8; MAX_PACKET];
            let result = { self.peer.lock().await.update_timers(&mut send_buf) };
            self.send_tunn_result(result).await;
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    }

    pub(crate) async fn consume_task(&self, bus: Bus) -> ! {
        let endpoint = bus.new_endpoint();
        loop {
            let mut recv_buf = [0u8; MAX_PACKET];
            let mut send_buf = [0u8; MAX_PACKET];
            let size = match self.udp.recv(&mut recv_buf).await {
                Ok(size) => size,
                Err(error) => {
                    tracing::warn!("WARP tunnel recv failed: {error}");
                    tokio::time::sleep(Duration::from_millis(10)).await;
                    continue;
                }
            };
            let raw = &recv_buf[..size];
            // When AmneziaWG obfuscation is active, decode the incoming packet
            // before passing it to boringtun.  Packets whose header does not
            // match any of h1-h4 (e.g. junk injected by the remote peer during
            // its own handshake setup) are silently discarded.
            //
            // `decoded_buf` holds the reconstructed WG packet (type byte +
            // payload) when amnezia is active.  When inactive, `data` points
            // directly into `recv_buf` and `decoded_buf` is unused.
            let decoded_buf: Vec<u8> = if let Some(codec) = &self.amnezia {
                match codec.decode(raw) {
                    Some((wg_type, tail)) => {
                        // Reconstruct [type byte | rest-of-wg-packet].
                        std::iter::once(wg_type).chain(tail.iter().copied()).collect()
                    }
                    None => continue,
                }
            } else {
                Vec::new()
            };
            let data: &[u8] = if self.amnezia.is_some() {
                &decoded_buf
            } else {
                if raw.is_empty() {
                    continue;
                }
                raw
            };
            let result = { self.peer.lock().await.decapsulate(None, data, &mut send_buf) };
            match result {
                TunnResult::WriteToNetwork(packet) => {
                    let payload = self.encode_outbound_packet(packet);
                    let _ = self.udp.send_to(&payload, self.endpoint).await;
                }
                TunnResult::WriteToTunnelV4(packet, _) | TunnResult::WriteToTunnelV6(packet, _) => {
                    if let Some(protocol) = route_protocol(packet, self.source_peer_ip) {
                        endpoint.send(Event::InboundInternetPacket(protocol, Bytes::copy_from_slice(packet)));
                    }
                }
                TunnResult::Done => {}
                TunnResult::Err(error) => tracing::warn!("WARP tunnel decapsulation failed: {error:?}"),
            }
        }
    }
}
pub(crate) fn decode_key(value: &str) -> anyhow::Result<[u8; 32]> {
    let bytes = STANDARD.decode(value).context("base64 decode failed")?;
    bytes.try_into().map_err(|_| anyhow!("expected 32-byte key"))
}
pub(crate) fn reserved_bytes_from_client_id(client_id: Option<&str>) -> [u8; 3] {
    let mut reserved = [0u8; 3];
    if let Some(client_id) = client_id {
        if let Ok(decoded) = STANDARD.decode(client_id) {
            for (index, value) in decoded.iter().take(3).enumerate() {
                reserved[index] = *value;
            }
        }
    }
    reserved
}

pub(crate) fn apply_reserved_bytes(packet: &mut [u8], reserved: [u8; 3]) {
    if packet.len() >= 4 {
        packet[1..4].copy_from_slice(&reserved);
    }
}

fn route_protocol(packet: &[u8], source_peer_ip: IpAddr) -> Option<PortProtocol> {
    match IpVersion::of_packet(packet).ok()? {
        IpVersion::Ipv4 => {
            let packet = Ipv4Packet::new_checked(packet).ok()?;
            if packet.dst_addr() != source_peer_ip {
                return None;
            }
            match packet.next_header() {
                IpProtocol::Tcp => Some(PortProtocol::Tcp),
                IpProtocol::Udp => Some(PortProtocol::Udp),
                _ => None,
            }
        }
        IpVersion::Ipv6 => {
            let packet = Ipv6Packet::new_checked(packet).ok()?;
            if packet.dst_addr() != source_peer_ip {
                return None;
            }
            match packet.next_header() {
                IpProtocol::Tcp => Some(PortProtocol::Tcp),
                IpProtocol::Udp => Some(PortProtocol::Udp),
                _ => None,
            }
        }
    }
}
