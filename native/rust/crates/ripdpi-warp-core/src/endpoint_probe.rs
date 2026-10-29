use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use boringtun::noise::awg::TimerRange;
use boringtun::noise::awg_cookies::CookieSource;
use boringtun::noise::{ReceiveOutcome, Tunn, TunnResult};
use socket2::{Domain, Protocol, Socket, Type};
use tokio::net::UdpSocket;
#[cfg(test)]
use tokio::time::timeout;
use tokio::time::{Instant, timeout_at};

use crate::amneziawg::{AwgMacKeys, AwgWireCodec, rand_u32};
use crate::config::{ResolvedWarpRuntimeEndpoint, WarpEndpointProbeRequest, WarpEndpointProbeResult, resolve_endpoint};
use crate::platform::{WarpPlatform, protect_socket_if_configured};
use crate::support::MAX_PACKET;
use crate::wireguard::{
    ReceiveOutput, apply_reserved_bytes, build_authenticated_awg_codec, decode_key, receive_packet,
    reserved_bytes_from_client_id,
};

/// Typed error returned by [`probe_endpoint`] / [`probe_endpoint_with_platform`].
///
/// Replaces the previous `anyhow::Error` return so callers can match on the
/// failure mode without string-sniffing. Motivated by the typed-error gate in
/// `.claude/rules/llm-rust-prompts.md` and the error-handling discipline in the
/// `rust-discipline` skill (finding M15).
#[derive(Debug)]
#[non_exhaustive]
pub enum WarpProbeError {
    /// DNS / address resolution of the configured endpoint failed.
    Resolve(std::io::Error),
    /// A configured key (private or peer-public) could not be decoded.
    InvalidKey(&'static str),
    /// The selected AWG wire profile has invalid or unsupported parameters.
    InvalidAwgParameters(String),
    /// The WireGuard handshake initiation could not be produced.
    Handshake(String),
    /// A socket or datagram I/O operation failed.
    Io(std::io::Error),
    /// The endpoint did not respond before the probe deadline elapsed.
    Timeout,
}

impl std::fmt::Display for WarpProbeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Resolve(error) => write!(f, "resolve WARP endpoint: {error}"),
            Self::InvalidKey(which) => write!(f, "invalid WARP {which}"),
            Self::InvalidAwgParameters(detail) => write!(f, "invalid AmneziaWG parameters: {detail}"),
            Self::Handshake(detail) => write!(f, "WARP handshake initiation failed: {detail}"),
            Self::Io(error) => write!(f, "WARP probe I/O: {error}"),
            Self::Timeout => f.write_str("WARP endpoint probe timed out"),
        }
    }
}

impl std::error::Error for WarpProbeError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Resolve(error) | Self::Io(error) => Some(error),
            Self::InvalidKey(_) | Self::InvalidAwgParameters(_) | Self::Handshake(_) | Self::Timeout => None,
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation can stop a handshake prelude; all probe resources are dropped.
pub async fn probe_endpoint(request: WarpEndpointProbeRequest) -> Result<WarpEndpointProbeResult, WarpProbeError> {
    probe_endpoint_with_platform(request, &WarpPlatform::default()).await
}

