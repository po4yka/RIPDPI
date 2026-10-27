use std::io;

use ripdpi_diagnostics_transport::ws_tls::{WsOverTlsConnector, WsOverTlsTarget};

use crate::tls::TlsKeyLogCallback;
use crate::transport::TransportConfig;

const TELEGRAM_WS_HOST: &str = "kws2.web.telegram.org";
const TELEGRAM_WS_PATH: &str = "/apiws";

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

/// Probe the Telegram WS endpoint over the scan's transport. The connector resolves the host
/// with the bounded resolver under the scan deadline (direct) or lets the SOCKS5 proxy resolve it.
pub(crate) fn telegram_ws_tunnel_probe_with_abort_callback(
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    should_abort: &dyn Fn() -> Option<&'static str>,
) -> TelegramWsProbeResult {
    telegram_ws_tunnel_probe_with_target(
        transport,
        |target| WsOverTlsConnector.probe_with_key_log_and_abort(&target, key_log.cloned(), should_abort),
        should_abort,
    )
}

fn telegram_ws_target(transport: &TransportConfig) -> WsOverTlsTarget {
    WsOverTlsTarget::new(TELEGRAM_WS_HOST, TELEGRAM_WS_PATH).with_transport(transport.clone())
}

#[cfg(test)]
pub(crate) fn telegram_ws_tunnel_probe_with<ProbeWs>(
    transport: &TransportConfig,
    probe_ws: ProbeWs,
) -> TelegramWsProbeResult
where
    ProbeWs: FnOnce(WsOverTlsTarget) -> io::Result<()>,
{
    telegram_ws_tunnel_probe_with_target(transport, probe_ws, &|| None)
}

#[cfg(test)]
pub(crate) fn telegram_ws_tunnel_probe_with_abort<ProbeWs>(
    probe_ws: ProbeWs,
    should_abort: &dyn Fn() -> Option<&'static str>,
) -> TelegramWsProbeResult
where
    ProbeWs: FnOnce(WsOverTlsTarget) -> io::Result<()>,
{
    telegram_ws_tunnel_probe_with_target(&crate::transport::direct_transport(), probe_ws, should_abort)
}

fn telegram_ws_tunnel_probe_with_target<ProbeWs>(
    transport: &TransportConfig,
    probe_ws: ProbeWs,
    should_abort: &dyn Fn() -> Option<&'static str>,
) -> TelegramWsProbeResult
where
    ProbeWs: FnOnce(WsOverTlsTarget) -> io::Result<()>,
{
    if let Some(reason) = should_abort() {
        return TelegramWsProbeResult::aborted(reason);
    }
    let start = std::time::Instant::now();
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let target = telegram_ws_target(transport);
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
