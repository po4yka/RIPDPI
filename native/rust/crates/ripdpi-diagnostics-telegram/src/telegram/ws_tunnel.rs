use std::io;

use ripdpi_diagnostics_transport::ws_tls::{WsOverTlsConnector, WsOverTlsTarget, WsProbeFailure};

use crate::tls::TlsKeyLogCallback;
use crate::transport::TransportConfig;

const TELEGRAM_WS_HOST: &str = "kws2.web.telegram.org";
const TELEGRAM_WS_PATH: &str = "/apiws";

pub(crate) struct TelegramWsProbeResult {
    pub(crate) status: String,
    pub(crate) abort_reason: Option<&'static str>,
    pub(crate) rtt_ms: u64,
    pub(crate) error: Option<String>,
    pub(crate) http_status: Option<u16>,
}

impl TelegramWsProbeResult {
    pub(crate) fn aborted(reason: &'static str, duration_ms: Option<u64>) -> Self {
        Self {
            status: if duration_ms.is_some() { reason } else { "not_run" }.into(),
            abort_reason: Some(reason),
            rtt_ms: duration_ms.unwrap_or_default(),
            error: Some(format!("probe_aborted:{reason}")),
            http_status: None,
        }
    }
}

/// Probe the Telegram WS endpoint over the scan's transport. The connector resolves the host
/// with the bounded resolver under the scan deadline (direct) or lets the SOCKS5 proxy resolve it.
pub(crate) fn telegram_ws_tunnel_probe_with_abort_callback(
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    should_abort: &(dyn Fn() -> Option<&'static str> + Sync),
) -> TelegramWsProbeResult {
    telegram_ws_tunnel_probe_with_target(
        transport,
        |target| WsOverTlsConnector.probe_with_key_log_and_abort(&target, key_log.cloned(), should_abort),
        should_abort,
    )
}

fn telegram_ws_target(transport: &TransportConfig) -> WsOverTlsTarget {
    WsOverTlsTarget::new(TELEGRAM_WS_HOST, TELEGRAM_WS_PATH).with_transport(transport.clone()).with_binary_subprotocol()
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
        return TelegramWsProbeResult::aborted(reason, None);
    }
    let start = std::time::Instant::now();
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let target = telegram_ws_target(transport);
        if let Some(reason) = should_abort() {
            return TelegramWsProbeResult::aborted(reason, None);
        }

        let result = probe_ws(target);
        if let Some(reason) = should_abort() {
            return TelegramWsProbeResult::aborted(reason, Some(start.elapsed().as_millis() as u64));
        }
        match result {
            Ok(()) => {
                let rtt_ms = start.elapsed().as_millis() as u64;
                TelegramWsProbeResult {
                    status: "ok".to_string(),
                    abort_reason: None,
                    rtt_ms,
                    error: None,
                    http_status: None,
                }
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
                abort_reason: None,
                rtt_ms,
                error: Some(format!("panic during Telegram WS tunnel probe: {message}")),
                http_status: None,
            }
        }
    }
}

fn ws_error_result(error: io::Error, start: std::time::Instant) -> TelegramWsProbeResult {
    let rtt_ms = start.elapsed().as_millis() as u64;
    let failure = error.get_ref().and_then(|cause| cause.downcast_ref::<WsProbeFailure>());
    let (status, http_status) = match failure {
        Some(WsProbeFailure::Cancelled) => return TelegramWsProbeResult::aborted("cancelled", Some(rtt_ms)),
        Some(WsProbeFailure::DeadlineExceeded) => {
            return TelegramWsProbeResult::aborted("deadline_exceeded", Some(rtt_ms));
        }
        Some(WsProbeFailure::SubprotocolNotNegotiated) => ("subprotocol_not_negotiated", Some(101)),
        Some(WsProbeFailure::UpgradeRejected(status)) => ("upgrade_rejected", Some(*status)),
        Some(WsProbeFailure::UpgradeFailed) => ("upgrade_failed", None),
        Some(WsProbeFailure::OpenTimedOut) => ("open_timed_out", None),
        None => ("unreachable", None),
    };
    TelegramWsProbeResult {
        status: status.into(),
        abort_reason: None,
        rtt_ms,
        error: Some(error.to_string()),
        http_status,
    }
}