/// # Cancel safety:
/// NOT cancel-safe: handshake and timer state can advance before outbound I/O.
/// The probe owns and drops its socket and peer when cancelled.
pub async fn probe_endpoint_with_platform(
    request: WarpEndpointProbeRequest,
    platform: &WarpPlatform,
) -> Result<WarpEndpointProbeResult, WarpProbeError> {
    let endpoint = resolve_endpoint(&request.endpoint).await.map_err(WarpProbeError::Resolve)?;
    let private_key = decode_key(&request.private_key).map_err(|_| WarpProbeError::InvalidKey("private key"))?;
    let peer_public_key =
        decode_key(&request.peer_public_key).map_err(|_| WarpProbeError::InvalidKey("peer public key"))?;
    let reserved = reserved_bytes_from_client_id(request.client_id.as_deref());
    let private_key = boringtun::x25519::StaticSecret::from(private_key);
    let local_public_key = boringtun::x25519::PublicKey::from(&private_key).to_bytes();
    let amnezia = build_authenticated_awg_codec(
        &request.amnezia,
        &request.amnezia.signature_programs(),
        AwgMacKeys::new(local_public_key, peer_public_key),
    )
    .map_err(|error| WarpProbeError::InvalidAwgParameters(error.to_string()))?
    .map(Arc::new);
    // Fail-closed on a malformed PSK rather than silently probing without it:
    // a probe that succeeds without the PSK the live tunnel will use is a false
    // positive. An empty PSK (the WARP default) is `None`, not an error.
    let preshared_key = match request.amnezia.preshared_key_opt() {
        Some(value) => Some(decode_key(value).map_err(|_| WarpProbeError::InvalidKey("preshared key"))?),
        None => None,
    };
    let mut peer = Box::new(Tunn::new(
        private_key,
        boringtun::x25519::PublicKey::from(peer_public_key),
        preshared_key,
        request.amnezia.persistent_keepalive_opt(),
        0,
        None,
    ));
    if let Some(codec) = &amnezia {
        let mut timers = codec.params().v3().timer_config();
        timers.persistent_keepalive = request.amnezia.persistent_keepalive_opt().map(TimerRange::fixed);
        peer.set_awg_timer_config(timers).map_err(|error| WarpProbeError::Handshake(error.to_owned()))?;
        peer.set_awg_disable_cookies(request.amnezia.v3.disable_cookies)
            .map_err(|error| WarpProbeError::Handshake(error.to_owned()))?;
        peer.set_transport_padding(codec.clone()).map_err(|error| WarpProbeError::Handshake(error.to_owned()))?;
    }
    let udp = bind_probe_socket(endpoint, platform).map_err(WarpProbeError::Io)?;
    let mut outbound = [0u8; MAX_PACKET];
    let started_at = std::time::Instant::now();
    let deadline = Instant::now() + Duration::from_millis(request.timeout_ms.max(250));
    match peer.format_handshake_initiation(&mut outbound, true) {
        TunnResult::WriteToNetwork(packet) => {
            send_probe_packet(&udp, endpoint, packet, reserved, amnezia.as_deref(), deadline).await?;
        }
        TunnResult::Err(error) => return Err(WarpProbeError::Handshake(format!("{error:?}"))),
        _ => return Err(WarpProbeError::Handshake("handshake initiation did not produce a packet".to_owned())),
    }
    let mut recv_buf = [0u8; MAX_PACKET];
    let mut decap_buf = [0u8; MAX_PACKET];
    let mut tick = tokio::time::interval(Duration::from_millis(250));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        if Instant::now() >= deadline {
            return Err(WarpProbeError::Timeout);
        }
        let received = timeout_at(deadline, async {
            tokio::select! {
                received = udp.recv_from(&mut recv_buf) => Some(received),
                _ = tick.tick() => None,
            }
        })
        .await
        .map_err(|_| WarpProbeError::Timeout)?;
        if Instant::now() >= deadline {
            return Err(WarpProbeError::Timeout);
        }
        let Some(received) = received else {
            if let TunnResult::WriteToNetwork(packet) = peer.update_timers(&mut outbound) {
                send_probe_packet(&udp, endpoint, packet, reserved, amnezia.as_deref(), deadline).await?;
            }
            continue;
        };
        let (size, source) = received.map_err(WarpProbeError::Io)?;
        if source != endpoint {
            continue;
        }
        let (result, outcome) = receive_packet(
            &mut peer,
            amnezia.as_deref(),
            CookieSource::Udp(source),
            &mut recv_buf[..size],
            &mut decap_buf,
        );
        if let ReceiveOutput::Network(length) = result {
            send_probe_packet(&udp, endpoint, &decap_buf[..length], reserved, amnezia.as_deref(), deadline).await?;
        }
        if matches!(
            outcome,
            ReceiveOutcome::HandshakeResponse | ReceiveOutcome::TransportEmpty | ReceiveOutcome::TransportIp
        ) {
            if Instant::now() >= deadline {
                return Err(WarpProbeError::Timeout);
            }
            return Ok(probe_result_from_endpoint(endpoint, &request.endpoint, started_at.elapsed()));
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation can stop the prelude after some signature/junk packets.
/// The caller drops the complete probe on cancellation or deadline expiry.
async fn send_probe_packet(
    udp: &UdpSocket,
    endpoint: SocketAddr,
    packet: &[u8],
    reserved: [u8; 3],
    codec: Option<&AwgWireCodec>,
    deadline: Instant,
) -> Result<(), WarpProbeError> {
    if Instant::now() >= deadline {
        return Err(WarpProbeError::Timeout);
    }
    let payload = encode_probe_packet(packet, reserved, codec)
        .ok_or_else(|| WarpProbeError::Handshake("AmneziaWG packet encoding failed".to_owned()))?;
    if packet.first() == Some(&1)
        && let Some(codec) = codec
    {
        for junk in codec.params().handshake_prelude(&mut rand_u32) {
            if Instant::now() >= deadline {
                return Err(WarpProbeError::Timeout);
            }
            timeout_at(deadline, udp.send_to(&junk, endpoint))
                .await
                .map_err(|_| WarpProbeError::Timeout)?
                .map_err(WarpProbeError::Io)?;
        }
    }
    if Instant::now() >= deadline {
        return Err(WarpProbeError::Timeout);
    }
    timeout_at(deadline, udp.send_to(&payload, endpoint))
        .await
        .map_err(|_| WarpProbeError::Timeout)?
        .map_err(WarpProbeError::Io)?;
    Ok(())
}

fn bind_probe_socket(endpoint: SocketAddr, platform: &WarpPlatform) -> std::io::Result<UdpSocket> {
    let bind_addr = if endpoint.is_ipv4() {
        SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))
    } else {
        "[::]:0".parse().expect("ipv6 bind addr")
    };
    let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
    socket.bind(&bind_addr.into())?;
    // Fail-closed: a protect rejection drops the probe socket here rather than
    // probing through an unprotected socket (vpnservice-protect-invariant).
    protect_socket_if_configured(&socket, platform)?;
    socket.set_nonblocking(true)?;
    UdpSocket::from_std(socket.into())
}

