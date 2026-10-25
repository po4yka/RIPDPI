use std::io;
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use anyhow::Context;
use boringtun::noise::errors::WireGuardError;
use boringtun::noise::{Tunn, TunnResult};
use bytes::Bytes;

use super::awg_timers::{AwgTimers, keepalive_interval};
use super::carrier::WgCarrier;
use super::keys::{apply_reserved_bytes, decode_key};
use super::routing::route_protocol;
use super::socket::bind_tunnel_socket;
use crate::amneziawg::{AwgMacKeys, AwgParams, AwgParamsError, AwgWireCodec, rand_u32};
use crate::config::WarpAmneziaConfig;
use crate::platform::WarpPlatform;
use crate::support::MAX_PACKET;
use crate::virtual_iface::{Bus, Event};

/// Build the AmneziaWG wire codec for a tunnel from its config.
///
/// `signature_programs` carries the AWG 3.1 `I1..I5` signature programs. The native WARP runtime now sources them from the resolved
/// config's own `i1..i5` fields (via [`WarpAmneziaConfig::signature_programs`]),
/// and a generic AmneziaWG profile maps its `AmneziaWgObfuscation` `I*` fields
/// here; empty strings mean unset. Invalid active parameters and platform
/// compatibility errors fail tunnel construction. Only an explicitly disabled
/// config may select plain WireGuard.
pub(crate) fn build_authenticated_awg_codec(
    cfg: &WarpAmneziaConfig,
    signature_programs: &[&str],
    mac_keys: AwgMacKeys,
) -> Result<Option<AwgWireCodec>, AwgParamsError> {
    build_awg_codec_for_platform(
        cfg,
        signature_programs,
        cfg!(all(target_os = "android", target_arch = "aarch64")),
        Some(mac_keys),
    )
}

fn build_awg_codec_for_platform(
    cfg: &WarpAmneziaConfig,
    signature_programs: &[&str],
    is_android_arm64: bool,
    mac_keys: Option<AwgMacKeys>,
) -> Result<Option<AwgWireCodec>, AwgParamsError> {
    if !cfg.enabled {
        return Ok(None);
    }
    let params = AwgParams::from_config_for_platform(cfg, signature_programs, is_android_arm64)?;
    Ok(Some(match mac_keys {
        Some(mac_keys) => AwgWireCodec::new_authenticated(params, mac_keys),
        None => AwgWireCodec::new(params),
    }))
}

/// Construction parameters for a [`WireGuardTunnel`].
///
/// Bundled into a struct (rather than positional arguments) because a generic
/// AmneziaWG profile needs more knobs than WARP -- a preshared key, a
/// configurable persistent-keepalive interval, and the AWG 3.1 `I1..I5`
/// special-junk frames -- and a 10-argument constructor trips
/// `clippy::too_many_arguments`.
pub(crate) struct WireGuardTunnelParams<'a> {
    /// Base64-encoded Curve25519 interface private key.
    pub private_key: &'a str,
    /// Base64-encoded Curve25519 peer (server) public key.
    pub peer_public_key: &'a str,
    /// Optional base64-encoded 32-byte WireGuard preshared key (PSK). WARP
    /// does not use one; a generic AmneziaWG peer may.
    pub preshared_key: Option<&'a str>,
    /// Persistent-keepalive interval in seconds. WARP pins `Some(25)`; a
    /// generic profile maps its (nullable) `PersistentKeepalive` field here.
    pub persistent_keepalive: Option<u16>,
    /// Upper bound of an AWG 3.x `PersistentKeepalive = lo-hi` range. Above
    /// `persistent_keepalive`, each interval is drawn from the range.
    pub persistent_keepalive_max: Option<u16>,
    /// Resolved UDP endpoint of the peer.
    pub endpoint: SocketAddr,
    /// The 3 WireGuard reserved bytes (WARP derives them from the Cloudflare
    /// client id; generic profiles pass `[0; 3]`).
    pub reserved: [u8; 3],
    /// Local interface IPv4 used to classify inbound tunnel packets.
    pub source_peer_ip: IpAddr,
    pub source_peer_ipv6: Option<Ipv6Addr>,
    /// AmneziaWG `Jc/Jmin/Jmax/H1..H4/S1..S4` obfuscation knobs.
    pub amnezia_cfg: &'a WarpAmneziaConfig,
    /// AmneziaWG 3.1 `I1..I5` signature programs. Empty strings = unset.
    pub signature_programs: [&'a str; 5],
    /// Pre-built datagram transport. `None` is the default plain-UDP path: the
    /// tunnel binds + protects its own [`UdpSocket`](tokio::net::UdpSocket) via
    /// `bind_tunnel_socket`. `Some(carrier)` selects an already-connected
    /// WG-over-WebSocket carrier (the WS-select path); ownership transfers into
    /// the tunnel. WARP and the plain-UDP AmneziaWG path pass `None`.
    pub carrier: Option<WgCarrier>,
}

