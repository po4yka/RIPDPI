use std::collections::BTreeMap;

use crate::types::{Diagnosis, ProbeResult};

use super::diagnosis::failure_detail_value;

pub const MOBILE_ALLOWLIST_DIAGNOSIS_CODE: &str = "mobile_allowlist_mode";
pub const MOBILE_ALLOWLIST_NARROWING: &str = "standard_https_to_permitted_destination";

const FOREIGN_TARGET_CLASSES: [&str; 2] = ["foreign_datacenter", "cdn"];
const UDP_SILENT_OUTCOMES: [&str; 2] = ["quic_timeout", "quic_empty"];
const UDP_REPLY_OUTCOMES: [&str; 8] = [
    "quic_initial_response",
    "quic_response",
    "quic_initial_ack",
    "quic_initial_other",
    "quic_initial_close",
    "quic_retry",
    "quic_version_negotiation",
    "quic_invalid_response",
];

#[derive(Default)]
struct TargetRows<'a> {
    cohort: &'a str,
    target_class: &'a str,
    outcomes: Vec<(&'a str, &'a str, &'a str)>,
}

impl TargetRows<'_> {
    fn all(&self, outcome: &str) -> bool {
        !self.outcomes.is_empty() && self.outcomes.iter().all(|(value, _, _)| *value == outcome)
    }

    fn address_level_failure(&self) -> bool {
        self.all("matrix_target_transport_failed")
            && self.outcomes.iter().all(|(_, stage, tcp_status)| *stage == "tcp" && *tcp_status != "ok")
    }
}

