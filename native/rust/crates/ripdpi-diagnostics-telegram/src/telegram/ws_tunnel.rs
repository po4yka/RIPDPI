use std::io;
use std::net::{SocketAddr, ToSocketAddrs};

use ripdpi_diagnostics_transport::ws_tls::{WsOverTlsConnector, WsOverTlsTarget};

const TELEGRAM_WS_HOST: &str = "kws2.web.telegram.org";
const TELEGRAM_WS_PATH: &str = "/apiws";
const TELEGRAM_WS_PORT: u16 = 443;

pub(crate) struct TelegramWsProbeResult {
    pub(crate) status: String,
    pub(crate) rtt_ms: u64,
    pub(crate) error: Option<String>,
}

/// Probe whether the Telegram WebSocket tunnel endpoint is reachable.
///
/// Attempts a TLS + WebSocket handshake to `wss://kws2.web.telegram.org/apiws`
/// (DC2 is the default/most common). Does not send any MTProto data -- only
/// verifies that the WSS endpoint accepts connections.
pub(crate) fn telegram_ws_tunnel_probe() -> TelegramWsProbeResult {
    telegram_ws_tunnel_probe_with(resolve_telegram_ws_addr, |resolved_addr| {
        WsOverTlsConnector.probe(&telegram_ws_target(resolved_addr))
    })
}

fn resolve_telegram_ws_addr() -> io::Result<SocketAddr> {
    (TELEGRAM_WS_HOST, TELEGRAM_WS_PORT)
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "Telegram WS endpoint resolved no addresses"))
}

fn telegram_ws_target(resolved_addr: Option<SocketAddr>) -> WsOverTlsTarget {
    WsOverTlsTarget::new(TELEGRAM_WS_HOST, TELEGRAM_WS_PATH).with_resolved_addr(resolved_addr)
}

pub(crate) fn telegram_ws_tunnel_probe_with<ResolveWsAddr, ProbeWs>(
    resolve_ws_addr: ResolveWsAddr,
    probe_ws: ProbeWs,
) -> TelegramWsProbeResult
where
    ResolveWsAddr: FnOnce() -> io::Result<SocketAddr>,
    ProbeWs: FnOnce(Option<SocketAddr>) -> io::Result<()>,
{
    let start = std::time::Instant::now();
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let resolved_addr = match resolve_ws_addr() {
            Ok(addr) => Some(addr),
            Err(err) => {
                tracing::warn!("Telegram WS tunnel encrypted DNS bootstrap failed: {err}");
                None
            }
        };

        match probe_ws(resolved_addr) {
            Ok(()) => {
                let rtt_ms = start.elapsed().as_millis() as u64;
                TelegramWsProbeResult { status: "ok".to_string(), rtt_ms, error: None }
            }
            Err(e) => {
                let rtt_ms = start.elapsed().as_millis() as u64;
                TelegramWsProbeResult { status: "unreachable".to_string(), rtt_ms, error: Some(e.to_string()) }
            }
        }
    })) {
        Ok(result) => result,
        Err(payload) => {
            let rtt_ms = start.elapsed().as_millis() as u64;
            let message = if let Some(message) = payload.downcast_ref::<&str>() {
                (*message).to_string()
            } else if let Some(message) = payload.downcast_ref::<String>() {
                message.clone()
            } else {
                "unknown panic".to_string()
            };
            tracing::error!("Telegram WS tunnel probe panicked: {message}");
            TelegramWsProbeResult {
                status: "unreachable".to_string(),
                rtt_ms,
                error: Some(format!("panic during Telegram WS tunnel probe: {message}")),
            }
        }
    }
}