pub(crate) struct WireGuardTunnel {
    peer: tokio::sync::Mutex<Box<Tunn>>,
    carrier: WgCarrier,
    outbound: tokio::sync::Mutex<()>,
    endpoint: SocketAddr,
    source_peer_ip: IpAddr,
    source_peer_ipv6: Option<Ipv6Addr>,
    reserved: [u8; 3],
    amnezia: Option<AwgWireCodec>,
    handshake_readiness: HandshakeReadiness,
    /// AWG 3.x keepalive range in seconds; boringtun's fixed keepalive is off when set.
    keepalive_range: Option<(u16, u16)>,
    /// Stamps for the AWG 3.x keepalive range and `RekeyAfterTime`.
    timers: AwgTimers,
}

/// Room for the largest message the AWG timers make: a 148-byte initiation or a keepalive.
const TIMER_PACKET_BUFFER: usize = 256;

#[derive(Default)]
struct HandshakeReadiness {
    established: AtomicBool,
}

impl HandshakeReadiness {
    fn observe_authenticated_packet(&self, packet: &[u8]) {
        if wireguard_message_type(packet) == Some(2) {
            self.established.store(true, Ordering::SeqCst);
        }
    }

    fn is_established(&self) -> bool {
        self.established.load(Ordering::SeqCst)
    }
}

impl WireGuardTunnel {
    pub(crate) async fn new(params: WireGuardTunnelParams<'_>, platform: &WarpPlatform) -> anyhow::Result<Self> {
        let WireGuardTunnelParams {
            private_key,
            peer_public_key,
            preshared_key,
            persistent_keepalive,
            persistent_keepalive_max,
            endpoint,
            reserved,
            source_peer_ip,
            source_peer_ipv6,
            amnezia_cfg,
            signature_programs,
            carrier,
        } = params;
        let private_key = decode_key(private_key).context("invalid WireGuard private key")?;
        let peer_public_key = decode_key(peer_public_key).context("invalid WireGuard peer public key")?;
        let preshared_key = match preshared_key.filter(|value| !value.is_empty()) {
            Some(value) => Some(decode_key(value).context("invalid WireGuard preshared key")?),
            None => None,
        };
        let keepalive_range = match (persistent_keepalive, persistent_keepalive_max) {
            (Some(low), Some(high)) if high > low => Some((low, high)),
            _ => None,
        };
        let private_key = boringtun::x25519::StaticSecret::from(private_key);
        let local_public_key = boringtun::x25519::PublicKey::from(&private_key).to_bytes();
        let peer = Box::new(Tunn::new(
            private_key,
            boringtun::x25519::PublicKey::from(peer_public_key),
            preshared_key,
            if keepalive_range.is_some() { None } else { persistent_keepalive },
            0,
            None,
        ));
        // Default (carrier = None): bind + protect a plain WireGuard UDP socket.
        // A WS-select profile supplies an already-connected, already-protected
        // carrier, which takes ownership unchanged.
        let carrier = match carrier {
            Some(carrier) => carrier,
            None => WgCarrier::Udp(bind_tunnel_socket(endpoint, platform)?),
        };
        let amnezia = build_authenticated_awg_codec(
            amnezia_cfg,
            &signature_programs,
            AwgMacKeys::new(local_public_key, peer_public_key),
        )?;
        Ok(Self {
            peer: tokio::sync::Mutex::new(peer),
            carrier,
            outbound: tokio::sync::Mutex::new(()),
            endpoint,
            source_peer_ip,
            source_peer_ipv6,
            reserved,
            amnezia,
            handshake_readiness: HandshakeReadiness::default(),
            keepalive_range,
            timers: AwgTimers::new(),
        })
    }