pub fn classify_mobile_allowlist(results: &[ProbeResult]) -> Option<Diagnosis> {
    let selective = results
        .iter()
        .any(|result| result.probe_type == "selective_availability_summary" && result.outcome == "matrix_selective");
    if !selective {
        return None;
    }
    let mut targets = BTreeMap::<&str, TargetRows<'_>>::new();
    for result in results.iter().filter(|result| result.probe_type == "selective_availability") {
        let id = failure_detail_value(result, "targetId").unwrap_or(result.target.as_str());
        let rows = targets.entry(id).or_default();
        rows.cohort = failure_detail_value(result, "cohort").unwrap_or_default();
        rows.target_class = failure_detail_value(result, "targetClass").unwrap_or_default();
        rows.outcomes.push((
            result.outcome.as_str(),
            failure_detail_value(result, "failureStage").unwrap_or_default(),
            failure_detail_value(result, "tcpStatus").unwrap_or_default(),
        ));
    }
    let permitted = targets.values().filter(|rows| rows.cohort == "declared_available").collect::<Vec<_>>();
    let domestic_failed =
        targets.values().filter(|rows| rows.cohort == "domestic" && rows.all("matrix_target_transport_failed")).count();
    let domestic_available =
        targets.values().any(|rows| rows.cohort == "domestic" && rows.all("matrix_target_available"));
    let foreign = targets
        .values()
        .filter(|rows| rows.cohort != "user" && FOREIGN_TARGET_CLASSES.contains(&rows.target_class))
        .collect::<Vec<_>>();
    let udp = results.iter().filter(|result| result.probe_type == "quic_reachability").collect::<Vec<_>>();
    let udp_silent = udp.iter().filter(|result| UDP_SILENT_OUTCOMES.contains(&result.outcome.as_str())).count();
    let udp_reply = udp.iter().filter(|result| UDP_REPLY_OUTCOMES.contains(&result.outcome.as_str())).count();
    let allowlist = !permitted.is_empty()
        && permitted.iter().all(|rows| rows.all("matrix_target_available"))
        && domestic_failed > 0
        && !domestic_available
        && !foreign.is_empty()
        && foreign.iter().all(|rows| rows.address_level_failure())
        && udp_silent > 0
        && udp_reply == 0;
    allowlist.then(|| Diagnosis {
        code: MOBILE_ALLOWLIST_DIAGNOSIS_CODE.to_string(),
        summary: "Only permitted destinations completed HTTPS; foreign-address and UDP targets failed".to_string(),
        severity: "warning".to_string(),
        target: None,
        evidence: vec![
            "verdict=allowlist_mode".to_string(),
            format!("permittedAvailable={}", permitted.len()),
            format!("domesticUnlistedFailed={domestic_failed}"),
            format!("foreignAddressFailed={}", foreign.len()),
            "foreignFailureStage=tcp".to_string(),
            format!("udpSilent={udp_silent}"),
            format!("udpReplied={udp_reply}"),
            format!("narrowing={MOBILE_ALLOWLIST_NARROWING}"),
            "excludedTransports=udp|direct_foreign_address".to_string(),
        ],
        recommendation: Some(
            "Use only transports that complete standard HTTPS over TCP toward a permitted destination".to_string(),
        ),
        control_validated: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::ProbeDetail;

    fn matrix_row(id: &str, cohort: &str, class: &str, outcome: &str, stage: &str) -> ProbeResult {
        let tcp_status = if stage == "tcp" { "failed" } else { "ok" };
        ProbeResult {
            probe_type: "selective_availability".into(),
            target: id.into(),
            outcome: outcome.into(),
            details: [
                ("targetId", id),
                ("cohort", cohort),
                ("targetClass", class),
                ("failureStage", stage),
                ("tcpStatus", tcp_status),
            ]
            .into_iter()
            .map(|(key, value)| ProbeDetail { key: key.into(), value: value.into() })
            .collect(),
        }
    }

    fn row(probe_type: &str, outcome: &str) -> ProbeResult {
        ProbeResult { probe_type: probe_type.into(), target: "t".into(), outcome: outcome.into(), details: vec![] }
    }

    fn scan(permitted: &str, foreign: &str, foreign_stage: &str, summary: &str, udp: &str) -> Vec<ProbeResult> {
        scan_with_domestic(permitted, FAILED, foreign, foreign_stage, summary, udp)
    }

    fn scan_with_domestic(
        permitted: &str,
        domestic: &str,
        foreign: &str,
        foreign_stage: &str,
        summary: &str,
        udp: &str,
    ) -> Vec<ProbeResult> {
        let domestic_stage = if domestic == FAILED { "tcp" } else { "none" };
        let mut rows = Vec::new();
        for _ in 0..2 {
            rows.push(matrix_row("a", "declared_available", "domestic_hosting", permitted, "none"));
            rows.push(matrix_row("b", "declared_available", "domestic_hosting", permitted, "none"));
            rows.push(matrix_row("e", "domestic", "domestic_hosting", domestic, domestic_stage));
            rows.push(matrix_row("c", "global", "cdn", foreign, foreign_stage));
            rows.push(matrix_row("d", "global", "foreign_datacenter", foreign, foreign_stage));
            rows.push(matrix_row("u", "user", "", AVAILABLE, "none"));
        }
        rows.push(row("selective_availability_summary", summary));
        rows.push(row("quic_reachability", udp));
        rows
    }

    const AVAILABLE: &str = "matrix_target_available";
    const FAILED: &str = "matrix_target_transport_failed";

    #[test]
    fn permitted_reachable_with_foreign_and_udp_failing_is_allowlist_mode() {
        let diagnosis =
            classify_mobile_allowlist(&scan(AVAILABLE, FAILED, "tcp", "matrix_selective", "quic_timeout")).unwrap();
        assert_eq!(diagnosis.code, "mobile_allowlist_mode");
        assert!(diagnosis.target.is_none());
        assert_eq!(
            diagnosis.evidence,
            [
                "verdict=allowlist_mode",
                "permittedAvailable=2",
                "domesticUnlistedFailed=1",
                "foreignAddressFailed=2",
                "foreignFailureStage=tcp",
                "udpSilent=1",
                "udpReplied=0",
                "narrowing=standard_https_to_permitted_destination",
                "excludedTransports=udp|direct_foreign_address",
            ]
        );
    }

    #[test]
    fn foreign_range_block_with_reachable_domestic_hosting_is_not_allowlist() {
        let rows = scan_with_domestic(AVAILABLE, AVAILABLE, FAILED, "tcp", "matrix_selective", "quic_timeout");
        assert!(classify_mobile_allowlist(&rows).is_none());
    }

    #[test]
    fn one_reachable_foreign_or_failed_permitted_target_is_not_allowlist() {
        let mut foreign_reached = scan(AVAILABLE, FAILED, "tcp", "matrix_selective", "quic_timeout");
        foreign_reached[3] = matrix_row("c", "global", "cdn", AVAILABLE, "none");
        assert!(classify_mobile_allowlist(&foreign_reached).is_none());
        let mut permitted_failed = scan(AVAILABLE, FAILED, "tcp", "matrix_selective", "quic_timeout");
        permitted_failed[0] = matrix_row("a", "declared_available", "domestic_hosting", FAILED, "tcp");
        assert!(classify_mobile_allowlist(&permitted_failed).is_none());
    }

    #[test]
    fn failure_after_an_open_tcp_connection_is_not_address_level() {
        let mut rows = scan(AVAILABLE, FAILED, "tcp", "matrix_selective", "quic_timeout");
        for row in rows.iter_mut().filter(|row| row.target == "c") {
            row.details.retain(|detail| detail.key != "tcpStatus");
            row.details.push(ProbeDetail { key: "tcpStatus".into(), value: "ok".into() });
        }
        assert!(classify_mobile_allowlist(&rows).is_none());
    }

    #[test]
    fn universal_failure_is_not_allowlist_mode() {
        assert!(
            classify_mobile_allowlist(&scan(FAILED, FAILED, "tcp", "matrix_unavailable", "quic_timeout")).is_none()
        );
    }

    #[test]
    fn tls_stage_failure_reads_as_dpi_not_allowlist() {
        assert!(
            classify_mobile_allowlist(&scan(AVAILABLE, FAILED, "tls", "matrix_selective", "quic_timeout")).is_none()
        );
    }

    #[test]
    fn udp_reply_or_missing_udp_evidence_is_not_allowlist() {
        assert!(
            classify_mobile_allowlist(&scan(AVAILABLE, FAILED, "tcp", "matrix_selective", "quic_initial_response"))
                .is_none()
        );
        assert!(classify_mobile_allowlist(&scan(AVAILABLE, FAILED, "tcp", "matrix_selective", "quic_error")).is_none());
    }

    #[test]
    fn mixed_or_inconclusive_matrix_is_not_allowlist() {
        assert!(classify_mobile_allowlist(&scan(AVAILABLE, FAILED, "tcp", "matrix_mixed", "quic_timeout")).is_none());
    }
}