fn encode_probe_packet(packet: &[u8], reserved: [u8; 3], amnezia: Option<&AwgWireCodec>) -> Option<Vec<u8>> {
    match amnezia {
        Some(codec) => codec.encode_with_reserved(packet, reserved),
        None => Some({
            let mut payload = packet.to_vec();
            apply_reserved_bytes(&mut payload, reserved);
            payload
        }),
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::WarpAmneziaConfig;

    // A valid 32-byte base64 key (all zero bytes) for the keys that must decode
    // so the probe reaches the preshared-key decode and fails there, not earlier.
    const VALID_KEY_B64: &str = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    fn request_with_psk(preshared_key: &str) -> WarpEndpointProbeRequest {
        let amnezia = WarpAmneziaConfig { preshared_key: preshared_key.to_owned(), ..Default::default() };
        WarpEndpointProbeRequest {
            // Literal IPv4 so resolution is synchronous and offline; the probe
            // fails at the PSK decode before any socket is bound.
            endpoint: ResolvedWarpRuntimeEndpoint {
                host: "vpn.example.org".to_owned(),
                ipv4: Some("203.0.113.7".to_owned()),
                ipv6: None,
                port: 51820,
                source: "test".to_owned(),
            },
            private_key: VALID_KEY_B64.to_owned(),
            peer_public_key: VALID_KEY_B64.to_owned(),
            client_id: None,
            amnezia,
            timeout_ms: 250,
        }
    }

    fn probe(preshared_key: &str) -> Result<WarpEndpointProbeResult, WarpProbeError> {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("runtime")
            .block_on(probe_endpoint(request_with_psk(preshared_key)))
    }

    #[test]
    fn probe_rejects_non_base64_preshared_key() {
        match probe("not valid base64!!!") {
            Err(WarpProbeError::InvalidKey("preshared key")) => {}
            other => panic!("malformed PSK must fail closed with InvalidKey(\"preshared key\"), got: {other:?}"),
        }
    }

    #[test]
    fn probe_rejects_wrong_length_preshared_key() {
        // Decodes cleanly from base64 but yields fewer than 32 bytes.
        match probe("AAAA") {
            Err(WarpProbeError::InvalidKey("preshared key")) => {}
            other => panic!("short PSK must fail closed with InvalidKey(\"preshared key\"), got: {other:?}"),
        }
    }
    #[tokio::test]
    async fn probe_rejects_invalid_profiles_without_panicking() {
        for invalid in [
            WarpAmneziaConfig { enabled: true, wire_profile: String::new(), ..Default::default() },
            WarpAmneziaConfig { enabled: true, h1: "100-199".into(), h2: "150-200".into(), ..Default::default() },
            WarpAmneziaConfig { enabled: true, i1: "deadbeef".into(), ..Default::default() },
            WarpAmneziaConfig { enabled: true, jc: 129, ..Default::default() },
        ] {
            let mut request = request_with_psk("");
            request.amnezia = invalid;
            assert!(matches!(probe_endpoint(request).await, Err(WarpProbeError::InvalidAwgParameters(_))));
        }
    }

    /// # Cancel safety:
    /// NOT cancel-safe: the test owns a probe task and joins it after the bounded exchange.
    #[tokio::test]
    async fn an_authenticated_cookie_reply_does_not_make_a_probe_succeed() {
        use base64::Engine as _;
        use boringtun::noise::rate_limiter::RateLimiter;
        use boringtun::x25519::{PublicKey, StaticSecret};
        let receiver = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let server_key = StaticSecret::from([9; 32]);
        let server_public = PublicKey::from(&server_key);
        let client_key = StaticSecret::from([7; 32]);
        let mut server = Tunn::new(
            server_key,
            PublicKey::from(&client_key),
            None,
            None,
            1,
            Some(Arc::new(RateLimiter::new(&server_public, 0))),
        );
        let mut request = request_with_psk("");
        request.endpoint.ipv4 = Some("127.0.0.1".into());
        request.endpoint.port = i32::from(receiver.local_addr().unwrap().port());
        request.private_key = base64::engine::general_purpose::STANDARD.encode([7; 32]);
        request.peer_public_key = base64::engine::general_purpose::STANDARD.encode(server_public.as_bytes());
        let probe = tokio::spawn(probe_endpoint(request));
        let mut input = [0; 2048];
        let mut out = [0; 2048];
        let (size, client) = timeout(Duration::from_secs(1), receiver.recv_from(&mut input)).await.unwrap().unwrap();
        let TunnResult::WriteToNetwork(cookie) = server.decapsulate(Some(client.ip()), &input[..size], &mut out) else {
            panic!("challenge")
        };
        assert_eq!(&cookie[..4], &[3, 0, 0, 0]);
        receiver.send_to(cookie, client).await.unwrap();
        let result = probe.await.unwrap();
        assert!(matches!(result, Err(WarpProbeError::Timeout)), "cookie is not authenticated peer proof: {result:?}");
    }

    /// # Cancel safety:
    /// cancel-safe: this test owns two local sockets and creates no tasks.
    #[tokio::test]
    async fn expired_probe_deadline_prevents_a_ready_udp_send() {
        let sender = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let receiver = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let deadline = Instant::now() - Duration::from_secs(1);
        assert!(matches!(
            send_probe_packet(&sender, receiver.local_addr().unwrap(), &[1; 148], [0; 3], None, deadline).await,
            Err(WarpProbeError::Timeout)
        ));
        assert_eq!(receiver.try_recv(&mut [0; 256]).unwrap_err().kind(), std::io::ErrorKind::WouldBlock);
    }

    #[tokio::test]
    async fn probe_sends_all_signature_slots_then_junk_then_range_handshake() {
        let receiver = UdpSocket::bind("127.0.0.1:0").await.expect("listener");
        let mut request = request_with_psk("");
        request.endpoint.ipv4 = Some("127.0.0.1".into());
        request.endpoint.port = i32::from(receiver.local_addr().expect("address").port());
        request.amnezia = WarpAmneziaConfig {
            enabled: true,
            h1: "100-199".into(),
            h2: "200-299".into(),
            h3: "300-399".into(),
            h4: "400-499".into(),
            i1: "<b 0x01>".into(),
            i2: "<b 0x02>".into(),
            i3: "<b 0x03>".into(),
            i4: "<b 0x04>".into(),
            i5: "<b 0x05>".into(),
            jc: 2,
            jmin: 9,
            jmax: 9,
            ..Default::default()
        };
        let probe = tokio::spawn(probe_endpoint(request));
        let mut buffer = [0u8; MAX_PACKET];
        for expected in 1u8..=5 {
            let (size, _) = timeout(Duration::from_secs(2), receiver.recv_from(&mut buffer))
                .await
                .expect("signature deadline")
                .expect("signature datagram");
            assert_eq!(&buffer[..size], &[expected]);
        }
        for _ in 0..2 {
            let (size, _) = timeout(Duration::from_secs(2), receiver.recv_from(&mut buffer))
                .await
                .expect("junk deadline")
                .expect("junk datagram");
            assert_eq!(size, 9);
        }
        let (size, _) = timeout(Duration::from_secs(2), receiver.recv_from(&mut buffer))
            .await
            .expect("handshake deadline")
            .expect("handshake datagram");
        assert_eq!(size, 148);
        let header = u32::from_le_bytes(buffer[..4].try_into().expect("header"));
        assert!((100..=199).contains(&header));
        assert!(matches!(probe.await.expect("probe must not panic"), Err(WarpProbeError::Timeout)));
    }
}
