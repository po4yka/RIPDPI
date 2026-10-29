use std::io;
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use anyhow::Context;
use boringtun::noise::awg::{AwgTimerConfig, TimerRange};
use boringtun::noise::awg_cookies::CookieSource;
use boringtun::noise::errors::WireGuardError;
use boringtun::noise::{ReceiveOutcome, Tunn, TunnResult};
use bytes::Bytes;

use super::carrier::WgCarrier;
use super::keys::{apply_reserved_bytes, decode_key};
use super::routing::route_protocol;
use super::socket::bind_tunnel_socket;
use crate::amneziawg::{AwgMacKeys, AwgParams, AwgParamsError, AwgWireCodec, fill_random, rand_u32};
use crate::config::WarpAmneziaConfig;
use crate::platform::WarpPlatform;
use crate::support::MAX_PACKET;
use crate::virtual_iface::{Bus, Event};

/// Build the AmneziaWG wire codec for a tunnel from its config.
///
/// `signature_programs` carries the AWG 3.1 `I1..I5` signature programs. The native WARP runtime now sources them from the resolved
/// config's own `i1..i5` fields (via [`WarpAmneziaConfig::signature_programs`]),
/// and a generic AmneziaWG profile maps its `AmneziaWgObfuscation` `I*` fields
/// here; empty strings mean unset. Invalid active parameters fail tunnel
/// construction. Only an explicitly disabled
/// config may select plain WireGuard.
pub(crate) fn build_authenticated_awg_codec(
    cfg: &WarpAmneziaConfig,
    signature_programs: &[&str],
    mac_keys: AwgMacKeys,
) -> Result<Option<AwgWireCodec>, AwgParamsError> {
    build_awg_codec(cfg, signature_programs, Some(mac_keys))
}

fn build_awg_codec(
    cfg: &WarpAmneziaConfig,
    signature_programs: &[&str],
    mac_keys: Option<AwgMacKeys>,
) -> Result<Option<AwgWireCodec>, AwgParamsError> {
    if !cfg.enabled {
        return Ok(None);
    }
    let params = AwgParams::from_config(cfg, signature_programs)?;
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
    /// Inner MTU used for transport padding bounds.
    pub mtu: usize,
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
    amnezia: Option<Arc<AwgWireCodec>>,
    handshake_readiness: HandshakeReadiness,
    carrier_cookie_id: [u8; 16],
}

/// The boringtun device ticks its timers every 250 ms; they are rounded to a second.
const TIMER_TICK: Duration = Duration::from_millis(250);

#[derive(Default)]
struct HandshakeReadiness {
    established: AtomicBool,
}

impl HandshakeReadiness {
    fn observe(&self, outcome: ReceiveOutcome) {
        if matches!(
            outcome,
            ReceiveOutcome::HandshakeResponse | ReceiveOutcome::TransportEmpty | ReceiveOutcome::TransportIp
        ) {
            self.established.store(true, Ordering::SeqCst);
        }
    }

    fn is_established(&self) -> bool {
        self.established.load(Ordering::SeqCst)
    }
}

