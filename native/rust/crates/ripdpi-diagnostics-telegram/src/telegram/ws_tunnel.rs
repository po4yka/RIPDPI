use std::io;
use std::net::{SocketAddr, ToSocketAddrs};

use ripdpi_diagnostics_transport::ws_tls::{WsOverTlsConnector, WsOverTlsTarget};

use crate::tls::TlsKeyLogCallback;

const TELEGRAM_WS_HOST: &str = "kws2.web.telegram.org";
const TELEGRAM_WS_PATH: &str = "/apiws";
const TELEGRAM_WS_PORT: u16 = 443;

pub(crate) struct TelegramWsProbeResult {
    pub(crate) status: String,
    pub(crate) rtt_ms: u64,
    pub(crate) error: Option<String>,
}

impl TelegramWsProbeResult {
    pub(crate) fn aborted(reason: &str) -> Self {
        Self { status: reason.to_string(), rtt_ms: 0, error: Some(format!("probe_aborted:{reason}")) }
    }
}

pub(crate) fn telegram_ws_tunnel_probe_with_abort_callback(
    key_log: Option<&TlsKeyLogCallback>,
    should_abort: &dyn Fn() -> Option<&'static str>,
) -> TelegramWsProbeResult {
    telegram_ws_tunnel_probe_with_target(
        resolve_telegram_ws_addr,
        |target| WsOverTlsConnector.probe_with_key_log_and_abort(&target, key_log.cloned(), should_abort),
        should_abort,
    )
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

#[cfg(test)]
pub(crate) fn telegram_ws_tunnel_probe_with<ResolveWsAddr, ProbeWs>(
    resolve_ws_addr: ResolveWsAddr,
    probe_ws: ProbeWs,
) -> TelegramWsProbeResult
where
    ResolveWsAddr: FnOnce() -> io::Result<SocketAddr>,
    ProbeWs: FnOnce(Option<SocketAddr>) -> io::Result<()>,
{
    telegram_ws_tunnel_probe_with_target(resolve_ws_addr, |target| probe_ws(target.resolved_addr), &|| None)
}

#[cfg(test)]
pub(crate) fn telegram_ws_tunnel_probe_with_abort<ResolveWsAddr, ProbeWs>(
    resolve_ws_addr: ResolveWsAddr,
    probe_ws: ProbeWs,
    should_abort: &dyn Fn() -> Option<&'static str>,
) -> TelegramWsProbeResult
where
    ResolveWsAddr: FnOnce() -> io::Result<SocketAddr>,
    ProbeWs: FnOnce(WsOverTlsTarget) -> io::Result<()>,
{
    telegram_ws_tunnel_probe_with_target(resolve_ws_addr, probe_ws, should_abort)
}

fn telegram_ws_tunnel_probe_with_target<ResolveWsAddr, ProbeWs>(
    resolve_ws_addr: ResolveWsAddr,
    probe_ws: ProbeWs,
    should_abort: &dyn Fn() -> Option<&'static str>,
) -> TelegramWsProbeResult
where
    ResolveWsAddr: FnOnce() -> io::Result<SocketAddr>,
    ProbeWs: FnOnce(WsOverTlsTarget) -> io::Result<()>,
{
    if let Some(reason) = should_abort() {
        return TelegramWsProbeResult::aborted(reason);
    }
    let start = std::time::Instant::now();
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let resolved_addr = match resolve_ws_addr() {
            Ok(addr) => Some(addr),
            Err(err) => {
                tracing::warn!("Telegram WS tunnel encrypted DNS bootstrap failed: {err}");
                None
            }
        };
        if let Some(reason) = should_abort() {
            return TelegramWsProbeResult::aborted(reason);
        }

        let target = telegram_ws_target(resolved_addr);
        if let Some(reason) = should_abort() {
            return TelegramWsProbeResult::aborted(reason);
        }

        match probe_ws(target) {
            Ok(()) => {
                let rtt_ms = start.elapsed().as_millis() as u64;
                TelegramWsProbeResult { status: "ok".to_string(), rtt_ms, error: None }
            }
            Err(e) => ws_error_result(e, start),
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

fn ws_error_result(error: io::Error, start: std::time::Instant) -> TelegramWsProbeResult {
    let rtt_ms = start.elapsed().as_millis() as u64;
    if let Some(reason) = typed_abort_reason(&error) {
        return TelegramWsProbeResult {
            status: reason.to_string(),
            rtt_ms,
            error: Some(format!("probe_aborted:{reason}")),
        };
    }
    TelegramWsProbeResult { status: "unreachable".to_string(), rtt_ms, error: Some(error.to_string()) }
}

fn typed_abort_reason(error: &io::Error) -> Option<&'static str> {
    let message = error.to_string();
    if error.kind() == io::ErrorKind::Interrupted && message == "probe_aborted:cancelled" {
        Some("cancelled")
    } else if error.kind() == io::ErrorKind::TimedOut
        && matches!(message.as_str(), "scan_deadline_exceeded" | "deadline_exceeded")
    {
        Some("deadline_exceeded")
    } else {
        None
    }
}
