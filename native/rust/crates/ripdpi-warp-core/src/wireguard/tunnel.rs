use std::io;
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use anyhow::Context;
use boringtun::noise::{Tunn, TunnResult};
use bytes::Bytes;

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
/// `special_junk_hex` carries the AWG 2.0 `I1..I5` fixed special-junk frames
/// (hex strings). The native WARP runtime now sources them from the resolved
/// config's own `i1..i5` fields (via [`WarpAmneziaConfig::special_junk_hex`]),
/// and a generic AmneziaWG profile maps its `AmneziaWgObfuscation` `I*` fields
/// here; empty strings mean unset. Invalid active parameters and platform
/// compatibility errors fail tunnel construction. Only an explicitly disabled
/// config may select plain WireGuard.
pub(crate) fn build_authenticated_awg_codec(
    cfg: &WarpAmneziaConfig,
    special_junk_hex: &[&str],
    mac_keys: AwgMacKeys,
) -> Result<Option<AwgWireCodec>, AwgParamsError> {
    build_awg_codec_for_platform(
        cfg,
        special_junk_hex,
        cfg!(all(target_os = "android", target_arch = "aarch64")),
        Some(mac_keys),
    )
}

fn build_awg_codec_for_platform(
    cfg: &WarpAmneziaConfig,
    special_junk_hex: &[&str],
    is_android_arm64: bool,
    mac_keys: Option<AwgMacKeys>,
) -> Result<Option<AwgWireCodec>, AwgParamsError> {
    if !cfg.enabled {
        return Ok(None);
    }
    let params = AwgParams::from_config_for_platform(cfg, special_junk_hex, is_android_arm64)?;
    Ok(Some(match mac_keys {
        Some(mac_keys) => AwgWireCodec::new_authenticated(params, mac_keys),
        None => AwgWireCodec::new(params),
    }))
}

/// Construction parameters for a [`WireGuardTunnel`].
///
/// Bundled into a struct (rather than positional arguments) because a generic
/// AmneziaWG profile needs more knobs than WARP -- a preshared key, a
/// configurable persistent-keepalive interval, and the AWG 2.0 `I1..I5`
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
    /// AmneziaWG 2.0 `I1..I5` special-junk frames (hex). Empty strings = unset.
    pub special_junk_hex: [&'a str; 5],
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
    endpoint: SocketAddr,
    source_peer_ip: IpAddr,
    source_peer_ipv6: Option<Ipv6Addr>,
    reserved: [u8; 3],
    amnezia: Option<AwgWireCodec>,
    handshake_readiness: HandshakeReadiness,
}

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
            endpoint,
            reserved,
            source_peer_ip,
            source_peer_ipv6,
            amnezia_cfg,
            special_junk_hex,
            carrier,
        } = params;
        let private_key = decode_key(private_key).context("invalid WireGuard private key")?;
        let peer_public_key = decode_key(peer_public_key).context("invalid WireGuard peer public key")?;
        let preshared_key = match preshared_key.filter(|value| !value.is_empty()) {
            Some(value) => Some(decode_key(value).context("invalid WireGuard preshared key")?),
            None => None,
        };
        let private_key = boringtun::x25519::StaticSecret::from(private_key);
        let local_public_key = boringtun::x25519::PublicKey::from(&private_key).to_bytes();
        let peer = Box::new(Tunn::new(
            private_key,
            boringtun::x25519::PublicKey::from(peer_public_key),
            preshared_key,
            persistent_keepalive,
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
            &special_junk_hex,
            AwgMacKeys::new(local_public_key, peer_public_key),
        )?;
        Ok(Self {
            peer: tokio::sync::Mutex::new(peer),
            carrier,
            endpoint,
            source_peer_ip,
            source_peer_ipv6,
            reserved,
            amnezia,
            handshake_readiness: HandshakeReadiness::default(),
        })
    }

    /// Emit the AmneziaWG handshake prelude -- AWG 2.0 special-junk frames
    /// (`I1..I5`) followed by `Jc` random junk packets sized uniformly in
    /// `[Jmin, Jmax]` -- before the first real WireGuard handshake
    /// initiation, to defeat protocol fingerprinting. No-op when AWG
    /// obfuscation is disabled or configured for passthrough.
    pub(crate) async fn send_amnezia_junk(&self) {
        let Some(codec) = &self.amnezia else {
            return;
        };
        let mut rng = rand_u32;
        for junk in codec.params().handshake_prelude(&mut rng) {
            let _ = self.carrier.send_to(&junk, self.endpoint).await;
        }
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
                TunnResult::WriteToNetwork(packet) => self
                    .encode_outbound_packet(packet)
                    .ok_or_else(|| io::Error::other("AmneziaWG handshake packet authentication failed"))?,
                TunnResult::Err(error) => {
                    return Err(io::Error::other(format!("WireGuard handshake initiation failed: {error:?}")));
                }
                _ => return Err(io::Error::other("WireGuard handshake initiation produced no packet")),
            }
        };
        self.carrier.send_to(&payload, self.endpoint).await?;
        Ok(())
    }

    pub(crate) fn is_handshake_established(&self) -> bool {
        self.handshake_readiness.is_established()
    }

    async fn send_ip_packet(&self, packet: &[u8]) {
        let mut send_buf = [0u8; MAX_PACKET];
        let result = { self.peer.lock().await.encapsulate(packet, &mut send_buf) };
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
                if let Some(payload) = self.encode_outbound_packet(packet) {
                    let _ = self.carrier.send_to(&payload, self.endpoint).await;
                }
            }
            TunnResult::Done => {}
            TunnResult::Err(error) => tracing::warn!("WARP tunnel write failed: {error:?}"),
            _ => {}
        }
    }

    pub(crate) async fn produce_task(&self, bus: Bus) {
        let mut endpoint = bus.new_endpoint();
        loop {
            match endpoint.recv().await {
                Event::Shutdown => break,
                Event::OutboundInternetPacket(packet) => self.send_ip_packet(&packet).await,
                _ => {}
            }
        }
    }

    pub(crate) async fn routine_task(&self) {
        loop {
            let mut send_buf = [0u8; MAX_PACKET];
            let result = { self.peer.lock().await.update_timers(&mut send_buf) };
            self.send_tunn_result(result).await;
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
            match result {
                TunnResult::WriteToNetwork(packet) => {
                    self.handshake_readiness.observe_authenticated_packet(data);
                    if let Some(payload) = self.encode_outbound_packet(packet) {
                        let _ = self.carrier.send_to(&payload, self.endpoint).await;
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
                TunnResult::Err(error) => {
                    tracing::warn!("WARP tunnel decapsulation failed: {error:?}");
                }
            }
        }
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
            endpoint: dummy_endpoint(),
            reserved: [0u8; 3],
            source_peer_ip: IpAddr::V4(Ipv4Addr::new(10, 8, 0, 2)),
            source_peer_ipv6: None,
            amnezia_cfg: &amnezia,
            special_junk_hex: ["", "", "", "", ""],
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
        let config = WarpAmneziaConfig { enabled: true, h1: 42, h2: 42, ..WarpAmneziaConfig::default() };

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