    /// Send a complete initiation prelude under the same lock as its packet.
    /// Retries, rekeys, and the first initiation all pass through this path.
    async fn send_outbound_packet(&self, packet: &[u8]) -> io::Result<()> {
        let _outbound = self.outbound.lock().await;
        let payload = self
            .encode_outbound_packet(packet)
            .ok_or_else(|| io::Error::other("AmneziaWG packet authentication failed"))?;
        if wireguard_message_type(packet) == Some(1)
            && let Some(codec) = &self.amnezia
        {
            for junk in codec.params().handshake_prelude(&mut rand_u32) {
                self.carrier.send_to(&junk, self.endpoint).await?;
            }
        }
        self.carrier.send_to(&payload, self.endpoint).await?;
        if wireguard_message_type(packet) == Some(1) {
            self.timers.mark_initiation();
        }
        self.timers.mark_traffic();
        Ok(())
    }

    /// Start a WireGuard handshake explicitly after the receive task is live.
    ///
    /// The runtime cannot use the local SOCKS bind as its readiness boundary:
    /// a dead or wire-incompatible peer would otherwise look connected. The
    /// authenticated response observed by [`Self::consume_task`] is the remote
    /// readiness proof.
    pub(crate) async fn initiate_handshake(&self) -> io::Result<()> {
        let mut send_buf = [0u8; MAX_PACKET];
        let payload = {
            let mut peer = self.peer.lock().await;
            match peer.format_handshake_initiation(&mut send_buf, true) {
                TunnResult::WriteToNetwork(packet) => packet.to_vec(),
                TunnResult::Err(error) => {
                    return Err(io::Error::other(format!("WireGuard handshake initiation failed: {error:?}")));
                }
                _ => return Err(io::Error::other("WireGuard handshake initiation produced no packet")),
            }
        };
        self.send_outbound_packet(&payload).await
    }

    pub(crate) fn is_handshake_established(&self) -> bool {
        self.handshake_readiness.is_established()
    }

    /// Encrypt and send one IP packet. `padded` is a reused scratch buffer for AWG 3.x content padding.
    ///
    /// # Cancel safety
    /// cancel-safe: cancellation drops at most this one datagram; the boringtun
    /// lock is released before the send, and `padded` is rewritten on every call.
    async fn send_ip_packet(&self, packet: &[u8], padded: &mut Vec<u8>) {
        // AWG 3.x content padding rides inside the ciphertext; the receiver trims to the IP length.
        let packet = match &self.amnezia {
            Some(codec) if codec.pad_transport_plaintext_into(packet, padded) => padded.as_slice(),
            _ => packet,
        };
        let mut send_buf = [0u8; MAX_PACKET];
        let result = { self.peer.lock().await.encapsulate(packet, &mut send_buf) };
        self.send_tunn_result(result).await;
        self.refresh_key_after_send().await;
    }

    /// AWG 3.x `RekeyAfterTime` (`keepKeyFreshSending`): after a send, start a
    /// handshake once our session is older than a fresh sample, at most once per
    /// `rekeyMinTimeout`. boringtun's fixed 120 s rekey still applies when the
    /// sample is larger. boringtun does not expose the initiator role; a RIPDPI
    /// client starts every session, so the session stamp comes from our responses.
    ///
    /// # Cancel safety
    /// cancel-safe: the boringtun lock is not held across an await, and a dropped
    /// initiation is retried by the next send or by boringtun's own timers.
    async fn refresh_key_after_send(&self) {
        let Some(limit) = self.amnezia.as_ref().and_then(|codec| codec.params().v3().rekey_after_time()) else {
            return;
        };
        if !self.timers.early_rekey_due(limit) {
            return;
        }
        let mut send_buf = [0u8; TIMER_PACKET_BUFFER];
        let result = {
            let mut peer = self.peer.lock().await;
            match peer.time_since_last_handshake() {
                Some(age) if age > limit => peer.format_handshake_initiation(&mut send_buf, false),
                _ => TunnResult::Done,
            }
        };
        self.send_tunn_result(result).await;
    }

    fn encode_outbound_packet(&self, packet: &[u8]) -> Option<Vec<u8>> {
        match &self.amnezia {
            // `encode_with_reserved` overlays the reserved bytes during its
            // single output copy, dropping the redundant per-packet `to_vec`.
            Some(codec) => codec.encode_with_reserved(packet, self.reserved),
            None => Some({
                let mut payload = packet.to_vec();
                apply_reserved_bytes(&mut payload, self.reserved);
                payload
            }),
        }
    }

