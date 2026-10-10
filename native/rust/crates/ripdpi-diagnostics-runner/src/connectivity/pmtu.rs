use ripdpi_diagnostics_contracts::{PmtuProbeConfig, ProbeDetail, ProbeResult};
use ripdpi_diagnostics_transport::transport::TransportConfig;
use rustls::client::danger::ServerCertVerifier;
use std::sync::{Arc, atomic::AtomicBool};
/// Collect independent family observations without translating failure into blocking claims.
pub fn run_pmtu_probe(
    config: &PmtuProbeConfig,
    transport: &TransportConfig,
    raw_path: bool,
    cancel: &AtomicBool,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Vec<ProbeResult> {
    [false, true]
        .into_iter()
        .map(|ipv6| {
            let facts = ripdpi_diagnostics_http::pmtu::probe_pmtu(config, ipv6, transport, raw_path, cancel, verifier);
            let outcome = match facts.status.as_str() {
                "OBSERVED" => "pmtu_observed",
                "INCONCLUSIVE" => "pmtu_inconclusive",
                "FAILED" => "pmtu_failed",
                "TIMEOUT" => "pmtu_timeout",
                "CANCELLED" => "pmtu_cancelled",
                "UNSUPPORTED" => "pmtu_unsupported",
                "INVALID" => "pmtu_invalid",
                _ => "pmtu_not_observed",
            };
            ProbeResult {
                probe_type: "pmtu".into(),
                target: format!("{} ({})", config.host, facts.address_family),
                outcome: outcome.into(),
                details: vec![ProbeDetail {
                    key: "pmtuEvidence".into(),
                    value: facts.to_detail_json().unwrap_or_default(),
                }],
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn produces_distinct_cancelled_family_rows_without_io() {
        let rows = run_pmtu_probe(
            &PmtuProbeConfig::default(),
            &TransportConfig::Direct { route_experiment: None },
            true,
            &AtomicBool::new(true),
            None,
        );
        assert_eq!(rows.len(), 2);
        assert_ne!(rows[0].target, rows[1].target);
        for (row, family) in rows.iter().zip(["IPV4", "IPV6"]) {
            assert_eq!(row.outcome, "pmtu_cancelled");
            assert!(row.details[0].value.contains(&format!("\"addressFamily\":\"{family}\"")));
            assert!(row.details[0].value.contains("\"tlsValidated\":false"));
        }
    }
}
