mod connect;
pub mod dc;
mod protect;
mod relay;

use std::io::{self, Read};
use std::net::{IpAddr, SocketAddr, TcpStream};

pub use dc::{dc_from_ip, extract_dc_from_init, is_telegram_ip};

/// Configuration for a WebSocket tunnel connection.
pub struct WsTunnelConfig {
    /// Unix socket path for Android VPN socket protection. `None` when not
    /// running in VPN mode.
    pub protect_path: Option<String>,
}

/// Result of classifying a target IP for WS tunnel eligibility.
pub enum WsTunnelDecision {
    /// Target is a Telegram DC; use WS tunnel with this DC number (1-5).
    Tunnel(u8),
    /// Target is not a Telegram IP; use the normal transport path.
    Passthrough,
}

/// Error from a WS tunnel attempt that preserves the init packet for fallback.
pub struct WsTunnelError {
    /// The IO error that caused the tunnel to fail.
    pub error: io::Error,
    /// The 64-byte init packet already read from the client, if available.
    /// Can be passed as `seed_request` to the desync fallback path.
    pub init_packet: Option<[u8; 64]>,
}

/// Classify whether a target IP should be tunneled through WebSocket.
///
/// Returns `Tunnel(dc)` for known Telegram DC IPs, `Passthrough` otherwise.
pub fn classify_target(ip: IpAddr) -> WsTunnelDecision {
    match ip {
        IpAddr::V4(v4) => match dc::dc_from_ip(v4) {
            Some(dc_num) => WsTunnelDecision::Tunnel(dc_num),
            None => WsTunnelDecision::Passthrough,
        },
        IpAddr::V6(_) => WsTunnelDecision::Passthrough,
    }
}

/// Execute a WebSocket tunnel relay for a Telegram connection.
///
/// Reads the first 64 bytes (MTProto obfuscated2 init packet) from `client`,
/// extracts the DC number (falling back to `target` IP-based detection),
/// opens a WSS connection to `wss://kws{dc}.web.telegram.org/apiws`, sends
/// the init packet as the first binary frame, and relays data bidirectionally
/// until one side closes.
///
/// On failure, returns a `WsTunnelError` that includes the init packet (if it
/// was successfully read) so the caller can fall back to the desync path.
pub fn relay_ws_tunnel(
    mut client: TcpStream,
    fallback_dc: u8,
    target: SocketAddr,
    config: &WsTunnelConfig,
) -> Result<(), WsTunnelError> {
    // Read the 64-byte obfuscated2 init packet
    let mut init = [0u8; 64];
    if let Err(error) = client.read_exact(&mut init) {
        return Err(WsTunnelError { error, init_packet: None });
    }

    // Try to extract DC from init packet, fall back to IP-based or provided DC
    let dc = dc::extract_dc_from_init(&init).unwrap_or_else(|| {
        if let IpAddr::V4(v4) = target.ip() {
            dc::dc_from_ip(v4).unwrap_or(fallback_dc)
        } else {
            fallback_dc
        }
    });

    let ws = connect::open_ws_tunnel(dc, config.protect_path.as_deref())
        .map_err(|error| WsTunnelError { error, init_packet: Some(init) })?;

    relay::ws_relay(client, ws, &init).map_err(|error| WsTunnelError { error, init_packet: Some(init) })
}

/// Probe whether the WebSocket tunnel endpoint for a given DC is reachable.
///
/// Performs a TLS + WebSocket handshake to `wss://kws{dc}.web.telegram.org/apiws`
/// without sending any MTProto data. Returns `Ok(())` if the endpoint accepts
/// the WSS connection, or an error describing why it failed.
///
/// Intended for diagnostics/monitoring, not for relaying traffic.
pub fn probe_ws_tunnel(dc: u8) -> io::Result<()> {
    let _ws = connect::open_ws_tunnel(dc, None)?;
    Ok(())
}
