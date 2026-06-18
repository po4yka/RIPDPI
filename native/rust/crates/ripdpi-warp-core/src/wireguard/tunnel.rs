use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

use anyhow::Context;
use boringtun::noise::{Tunn, TunnResult};
use bytes::Bytes;
use tokio::net::UdpSocket;

use super::keys::{apply_reserved_bytes, decode_key};
use super::routing::route_protocol;
use super::socket::bind_tunnel_socket;
use crate::amneziawg::{AwgParams, AwgWireCodec, rand_u32};
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
/// here; empty strings mean unset. An invalid config (e.g. inverted junk range,
/// colliding headers, malformed `I*` hex) is logged and treated as disabled
/// rather than failing tunnel construction -- a malformed obfuscation knob must
/// not take the whole runtime down.
pub(crate) fn build_awg_codec(cfg: &WarpAmneziaConfig, special_junk_hex: &[&str]) -> Option<AwgWireCodec> {
    if !cfg.enabled {
        return None;
    }
    match AwgParams::from_config(cfg, special_junk_hex) {
        Ok(params) => Some(AwgWireCodec::new(params)),
        Err(error) => {
            tracing::warn!("invalid AmneziaWG config, obfuscation disabled: {error}");
            None
        }
    }
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
    /// AmneziaWG `Jc/Jmin/Jmax/H1..H4/S1..S4` obfuscation knobs.
    pub amnezia_cfg: &'a WarpAmneziaConfig,
    /// AmneziaWG 2.0 `I1..I5` special-junk frames (hex). Empty strings = unset.
    pub special_junk_hex: [&'a str; 5],
}

pub(crate) struct WireGuardTunnel {
    peer: tokio::sync::Mutex<Box<Tunn>>,
    udp: UdpSocket,
    endpoint: SocketAddr,
    source_peer_ip: IpAddr,
    reserved: [u8; 3],
    amnezia: Option<AwgWireCodec>,
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
            amnezia_cfg,
            special_junk_hex,
        } = params;
        let private_key = decode_key(private_key).context("invalid WireGuard private key")?;
        let peer_public_key = decode_key(peer_public_key).context("invalid WireGuard peer public key")?;
        let preshared_key = match preshared_key.filter(|value| !value.is_empty()) {
            Some(value) => Some(decode_key(value).context("invalid WireGuard preshared key")?),
            None => None,
        };
        let peer = Box::new(Tunn::new(
            boringtun::x25519::StaticSecret::from(private_key),
            boringtun::x25519::PublicKey::from(peer_public_key),
            preshared_key,
            persistent_keepalive,
            0,
            None,
        ));
        let udp = bind_tunnel_socket(endpoint, platform)?;
        let amnezia = build_awg_codec(amnezia_cfg, &special_junk_hex);
        Ok(Self { peer: tokio::sync::Mutex::new(peer), udp, endpoint, source_peer_ip, reserved, amnezia })
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
            // `encode_with_reserved` overlays the reserved bytes during its
            // single output copy, dropping the redundant per-packet `to_vec`.
            Some(codec) => codec.encode_with_reserved(packet, self.reserved),
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
            // before passing it to boringtun. Packets whose header does not
            // match any of h1-h4 (e.g. junk injected by the remote peer during
            // its own handshake setup) are silently discarded.
            //
            // `decoded_buf` holds the reconstructed WG packet (type byte +
            // payload) when amnezia is active. When inactive, `data` points
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
                TunnResult::Err(error) => {
                    tracing::warn!("WARP tunnel decapsulation failed: {error:?}");
                }
            }
        }
    }
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
            amnezia_cfg: &amnezia,
            special_junk_hex: ["", "", "", "", ""],
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
}