    async fn send_tunn_result<'a>(&self, result: TunnResult<'a>) {
        match result {
            TunnResult::WriteToNetwork(packet) => {
                if let Err(error) = self.send_outbound_packet(packet).await {
                    tracing::warn!("WARP tunnel send failed: {error}");
                }
            }
            TunnResult::Done => {}
            TunnResult::Err(error) => tracing::warn!("WARP tunnel write failed: {error:?}"),
            _ => {}
        }
    }

    pub(crate) async fn produce_task(&self, bus: Bus) {
        let mut endpoint = bus.new_endpoint();
        let mut padded = Vec::new();
        loop {
            match endpoint.recv().await {
                Event::Shutdown => break,
                Event::OutboundInternetPacket(packet) => self.send_ip_packet(&packet, &mut padded).await,
                _ => {}
            }
        }
    }

    pub(crate) async fn routine_task(&self) {
        // (traffic stamp, deadline): any authenticated packet re-arms the timer with a fresh draw
        // (`timersAnyAuthenticatedPacketTraversal`), so it fires only after an idle interval.
        let mut keepalive =
            self.keepalive_range.map(|range| (self.timers.traffic_stamp(), Instant::now() + keepalive_interval(range)));
        loop {
            let mut send_buf = [0u8; MAX_PACKET];
            let result = { self.peer.lock().await.update_timers(&mut send_buf) };
            self.send_tunn_result(result).await;
            if let (Some(range), Some((armed_at, deadline))) = (self.keepalive_range, keepalive.as_mut()) {
                let stamp = self.timers.traffic_stamp();
                if stamp != *armed_at {
                    *armed_at = stamp;
                    *deadline = Instant::now() + keepalive_interval(range);
                }
                if Instant::now() < *deadline {
                    tokio::time::sleep(Duration::from_millis(1)).await;
                    continue;
                }
                *deadline = Instant::now() + keepalive_interval(range);
                let result = {
                    let mut peer = self.peer.lock().await;
                    // Only an established session sends; an empty encapsulate would otherwise queue a handshake.
                    if peer.time_since_last_handshake().is_some() {
                        peer.encapsulate(&[], &mut send_buf)
                    } else {
                        TunnResult::Done
                    }
                };
                self.send_tunn_result(result).await;
            }
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    }

    pub(crate) async fn consume_task(&self, bus: Bus) {
        let endpoint = bus.new_endpoint();
        loop {
            let mut recv_buf = [0u8; MAX_PACKET];
            let mut send_buf = [0u8; MAX_PACKET];
            let size = match self.carrier.recv(&mut recv_buf).await {
                Ok(size) => size,
                Err(error) => {
                    tracing::warn!("WARP tunnel recv failed: {error}");
                    tokio::time::sleep(Duration::from_millis(10)).await;
                    continue;
                }
            };
            let raw = &recv_buf[..size];
            // When AmneziaWG obfuscation is active, decode the incoming packet
            // before passing it to boringtun. Packets whose header does not
            // match any of h1-h4 (e.g. junk injected by the remote peer during
            // its own handshake setup) are silently discarded.
            //
            // `decoded_buf` holds the reconstructed WG packet (type byte +
            // payload) when amnezia is active. When inactive, `data` points
            // directly into `recv_buf` and `decoded_buf` is unused.
            let decoded_buf: Vec<u8> = if let Some(codec) = &self.amnezia {
                match codec.decode(raw) {
                    Some(packet) => packet,
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
            if authenticated_by_boringtun(&result, data, self.amnezia.is_some()) {
                self.timers.mark_traffic();
                match wireguard_message_type(data) {
                    Some(2) => self.timers.mark_session(),
                    // The UDP window learns only from datagrams that passed AEAD and the replay check.
                    Some(4) => {
                        if let Some(codec) = &self.amnezia {
                            codec.observe_authenticated_transport(size);
                        }
                    }
                    _ => {}
                }
            }
            match result {
                TunnResult::WriteToNetwork(packet) => {
                    self.handshake_readiness.observe_authenticated_packet(data);
                    if let Err(error) = self.send_outbound_packet(packet).await {
                        tracing::warn!("WARP tunnel send failed: {error}");
                    }
                }
                TunnResult::WriteToTunnelV4(packet, _) | TunnResult::WriteToTunnelV6(packet, _) => {
                    if let Some(protocol) = route_protocol(packet, self.source_peer_ip, self.source_peer_ipv6) {
                        endpoint.send(Event::InboundInternetPacket(protocol, Bytes::copy_from_slice(packet)));
                    }
                }
                TunnResult::Done => {
                    self.handshake_readiness.observe_authenticated_packet(data);
                }
                TunnResult::Err(WireGuardError::InvalidPacket) if is_padded_keepalive(data, self.amnezia.is_some()) => {
                    tracing::debug!("AmneziaWG padded keepalive received");
                }
                TunnResult::Err(error) => {
                    tracing::warn!("WARP tunnel decapsulation failed: {error:?}");
                }
            }
        }
    }
}

/// An AWG 3.x peer pads keepalives with zeros inside the ciphertext. boringtun
/// decrypts and authenticates them, then rejects the zero "IP header" as
/// `InvalidPacket`. For a well-sized transport packet that is the only source of
/// `InvalidPacket`, because the size and type checks already passed.
fn is_padded_keepalive(decoded: &[u8], awg_active: bool) -> bool {
    awg_active && wireguard_message_type(decoded) == Some(4)
}

/// Whether boringtun accepted `decoded` as an authenticated message (MAC, AEAD, and replay checks).
fn authenticated_by_boringtun(result: &TunnResult<'_>, decoded: &[u8], awg_active: bool) -> bool {
    match result {
        TunnResult::WriteToNetwork(_)
        | TunnResult::WriteToTunnelV4(..)
        | TunnResult::WriteToTunnelV6(..)
        | TunnResult::Done => true,
        TunnResult::Err(WireGuardError::InvalidPacket) => is_padded_keepalive(decoded, awg_active),
        TunnResult::Err(_) => false,
    }
}

fn wireguard_message_type(packet: &[u8]) -> Option<u32> {
    packet.get(..4).map(|header| u32::from_le_bytes(header.try_into().expect("four-byte WireGuard header")))
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv4Addr, SocketAddr};

    use super::*;

    // A valid 32-byte base64 key (all zero bytes). Used for the keys that must
    // decode successfully so the test isolates the *preshared* key failure.
    const VALID_KEY_B64: &str = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    // Non-loopback, never connected: a non-empty malformed PSK is rejected
    // before any socket is bound, so this address is never touched.
    fn dummy_endpoint() -> SocketAddr {
        SocketAddr::from((Ipv4Addr::new(203, 0, 113, 7), 51820))
    }

    /// Drive [`WireGuardTunnel::new`] with valid identity keys and the given
    /// PSK, returning the construction error string (or `None` on success). The
    /// AmneziaWG config borrow must outlive the `block_on`, so it is held in a
    /// local here rather than handed back from a `'static` helper.
    fn build_error(preshared_key: Option<&str>) -> Option<String> {
        let amnezia = WarpAmneziaConfig::default();
        let params = WireGuardTunnelParams {
            private_key: VALID_KEY_B64,
            peer_public_key: VALID_KEY_B64,
            preshared_key,
            persistent_keepalive: None,
            persistent_keepalive_max: None,
            endpoint: dummy_endpoint(),
            reserved: [0u8; 3],
            source_peer_ip: IpAddr::V4(Ipv4Addr::new(10, 8, 0, 2)),
            source_peer_ipv6: None,
            amnezia_cfg: &amnezia,
            signature_programs: ["", "", "", "", ""],
            carrier: None,
        };
        let result = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("runtime")
            .block_on(WireGuardTunnel::new(params, &WarpPlatform::default()));
        result.err().map(|error| error.to_string())
    }

    #[test]
    fn new_rejects_non_base64_preshared_key() {
        let error = build_error(Some("not valid base64!!!")).expect("malformed PSK must fail closed");
        assert!(error.contains("invalid WireGuard preshared key"), "error must be the PSK context, got: {error}");
    }

    #[test]
    fn new_rejects_wrong_length_preshared_key() {
        // Decodes cleanly from base64 but yields fewer than 32 bytes.
        let error = build_error(Some("AAAA")).expect("short PSK must fail closed");
        assert!(error.contains("invalid WireGuard preshared key"), "error must be the PSK context, got: {error}");
    }

    #[test]
    fn invalid_active_awg_headers_fail_closed() {
        let config =
            WarpAmneziaConfig { enabled: true, h1: "42".into(), h2: "42".into(), ..WarpAmneziaConfig::default() };

        let result = build_awg_codec_for_platform(&config, &[""; 5], false, None);

        assert!(
            matches!(result, Err(AwgParamsError::HeaderCollision { .. })),
            "invalid active AWG config must not silently fall back to plain WireGuard"
        );
    }

    #[test]
    fn shared_codec_propagates_android_arm64_compatibility_error() {
        let config = WarpAmneziaConfig { enabled: true, s3: 1, ..WarpAmneziaConfig::default() };

        let result = build_awg_codec_for_platform(&config, &[""; 5], true, None);

        assert!(matches!(result, Err(AwgParamsError::Arm64S34VersionFloor { s3: 1, s4: 0 })));
    }

    #[tokio::test]
    async fn timer_retry_sends_fresh_signature_and_junk_before_each_initiation() {
        let receiver = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let config = WarpAmneziaConfig {
            enabled: true,
            jc: 2,
            jmin: 64,
            jmax: 64,
            h1: "100-199".into(),
            h2: "200-299".into(),
            h3: "300-399".into(),
            h4: "400-499".into(),
            i1: "<b 0xf0><r 16>".into(),
            i2: "<b 0xf2>".into(),
            ..Default::default()
        };
        let tunnel = std::sync::Arc::new(
            WireGuardTunnel::new(
                WireGuardTunnelParams {
                    private_key: VALID_KEY_B64,
                    peer_public_key: "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                    preshared_key: None,
                    persistent_keepalive: None,
                    persistent_keepalive_max: None,
                    endpoint: receiver.local_addr().unwrap(),
                    reserved: [0; 3],
                    source_peer_ip: "10.77.0.2".parse().unwrap(),
                    source_peer_ipv6: None,
                    amnezia_cfg: &config,
                    signature_programs: config.signature_programs(),
                    carrier: None,
                },
                &WarpPlatform::default(),
            )
            .await
            .unwrap(),
        );
        tunnel.initiate_handshake().await.unwrap();
        let routine = tokio::spawn({
            let tunnel = tunnel.clone();
            async move { tunnel.routine_task().await }
        });
        let mut signatures = Vec::new();
        let result = tokio::time::timeout(Duration::from_secs(12), async {
            for _ in 0..2 {
                for slot in 0..5 {
                    let mut buffer = [0; MAX_PACKET];
                    let size = receiver.recv(&mut buffer).await.unwrap();
                    match slot {
                        0 => {
                            assert_eq!(size, 17);
                            assert_eq!(buffer[0], 0xf0);
                            signatures.push(buffer[..size].to_vec());
                        }
                        1 => assert_eq!(&buffer[..size], &[0xf2]),
                        2 | 3 => assert_eq!(size, 64),
                        4 => {
                            assert_eq!(size, 148);
                            assert!((100..=199).contains(&wireguard_message_type(&buffer).unwrap()));
                        }
                        _ => unreachable!(),
                    }
                }
            }
        })
        .await;
        routine.abort();
        result.expect("initial handshake and timer retry");
        assert_ne!(signatures[0], signatures[1]);
    }

    #[test]
    fn only_boringtun_authenticated_transport_may_teach_the_udp_window() {
        let transport = [4, 0, 0, 0, 1, 0, 0, 0];
        assert!(!authenticated_by_boringtun(&TunnResult::Err(WireGuardError::InvalidAeadTag), &transport, true));
        assert!(!authenticated_by_boringtun(&TunnResult::Err(WireGuardError::DuplicateCounter), &transport, true));
        assert!(!authenticated_by_boringtun(&TunnResult::Err(WireGuardError::NoCurrentSession), &transport, true));
        assert!(authenticated_by_boringtun(&TunnResult::Done, &transport, true));
        assert!(authenticated_by_boringtun(&TunnResult::Err(WireGuardError::InvalidPacket), &transport, true));
        assert!(!authenticated_by_boringtun(&TunnResult::Err(WireGuardError::InvalidPacket), &transport, false));
        assert!(!authenticated_by_boringtun(&TunnResult::Err(WireGuardError::InvalidPacket), &[1, 0, 0, 0], true));
    }

    #[test]
    fn handshake_readiness_only_accepts_an_authenticated_response() {
        let readiness = HandshakeReadiness::default();
        assert!(!readiness.is_established());

        readiness.observe_authenticated_packet(&[1, 0, 0, 0]);
        readiness.observe_authenticated_packet(&[2, 0, 0]);
        assert!(!readiness.is_established(), "an initiation or truncated header cannot publish readiness");

        readiness.observe_authenticated_packet(&[2, 0, 0, 0]);
        assert!(readiness.is_established(), "a response accepted by boringtun publishes readiness");
    }
}
