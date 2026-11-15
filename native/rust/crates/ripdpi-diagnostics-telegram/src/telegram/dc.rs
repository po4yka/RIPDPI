use std::collections::HashSet;
use std::net::{IpAddr, SocketAddr};
use std::time::Instant;

use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;
use ripdpi_ws_transport_port::{TelegramDcClass, exact_dc_from_ip};

use crate::transport::{TargetAddress, TransportConfig, connect_transport_observed_with_abort};
use crate::types::TelegramTarget;

use super::ws_tunnel::telegram_transport_path;

#[derive(Default)]
pub(crate) struct TelegramDcResult {
    pub(crate) reachable: usize,
    pub(crate) total: usize,
    pub(crate) planned: usize,
    pub(crate) abort_reason: Option<&'static str>,
    pub(crate) results: Vec<String>,
}

pub(crate) fn telegram_dc_probe_with_abort(
    target: &TelegramTarget,
    transport: &TransportConfig,
    should_abort: &dyn Fn() -> Option<&'static str>,
) -> TelegramDcResult {
    let mut result = TelegramDcResult::default();
    let mut seen = HashSet::new();
    let path = telegram_transport_path(transport);
    let abort = || should_abort().or_else(deadline_abort_reason);

    for dc in &target.dc_endpoints {
        let label_valid = !dc.label.trim().is_empty()
            && dc.label.len() <= 64
            && !dc.label.contains(|ch: char| ch.is_control() || matches!(ch, ':' | '|'));
        let label = if label_valid { &dc.label } else { "DC" };
        let address = dc.ip.parse::<IpAddr>().ok().filter(|_| dc.port != 0).map(|ip| SocketAddr::new(ip, dc.port));
        let Some(address) = address.filter(|_| label_valid) else {
            result.planned += 1;
            result.results.push(format!("{label}:invalid_target::path={path}"));
            continue;
        };
        if !seen.insert(address) {
            continue;
        }
        result.planned += 1;
        let family = if address.is_ipv4() { "ipv4" } else { "ipv6" };
        // The exact bootstrap table annotates the address. Every valid configured pair is probed.
        let bootstrap = exact_dc_from_ip(address.ip()).map_or("", |dc| match dc.class() {
            TelegramDcClass::Production => ";bootstrap=production",
            TelegramDcClass::Test => ";bootstrap=test",
            TelegramDcClass::MediaOrCdn => ";bootstrap=media_or_cdn",
        });
        let endpoint = format!("address={address};family={family};path={path}{bootstrap}");
        if let Some(reason) = abort() {
            result.abort_reason = Some(reason);
            result.results.push(format!("{label}:not_run::{endpoint}"));
            continue;
        }

        let start = Instant::now();
        result.total += 1;
        let connection = connect_transport_observed_with_abort(
            &[TargetAddress::Ip(address.ip())],
            address.port(),
            transport,
            &abort,
        );
        if let Some(reason) = abort() {
            result.abort_reason = Some(reason);
            result.results.push(format!("{label}:{reason}::{endpoint}"));
            continue;
        }
        match connection {
            Ok(connection) => {
                result.reachable += 1;
                let elapsed = start.elapsed().as_millis();
                let route = connection
                    .route_report
                    .map_or_else(String::new, |report| format!(";route={}", report.selected_bucket_kind));
                result.results.push(format!("{label}:ok:{elapsed}ms:{endpoint}{route}"));
            }
            Err(error) => {
                result.results.push(format!("{label}:fail::{};stage={}", endpoint, error.stage.as_str()));
            }
        }
    }
    result
}

pub(crate) fn deadline_abort_reason() -> Option<&'static str> {
    active_scan_io_deadline().is_some_and(|deadline| Instant::now() >= deadline).then_some("deadline_exceeded")
}

#[cfg(test)]
mod tests;
