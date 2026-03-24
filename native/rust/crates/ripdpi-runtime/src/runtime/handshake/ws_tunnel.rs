use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream};

use ripdpi_ws_tunnel::WsTunnelConfig;

use crate::ws_bootstrap;

use super::super::state::RuntimeState;

/// Detect Telegram DC number from target IP, independent of WS tunnel config.
pub(super) fn detect_telegram_dc(target: SocketAddr) -> Option<u8> {
    match target.ip() {
        IpAddr::V4(v4) => ripdpi_ws_tunnel::dc_from_ip(v4),
        IpAddr::V6(_) => None,
    }
}

/// Format a virtual hostname for a Telegram DC, used as autolearn key.
pub(super) fn telegram_dc_host(dc: u8) -> String {
    format!("telegram-dc{dc}")
}

/// Classify a target as a Telegram DC, returning the DC number if matched.
fn classify_telegram_target(target: SocketAddr) -> Option<u8> {
    match ripdpi_ws_tunnel::classify_target(target.ip()) {
        ripdpi_ws_tunnel::WsTunnelDecision::Tunnel(dc) => Some(dc),
        ripdpi_ws_tunnel::WsTunnelDecision::Passthrough => None,
    }
}

/// Check if WS tunnel should be tried first (Always mode).
pub(super) fn should_ws_tunnel_first(target: SocketAddr, state: &RuntimeState) -> Option<u8> {
    if state.config.adaptive.ws_tunnel_mode != ripdpi_config::WsTunnelMode::Always {
        return None;
    }
    let dc = classify_telegram_target(target)?;
    log::info!("WS tunnel: routing to DC{dc} via wss://kws{dc}.web.telegram.org/apiws");
    Some(dc)
}

/// Check if WS tunnel should be tried as a last resort (Fallback mode).
pub(super) fn should_ws_tunnel_fallback(target: SocketAddr, state: &RuntimeState) -> Option<u8> {
    if state.config.adaptive.ws_tunnel_mode != ripdpi_config::WsTunnelMode::Fallback {
        return None;
    }
    classify_telegram_target(target)
}

/// Try WS tunnel as a last resort after desync exhaustion.
/// Returns `Some(Ok(()))` on success, `None` if WS also failed or couldn't be attempted.
pub(super) fn try_ws_tunnel_fallback(
    client: &TcpStream,
    target: SocketAddr,
    dc: u8,
    state: &RuntimeState,
) -> Option<io::Result<()>> {
    log::info!("WS tunnel fallback: desync exhausted for DC{dc}, escalating to wss://kws{dc}.web.telegram.org/apiws");
    let cloned = client.try_clone().ok()?;
    match run_ws_tunnel(cloned, dc, target, state) {
        WsTunnelResult::Ok => {
            if let Some(telemetry) = &state.telemetry {
                telemetry.on_ws_tunnel_escalation(target, dc, true);
            }
            Some(Ok(()))
        }
        _ => {
            if let Some(telemetry) = &state.telemetry {
                telemetry.on_ws_tunnel_escalation(target, dc, false);
            }
            None
        }
    }
}

/// Result of a WS tunnel attempt.
pub(super) enum WsTunnelResult {
    /// Tunnel completed successfully.
    Ok,
    /// Tunnel failed; init packet is available for desync fallback.
    Fallback { init_packet: Vec<u8> },
    /// Tunnel failed before reading init; no bytes consumed from client.
    FallbackNoInit,
}

/// Execute the WebSocket tunnel relay for a classified Telegram connection.
/// On failure, returns `Fallback` with the consumed init packet for desync retry.
pub(super) fn run_ws_tunnel(client: TcpStream, dc: u8, target: SocketAddr, state: &RuntimeState) -> WsTunnelResult {
    let resolved_addr = match ws_bootstrap::resolve_ws_tunnel_addr(dc, state.runtime_context.as_ref()) {
        Ok(addr) => Some(addr),
        Err(err) => {
            log::warn!("WS tunnel encrypted DNS bootstrap failed for DC{dc}: {err}");
            None
        }
    };
    let config = WsTunnelConfig { protect_path: state.config.process.protect_path.clone(), resolved_addr };
    match ripdpi_ws_tunnel::relay_ws_tunnel(client, dc, target, &config) {
        Result::Ok(()) => WsTunnelResult::Ok,
        Err(err) => {
            log::warn!("WS tunnel failed for DC{dc}, falling back to desync: {}", err.error);
            match err.init_packet {
                Some(init) => WsTunnelResult::Fallback { init_packet: init.to_vec() },
                None => WsTunnelResult::FallbackNoInit,
            }
        }
    }
}