impl WireGuardTunnel {
    /// Construct the peer and validate its policy before it processes traffic.
    ///
    /// # Cancel safety:
    /// cancel-safe: construction has no suspension point; owned sockets are dropped on error.
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
            mtu,
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
        let amnezia = build_authenticated_awg_codec(
            amnezia_cfg,
            &signature_programs,
            AwgMacKeys::new(local_public_key, peer_public_key),
        )?
        .map(|codec| Arc::new(codec.with_mtu(mtu)));
        let mut peer = Box::new(Tunn::new(
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
        if amnezia.is_some() || keepalive_range.is_some() {
            let mut timers =
                amnezia.as_ref().map_or_else(AwgTimerConfig::default, |codec| codec.params().v3().timer_config());
            timers.persistent_keepalive = match persistent_keepalive.filter(|value| *value != 0) {
                Some(low) => Some(
                    TimerRange::new(low, persistent_keepalive_max.unwrap_or(low))
                        .ok_or_else(|| anyhow::anyhow!("invalid persistent keepalive range"))?,
                ),
                None => None,
            };
            peer.set_awg_timer_config(timers).map_err(anyhow::Error::msg)?;
            peer.set_awg_disable_cookies(amnezia_cfg.v3.disable_cookies).map_err(anyhow::Error::msg)?;
        }
        if let Some(codec) = &amnezia {
            peer.set_transport_padding(codec.clone()).map_err(anyhow::Error::msg)?;
        }
        let mut carrier_cookie_id = [0; 16];
        fill_random(&mut carrier_cookie_id);
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
            carrier_cookie_id,
        })
    }

    /// Send a complete initiation prelude under the same lock as its packet.
    /// Retries, rekeys, and the first initiation all pass through this path.
    ///
    /// # Cancel safety:
    /// NOT cancel-safe: cancellation can stop a prelude after junk or leave a WS frame buffered.
    /// The carrier finishes buffered frames on the next send; shutdown drops the carrier.
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
        Ok(())
    }

    /// Start a WireGuard handshake explicitly after the receive task is live.
    ///
    /// The runtime cannot use the local SOCKS bind as its readiness boundary:
    /// a dead or wire-incompatible peer would otherwise look connected. The
    /// authenticated response observed by [`Self::consume_task`] is the remote
    /// readiness proof.
    ///
    /// # Cancel safety:
    /// NOT cancel-safe: the backend commits the initiation before carrier I/O.
    /// Cancellation belongs to startup failure or tunnel shutdown.
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

    /// Encrypt and send one IP packet with backend transport padding.
    /// `send_buf` is a reused [`MAX_PACKET`] buffer for the ciphertext.
    ///
    /// # Cancel safety:
    /// NOT cancel-safe: encryption advances the nonce and timers before carrier I/O.
    /// The owning producer is cancelled only at shutdown; the peer lock is released before I/O.
    async fn send_ip_packet(&self, packet: &[u8], send_buf: &mut [u8]) {
        let result = { self.peer.lock().await.encapsulate(packet, send_buf) };
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

    /// Send a backend result after releasing the peer lock.
    ///
    /// # Cancel safety:
    /// NOT cancel-safe: cancellation can interrupt an initiation prelude or carrier write.
    /// The caller must stop the tunnel when cancelled.
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

    /// Send the packets boringtun queued while no session existed. Its docs ask
    /// for an empty-datagram `decapsulate` after each `WriteToNetwork` until `Done`.
    ///
    /// # Cancel safety:
    /// NOT cancel-safe: a packet is removed from the backend queue before carrier I/O.
    /// Cancellation is limited to shutdown; packets still in the queue are then discarded.
    async fn send_queued_packets(&self, send_buf: &mut [u8]) {
        // The queue holds at most 256 packets; one that fails to encrypt is requeued and ends the loop with Done.
        loop {
            let result = { self.peer.lock().await.decapsulate(None, &[], send_buf) };
            let TunnResult::WriteToNetwork(packet) = result else { return };
            if let Err(error) = self.send_outbound_packet(packet).await {
                tracing::warn!("WARP tunnel send failed: {error}");
            }
        }
    }

    /// Forward outbound traffic until shutdown.
    ///
    /// # Cancel safety:
    /// NOT cancel-safe: a received bus packet can be encrypted but not sent.
    /// The runtime aborts and joins this producer only when the tunnel shuts down.
    pub(crate) async fn produce_task(&self, bus: Bus) {
        let mut endpoint = bus.new_endpoint();
        let mut send_buf = vec![0u8; MAX_PACKET];
        loop {
            match endpoint.recv().await {
                Event::Shutdown => break,
                Event::OutboundInternetPacket(packet) => self.send_ip_packet(&packet, &mut send_buf).await,
                _ => {}
            }
        }
    }

    /// Drive the backend's selected WireGuard or AWG policy until the task is aborted.
    ///
    /// # Cancel safety:
    /// NOT cancel-safe: a timer transition can commit before its packet is sent.
    /// This task is aborted at tunnel shutdown; peer locks are released before carrier I/O.
    pub(crate) async fn routine_task(&self) {
        let mut send_buf = vec![0u8; MAX_PACKET];
        let mut session_expired = false;
        let mut tick = tokio::time::interval(TIMER_TICK);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tick.tick().await;
            let result = { self.peer.lock().await.update_timers(&mut send_buf) };
            // boringtun repeats ConnectionExpired on every tick until new traffic starts a handshake.
            let expired = matches!(result, TunnResult::Err(WireGuardError::ConnectionExpired));
            if expired && !session_expired {
                tracing::warn!("WARP tunnel session expired; the next packet starts a new handshake");
            } else if !expired {
                self.send_tunn_result(result).await;
            }
            session_expired = expired;
        }
    }

    /// Feed carrier datagrams to the virtual interfaces until the carrier
    /// fails for good, and return that terminal error. Transient UDP errors
    /// are retried.
    ///
    /// # Cancel safety:
    /// NOT cancel-safe: decoding can commit replay, handshake and timer state before
    /// an outbound response finishes. The runtime aborts and joins this task at shutdown.
    pub(crate) async fn consume_task(&self, bus: Bus) -> io::Error {
        let endpoint = bus.new_endpoint();
        let mut recv_buf = vec![0u8; MAX_PACKET];
        let mut send_buf = vec![0u8; MAX_PACKET];
        loop {
            let received = match self.carrier.recv(&mut recv_buf).await {
                Ok(received) => received,
                Err(error) if self.carrier.is_terminal_recv_error(&error) => return error,
                Err(error) => {
                    tracing::warn!("WARP tunnel recv failed: {error}");
                    tokio::time::sleep(Duration::from_millis(10)).await;
                    continue;
                }
            };
            let size = received.len;
            let source = match received.source {
                Some(address) if address == self.endpoint => CookieSource::Udp(address),
                Some(_) => continue,
                None => CookieSource::Connection(self.carrier_cookie_id),
            };
            // When AmneziaWG obfuscation is active, decode the incoming packet
            // before passing it to boringtun. Packets whose header does not
            // match any of h1-h4 (e.g. junk injected by the remote peer during
            // its own handshake setup) are silently discarded.
            //
            // `decoded_buf` holds the reconstructed WG packet (type byte +
            // payload) when amnezia is active. When inactive, `data` points
            // directly into `recv_buf` and `decoded_buf` is unused.
            let mut decoded_buf: Vec<u8> = if let Some(codec) = &self.amnezia {
                match codec.decode(&recv_buf[..size]) {
                    Some(packet) => packet,
                    None => continue,
                }
            } else {
                Vec::new()
            };
            let data: &mut [u8] = if self.amnezia.is_some() {
                &mut decoded_buf
            } else {
                if size == 0 {
                    continue;
                }
                &mut recv_buf[..size]
            };
            // A WARP edge may echo the client id in the reserved bytes; boringtun needs them zero.
            apply_reserved_bytes(data, [0; 3]);
            let data: &[u8] = data;
            let (result, outcome) = { self.peer.lock().await.decapsulate_from(Some(source), data, &mut send_buf) };
            self.handshake_readiness.observe(outcome);
            if matches!(outcome, ReceiveOutcome::TransportEmpty | ReceiveOutcome::TransportIp) {
                // The UDP window learns only from datagrams that passed AEAD and the replay check.
                if let Some(codec) = &self.amnezia {
                    codec.observe_authenticated_transport(size);
                }
            }
            match result {
                TunnResult::WriteToNetwork(packet) => {
                    if let Err(error) = self.send_outbound_packet(packet).await {
                        tracing::warn!("WARP tunnel send failed: {error}");
                    }
                    self.send_queued_packets(&mut send_buf).await;
                }
                TunnResult::WriteToTunnelV4(packet, _) | TunnResult::WriteToTunnelV6(packet, _) => {
                    if let Some(protocol) = route_protocol(packet, self.source_peer_ip, self.source_peer_ipv6) {
                        endpoint.send(Event::InboundInternetPacket(protocol, Bytes::copy_from_slice(packet)));
                    }
                }
                TunnResult::Done => {
                    if outcome == ReceiveOutcome::TransportEmpty {
                        tracing::debug!("WireGuard authenticated empty transport received");
                    }
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
            persistent_keepalive_max: None,
            endpoint: dummy_endpoint(),
            reserved: [0u8; 3],
            source_peer_ip: IpAddr::V4(Ipv4Addr::new(10, 8, 0, 2)),
            source_peer_ipv6: None,
            mtu: 1420,
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

        let result = build_awg_codec(&config, &[""; 5], None);

        assert!(
            matches!(result, Err(AwgParamsError::HeaderCollision { .. })),
            "invalid active AWG config must not silently fall back to plain WireGuard"
        );
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
                    mtu: 1420,
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

    const CLIENT_SECRET: [u8; 32] = [21; 32];
    const SERVER_SECRET: [u8; 32] = [42; 32];

    /// A plain WireGuard peer on loopback and a client tunnel that dials it.
    async fn tunnel_and_peer() -> (std::sync::Arc<WireGuardTunnel>, tokio::net::UdpSocket, Box<Tunn>) {
        tunnel_and_peer_with_config(WarpAmneziaConfig::default()).await
    }

    /// # Cancel safety:
    /// cancel-safe: test setup drops all local resources if cancelled.
    async fn tunnel_and_peer_with_config(
        amnezia: WarpAmneziaConfig,
    ) -> (std::sync::Arc<WireGuardTunnel>, tokio::net::UdpSocket, Box<Tunn>) {
        use base64::Engine as _;
        use boringtun::x25519::{PublicKey, StaticSecret};
        let encode = |bytes: [u8; 32]| base64::engine::general_purpose::STANDARD.encode(bytes);
        let server_secret = StaticSecret::from(SERVER_SECRET);
        let server_public = PublicKey::from(&server_secret).to_bytes();
        let client_public = PublicKey::from(&StaticSecret::from(CLIENT_SECRET));
        let peer_socket = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let mut peer = Box::new(Tunn::new(server_secret, client_public, None, None, 1, None));
        if amnezia.enabled {
            peer.set_awg_timer_config(AwgTimerConfig::default()).unwrap();
        }
        let tunnel = WireGuardTunnel::new(
            WireGuardTunnelParams {
                private_key: &encode(CLIENT_SECRET),
                peer_public_key: &encode(server_public),
                preshared_key: None,
                persistent_keepalive: None,
                persistent_keepalive_max: None,
                endpoint: peer_socket.local_addr().unwrap(),
                reserved: [0; 3],
                source_peer_ip: "10.77.0.2".parse().unwrap(),
                source_peer_ipv6: None,
                mtu: 1420,
                amnezia_cfg: &amnezia,
                signature_programs: ["", "", "", "", ""],
                carrier: None,
            },
            &WarpPlatform::default(),
        )
        .await
        .unwrap();
        (std::sync::Arc::new(tunnel), peer_socket, peer)
    }

    fn spawn_consume(tunnel: &std::sync::Arc<WireGuardTunnel>) -> tokio::task::JoinHandle<io::Error> {
        let tunnel = tunnel.clone();
        tokio::spawn(async move { tunnel.consume_task(Bus::new()).await })
    }

    /// Answer the client's initiation; `edit` may rewrite the response before it is sent.
    async fn answer_initiation(socket: &tokio::net::UdpSocket, peer: &mut Tunn, edit: impl FnOnce(&mut [u8])) {
        let mut datagram = vec![0u8; MAX_PACKET];
        let mut out = vec![0u8; MAX_PACKET];
        let (size, client) = socket.recv_from(&mut datagram).await.unwrap();
        let TunnResult::WriteToNetwork(response) = peer.decapsulate(Some(client.ip()), &datagram[..size], &mut out)
        else {
            panic!("the peer must answer the initiation");
        };
        edit(response);
        socket.send_to(response, client).await.unwrap();
    }

    /// # Cancel safety:
    /// NOT cancel-safe: this test drives a live tunnel and must abort its receive task on exit.
    #[tokio::test]
    async fn configured_padding_reaches_automatic_empty_and_data_over_the_carrier() {
        let config = WarpAmneziaConfig {
            enabled: true,
            v3: crate::config::AwgV3Config { content_padding_addition: "300".into(), ..Default::default() },
            ..Default::default()
        };
        let (tunnel, socket, mut peer) = tunnel_and_peer_with_config(config).await;
        let consume = spawn_consume(&tunnel);
        let result = tokio::time::timeout(Duration::from_secs(2), async {
            tunnel.initiate_handshake().await.unwrap();
            answer_initiation(&socket, &mut peer, |_| {}).await;
            let mut datagram = [0; 2048];
            let mut plaintext = [0; 2048];
            let (size, _) = socket.recv_from(&mut datagram).await.unwrap();
            assert_eq!(size, 332, "automatic empty transport: 300 padding + 32 framing");
            assert!(matches!(peer.decapsulate(None, &datagram[..size], &mut plaintext), TunnResult::Done));
            let ip = [0x45, 0, 0, 20, 0, 0, 0, 0, 64, 17, 0, 0, 10, 77, 0, 2, 1, 1, 1, 1];
            tunnel.send_ip_packet(&ip, &mut datagram).await;
            let (size, _) = socket.recv_from(&mut datagram).await.unwrap();
            assert_eq!(size, 352, "20 IP + 300 padding + 32 framing");
            let TunnResult::WriteToTunnelV4(received, _) = peer.decapsulate(None, &datagram[..size], &mut plaintext)
            else {
                panic!("IP delivery")
            };
            assert_eq!(received, ip);
        })
        .await;
        consume.abort();
        result.expect("padded carrier round trip");
    }

    #[tokio::test]
    async fn handshake_response_that_echoes_the_reserved_bytes_is_accepted() {
        let (tunnel, socket, mut peer) = tunnel_and_peer().await;
        let consume = spawn_consume(&tunnel);
        tunnel.initiate_handshake().await.unwrap();
        // A WARP edge may echo the client id in bytes 1..4, as Xray and sing-box clients allow.
        answer_initiation(&socket, &mut peer, |response| response[1..4].copy_from_slice(&[0x11, 0x22, 0x33])).await;

        let established = tokio::time::timeout(Duration::from_secs(2), async {
            while !tunnel.is_handshake_established() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await;
        consume.abort();
        established.expect("a response with non-zero reserved bytes must complete the handshake");
    }

    #[tokio::test]
    async fn packet_sent_before_the_session_reaches_the_peer_after_the_handshake() {
        let (tunnel, socket, mut peer) = tunnel_and_peer().await;
        let consume = spawn_consume(&tunnel);
        // A minimal IPv4/UDP header from the interface address; boringtun queues it and starts a handshake.
        let ip_packet = [0x45, 0, 0, 20, 0, 0, 0, 0, 64, 17, 0, 0, 10, 77, 0, 2, 1, 1, 1, 1];
        tunnel.send_ip_packet(&ip_packet, &mut vec![0u8; MAX_PACKET]).await;
        answer_initiation(&socket, &mut peer, |_| {}).await;

        let delivered = tokio::time::timeout(Duration::from_secs(2), async {
            let mut datagram = vec![0u8; MAX_PACKET];
            let mut out = vec![0u8; MAX_PACKET];
            loop {
                let (size, client) = socket.recv_from(&mut datagram).await.unwrap();
                if let TunnResult::WriteToTunnelV4(packet, _) =
                    peer.decapsulate(Some(client.ip()), &datagram[..size], &mut out)
                {
                    return packet.to_vec();
                }
            }
        })
        .await;
        consume.abort();
        assert_eq!(delivered.expect("the queued packet must follow the handshake"), ip_packet);
    }

    /// # Cancel safety:
    /// NOT cancel-safe: the test owns a receive task and aborts it after the bounded exchange.
    #[tokio::test]
    async fn only_valid_authenticated_transport_changes_the_wire_padding_window() {
        for (empty, corrupt, expected_length) in [(false, false, 500), (true, true, 500), (true, false, 1216)] {
            let config = WarpAmneziaConfig {
                enabled: true,
                v3: crate::config::AwgV3Config { content_padding_addition: "1000".into(), ..Default::default() },
                ..Default::default()
            };
            let (tunnel, socket, mut peer) = tunnel_and_peer_with_config(config).await;
            let bus = Bus::new();
            let mut events = bus.new_endpoint();
            let consumer = {
                let tunnel = tunnel.clone();
                tokio::spawn(async move { tunnel.consume_task(bus).await })
            };
            let result = tokio::time::timeout(Duration::from_secs(2), async {
                tunnel.initiate_handshake().await.unwrap();
                answer_initiation(&socket, &mut peer, |_| {}).await;
                let mut datagram = [0; 2048];
                let mut plaintext = [0; 2048];
                let (size, client) = socket.recv_from(&mut datagram).await.unwrap();
                assert!(matches!(peer.decapsulate(None, &datagram[..size], &mut plaintext), TunnResult::Done));
                let payload = vec![if empty { 0 } else { 1 }; 1200];
                let TunnResult::WriteToNetwork(packet) = peer.encapsulate(&payload, &mut datagram) else {
                    panic!("candidate")
                };
                if corrupt {
                    *packet.last_mut().unwrap() ^= 1;
                }
                socket.send_to(packet, client).await.unwrap();
                // A following authenticated IP packet marks receive-loop progress without growing the window.
                let marker = [0x45, 0, 0, 20, 0, 0, 0, 0, 64, 17, 0, 0, 1, 1, 1, 1, 10, 77, 0, 2];
                let TunnResult::WriteToNetwork(packet) = peer.encapsulate(&marker, &mut datagram) else {
                    panic!("marker")
                };
                socket.send_to(packet, client).await.unwrap();
                let Event::InboundInternetPacket(_, received) = events.recv().await else { panic!("IP event") };
                assert_eq!(received.as_ref(), marker);
                let mut ip = vec![0; 400];
                ip[..20].copy_from_slice(&marker);
                ip[2..4].copy_from_slice(&400u16.to_be_bytes());
                tunnel.send_ip_packet(&ip, &mut datagram).await;
                let (size, _) = socket.recv_from(&mut datagram).await.unwrap();
                assert_eq!(size, expected_length, "empty={empty}, corrupt={corrupt}");
            })
            .await;
            consumer.abort();
            result.expect("typed receive outcome controls the real wire length");
        }
    }

    /// # Cancel safety:
    /// cancel-safe: the test owns its peers; no task or carrier operation is started.
    #[tokio::test]
    async fn fresh_responder_waits_for_valid_transport_before_publishing_readiness() {
        let (tunnel, _, mut initiator) =
            tunnel_and_peer_with_config(WarpAmneziaConfig { enabled: true, ..Default::default() }).await;
        let mut first = [0; 512];
        let mut second = [0; 512];
        let TunnResult::WriteToNetwork(init) = initiator.format_handshake_initiation(&mut first, false) else {
            panic!("init")
        };
        let mut responder = tunnel.peer.lock().await;
        let (TunnResult::WriteToNetwork(response), outcome) =
            responder.decapsulate_with_outcome(None, init, &mut second)
        else {
            panic!("response")
        };
        assert_eq!(outcome, ReceiveOutcome::HandshakeInitiation);
        tunnel.handshake_readiness.observe(outcome);
        assert!(!tunnel.is_handshake_established());
        let TunnResult::WriteToNetwork(confirm) = initiator.decapsulate(None, response, &mut first) else {
            panic!("confirm")
        };
        let confirm = confirm.to_vec();
        let TunnResult::WriteToNetwork(malformed) = initiator.encapsulate(&[1; 120], &mut first) else {
            panic!("malformed")
        };
        let (result, outcome) = responder.decapsulate_with_outcome(None, malformed, &mut second);
        assert!(matches!(result, TunnResult::Err(WireGuardError::InvalidPacket)));
        assert_eq!(outcome, ReceiveOutcome::TransportMalformed);
        tunnel.handshake_readiness.observe(outcome);
        assert!(!tunnel.is_handshake_established());
        assert!(responder.time_since_last_handshake().is_none());
        let (result, outcome) = responder.decapsulate_with_outcome(None, &confirm, &mut second);
        assert!(matches!(result, TunnResult::Done));
        assert_eq!(outcome, ReceiveOutcome::TransportEmpty);
        tunnel.handshake_readiness.observe(outcome);
        assert!(tunnel.is_handshake_established());
        assert!(responder.time_since_last_handshake().is_some());
    }

    /// # Cancel safety:
    /// NOT cancel-safe: the test owns a receive task and aborts it after the bounded exchange.
    #[tokio::test]
    async fn authenticated_response_from_foreign_udp_port_cannot_establish_the_tunnel() {
        let (tunnel, socket, mut peer) =
            tunnel_and_peer_with_config(WarpAmneziaConfig { enabled: true, ..Default::default() }).await;
        let consumer = spawn_consume(&tunnel);
        let result = tokio::time::timeout(Duration::from_secs(2), async {
            tunnel.initiate_handshake().await.unwrap();
            let mut datagram = [0; 2048];
            let mut output = [0; 2048];
            let (size, client) = socket.recv_from(&mut datagram).await.unwrap();
            let TunnResult::WriteToNetwork(response) = peer.decapsulate(None, &datagram[..size], &mut output) else {
                panic!("response")
            };
            let foreign = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
            foreign.send_to(response, client).await.unwrap();
            assert!(
                tokio::time::timeout(Duration::from_millis(100), socket.recv(&mut datagram)).await.is_err(),
                "a foreign response must not produce confirmation"
            );
            assert!(!tunnel.is_handshake_established());
            socket.send_to(response, client).await.unwrap();
            let (size, _) = socket.recv_from(&mut datagram).await.unwrap();
            assert_eq!(&datagram[..4], &[4, 0, 0, 0]);
            assert!(size >= 32);
            assert!(tunnel.is_handshake_established());
        })
        .await;
        consumer.abort();
        let _ = consumer.await;
        result.expect("source-bound receive");
    }

    #[test]
    fn handshake_readiness_requires_peer_proof_from_backend() {
        for accepted in [ReceiveOutcome::HandshakeResponse, ReceiveOutcome::TransportEmpty, ReceiveOutcome::TransportIp]
        {
            let readiness = HandshakeReadiness::default();
            for rejected in [
                ReceiveOutcome::Rejected,
                ReceiveOutcome::CookieChallenge,
                ReceiveOutcome::CookieAccepted,
                ReceiveOutcome::HandshakeInitiation,
                ReceiveOutcome::TransportMalformed,
            ] {
                readiness.observe(rejected);
                assert!(!readiness.is_established());
            }
            readiness.observe(accepted);
            assert!(readiness.is_established());
        }
    }
}
