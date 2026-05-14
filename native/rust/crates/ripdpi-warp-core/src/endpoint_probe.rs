use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;

use anyhow::Context;
use boringtun::noise::{Tunn, TunnResult};
use socket2::{Domain, Protocol, Socket, Type};
use tokio::net::UdpSocket;
use tokio::time::{timeout, Instant};

use crate::amneziawg::AwgWireCodec;
use crate::config::{resolve_endpoint, ResolvedWarpRuntimeEndpoint, WarpEndpointProbeRequest, WarpEndpointProbeResult};
use crate::platform::{protect_socket_if_configured, WarpPlatform};
use crate::support::MAX_PACKET;
use crate::wireguard::{apply_reserved_bytes, build_awg_codec, decode_key, reserved_bytes_from_client_id};

pub async fn probe_endpoint(request: WarpEndpointProbeRequest) -> anyhow::Result<WarpEndpointProbeResult> {
    probe_endpoint_with_platform(request, &WarpPlatform::default()).await
}

pub async fn probe_endpoint_with_platform(
    request: WarpEndpointProbeRequest,
    platform: &WarpPlatform,
) -> anyhow::Result<WarpEndpointProbeResult> {
    let endpoint = resolve_endpoint(&request.endpoint).await?;
    let private_key = decode_key(&request.private_key).context("invalid WARP private key")?;
    let peer_public_key = decode_key(&request.peer_public_key).context("invalid WARP peer public key")?;
    let reserved = reserved_bytes_from_client_id(request.client_id.as_deref());
    let amnezia = build_awg_codec(&request.amnezia);
    let mut peer = Box::new(Tunn::new(
        boringtun::x25519::StaticSecret::from(private_key),
        boringtun::x25519::PublicKey::from(peer_public_key),
        None,
        Some(25),
        0,
        None,
    ));
    let udp = bind_probe_socket(endpoint, platform).context("bind WARP probe socket")?;
    let mut outbound = [0u8; MAX_PACKET];
    let handshake = match peer.format_handshake_initiation(&mut outbound, true) {
        TunnResult::WriteToNetwork(packet) => encode_probe_packet(packet, reserved, amnezia.as_ref()),
        TunnResult::Err(error) => anyhow::bail!("format handshake initiation failed: {error:?}"),
        _ => anyhow::bail!("WARP handshake initiation did not produce a packet"),
    };
    let started_at = std::time::Instant::now();
    udp.send_to(&handshake, endpoint).await.context("send WARP probe handshake")?;

    let timeout_ms = request.timeout_ms.max(250);
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let mut recv_buf = [0u8; MAX_PACKET];
    let mut decap_buf = [0u8; MAX_PACKET];
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            anyhow::bail!("WARP endpoint probe timed out");
        }
        let (size, source) = timeout(remaining, udp.recv_from(&mut recv_buf))
            .await
            .context("WARP endpoint probe timed out")?
            .context("receive WARP probe packet")?;
        if source.ip() != endpoint.ip() {
            continue;
        }
        let Some(packet) = decode_probe_packet(&recv_buf[..size], amnezia.as_ref()) else {
            continue;
        };
        match peer.decapsulate(Some(source.ip()), &packet, &mut decap_buf) {
            TunnResult::WriteToNetwork(response) => {
                let payload = encode_probe_packet(response, reserved, amnezia.as_ref());
                udp.send_to(&payload, endpoint).await.context("respond to WARP probe packet")?;
                return Ok(probe_result_from_endpoint(endpoint, &request.endpoint, started_at.elapsed()));
            }
            TunnResult::Done | TunnResult::WriteToTunnelV4(_, _) | TunnResult::WriteToTunnelV6(_, _) => {
                return Ok(probe_result_from_endpoint(endpoint, &request.endpoint, started_at.elapsed()));
            }
            TunnResult::Err(_) => {}
        }
    }
}

fn bind_probe_socket(endpoint: SocketAddr, platform: &WarpPlatform) -> anyhow::Result<UdpSocket> {
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

fn encode_probe_packet(packet: &[u8], reserved: [u8; 3], amnezia: Option<&AwgWireCodec>) -> Vec<u8> {
    let mut payload = packet.to_vec();
    apply_reserved_bytes(&mut payload, reserved);
    match amnezia {
        Some(codec) => codec.encode(&payload),
        None => payload,
    }
}

fn decode_probe_packet(packet: &[u8], amnezia: Option<&AwgWireCodec>) -> Option<Vec<u8>> {
    match amnezia {
        Some(codec) => {
            let (wg_type, tail) = codec.decode(packet)?;
            Some(std::iter::once(wg_type).chain(tail.iter().copied()).collect())
        }
        None => Some(packet.to_vec()),
    }
}

fn probe_result_from_endpoint(
    endpoint: SocketAddr,
    original: &ResolvedWarpRuntimeEndpoint,
    elapsed: std::time::Duration,
) -> WarpEndpointProbeResult {
    WarpEndpointProbeResult {
        host: if !original.host.trim().is_empty() { original.host.clone() } else { endpoint.ip().to_string() },
        ipv4: endpoint.ip().is_ipv4().then(|| endpoint.ip().to_string()),
        ipv6: endpoint.ip().is_ipv6().then(|| endpoint.ip().to_string()),
        port: i32::from(endpoint.port()),
        rtt_ms: elapsed.as_millis().max(1) as u64,
    }
}
