use std::net::{IpAddr, Shutdown};
use std::time::Instant;

use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;

use crate::transport::{TargetAddress, TransportConfig, connect_transport_observed};
use crate::types::TelegramTarget;

const FALLBACK_MTPROTO_PORT: u16 = 80;
const PRIMARY_MTPROTO_PORT: u16 = 443;

pub(crate) struct TelegramDcResult {
    pub(crate) reachable: usize,
    pub(crate) total: usize,
    pub(crate) results: Vec<String>,
}

#[cfg(test)]
pub(crate) fn telegram_dc_probe(target: &TelegramTarget, transport: &TransportConfig) -> TelegramDcResult {
    telegram_dc_probe_with_abort(target, transport, &deadline_abort_reason)
}

pub(crate) fn telegram_dc_probe_with_abort(
    target: &TelegramTarget,
    transport: &TransportConfig,
    should_abort: &dyn Fn() -> Option<&'static str>,
) -> TelegramDcResult {
    let mut results = Vec::new();
    let mut reachable = 0usize;
    let mut total = 0usize;

    for dc in &target.dc_endpoints {
        if let Some(reason) = should_abort() {
            results.push(format!("dc_probe_aborted:{reason}"));
            break;
        }
        total += 1;
        let ip: IpAddr = match dc.ip.parse() {
            Ok(ip) => ip,
            Err(_) => {
                results.push(format!("{}:fail:parse_error", dc.label));
                continue;
            }
        };
        let dc_label = match ripdpi_ws_tunnel::classify_target(ip) {
            ripdpi_ws_tunnel::WsTunnelDecision::Tunnel(dc) => format!("dc{}", dc.number()),
            ripdpi_ws_tunnel::WsTunnelDecision::Passthrough => {
                results.push(format!("{}:fail:not_telegram_dc", dc.label));
                continue;
            }
        };
        let mut port_results = Vec::new();
        let mut rtts = Vec::new();
        for port in dc_probe_ports(dc.port) {
            if let Some(reason) = should_abort() {
                port_results.push(format!("{port}:fail:{reason}"));
                break;
            }
            let start = std::time::Instant::now();
            // Route through the protect-aware transport seam so the probe fd is
            // protected before connect when the VPN is up (DIAG-2), matching the
            // sibling download/upload probes.
            match connect_transport_observed(std::slice::from_ref(&TargetAddress::Ip(ip)), port, transport) {
                Ok(result) => {
                    let rtt_ms = start.elapsed().as_millis();
                    result.stream.shutdown(Shutdown::Both).ok();
                    rtts.push(rtt_ms);
                    port_results.push(format!("{port}:ok:{rtt_ms}ms"));
                }
                Err(_) => {
                    port_results.push(format!("{port}:fail"));
                }
            }
        }
        let is_reachable = !rtts.is_empty();
        if is_reachable {
            reachable += 1;
        }
        let median = median_rtt_ms(&mut rtts).map_or_else(|| "none".to_string(), |rtt| rtt.to_string());
        results
            .push(format!("{dc_label}:reachable={is_reachable}:medianRttMs={median}:ports={}", port_results.join(",")));
    }

    TelegramDcResult { reachable, total, results }
}

pub(crate) fn deadline_abort_reason() -> Option<&'static str> {
    active_scan_io_deadline().is_some_and(|deadline| Instant::now() >= deadline).then_some("deadline_exceeded")
}

fn dc_probe_ports(configured_port: u16) -> Vec<u16> {
    let mut ports = Vec::with_capacity(3);
    for port in [configured_port, PRIMARY_MTPROTO_PORT, FALLBACK_MTPROTO_PORT] {
        if !ports.contains(&port) {
            ports.push(port);
        }
    }
    ports
}

fn median_rtt_ms(rtts: &mut [u128]) -> Option<u128> {
    if rtts.is_empty() {
        return None;
    }
    rtts.sort_unstable();
    Some(rtts[rtts.len() / 2])
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::TelegramDcEndpoint;

    #[test]
    fn dc_probe_ports_include_primary_and_fallback_without_duplicates() {
        assert_eq!(dc_probe_ports(PRIMARY_MTPROTO_PORT), vec![443, 80]);
        assert_eq!(dc_probe_ports(FALLBACK_MTPROTO_PORT), vec![80, 443]);
        assert_eq!(dc_probe_ports(5222), vec![5222, 443, 80]);
    }

    #[test]
    fn median_rtt_reports_middle_successful_attempt() {
        let mut rtts = vec![30, 10, 20];
        assert_eq!(median_rtt_ms(&mut rtts), Some(20));
        assert_eq!(median_rtt_ms(&mut []), None);
    }

    #[test]
    fn dc_probe_rejects_catalog_ips_missing_from_ws_tunnel_dc_table() {
        let target = TelegramTarget {
            media_url: "https://telegram.org/".to_string(),
            upload_ip: "149.154.167.99".to_string(),
            upload_port: 443,
            dc_endpoints: vec![TelegramDcEndpoint {
                ip: "203.0.113.10".to_string(),
                label: "dc-test".to_string(),
                port: 443,
            }],
            stall_timeout_ms: 10,
            total_timeout_ms: 10,
            upload_size_bytes: 1,
        };

        let result = telegram_dc_probe(&target, &TransportConfig::Direct { route_experiment: None });
        assert_eq!(result.total, 1);
        assert_eq!(result.reachable, 0);
        assert_eq!(result.results, vec!["dc-test:fail:not_telegram_dc"]);
    }
}
