use std::collections::{BTreeMap, BTreeSet};

use crate::types::{Diagnosis, ProbeResult};
use crate::util::is_suspected_dns_tampering_outcome;

use super::common::{DiagnosisSink, diagnosis_evidence, normalize_host};
use super::failure_detail_value;

pub(crate) fn classify_dns_diagnoses(
    results: &[ProbeResult],
    domain_outcomes: &BTreeMap<String, Vec<&ProbeResult>>,
    hard_failure_codes: &mut BTreeSet<String>,
    sink: &mut DiagnosisSink,
) {
    for result in results.iter().filter(|result| result.probe_type == "dns_integrity") {
        classify_dns_tampering_result(result, domain_outcomes, hard_failure_codes, sink);
    }
}

fn classify_dns_tampering_result(
    result: &ProbeResult,
    domain_outcomes: &BTreeMap<String, Vec<&ProbeResult>>,
    hard_failure_codes: &mut BTreeSet<String>,
    sink: &mut DiagnosisSink,
) {
    if matches!(
        result.outcome.as_str(),
        "dns_sinkhole_substitution" | "dns_expected_mismatch" | "dns_nxdomain_mismatch"
    ) {
        push_dns_tampering(result, hard_failure_codes, sink);
        push_dns_blockpage_fingerprint(result, domain_outcomes, hard_failure_codes, sink);
        push_dns_injection(result, sink);
        push_dns_response_anomaly(result, sink);
        push_dns_cname_redirect(result, sink);
        push_dns_record_divergence(result, sink);

        if is_suspected_dns_tampering_outcome(result.outcome.as_str())
            && failure_detail_value(result, "dnsInjectionSuspected") == Some("true")
            && !sink.contains_code_for_target("dns_injection_suspected", result.target.as_str())
        {
            push_dns_injection_suspected(result, sink);
        }
    }
}

fn push_dns_tampering(result: &ProbeResult, hard_failure_codes: &mut BTreeSet<String>, sink: &mut DiagnosisSink) {
    let injection_suspected = failure_detail_value(result, "dnsInjectionSuspected") == Some("true");
    let (observation_kind, summary) = if result.outcome == "dns_nxdomain_mismatch" {
        (
            "nxdomain_difference",
            format!("UDP DNS returned NXDOMAIN for {} while encrypted DNS returned addresses", result.target),
        )
    } else if injection_suspected {
        (
            "unusual_timing_answer_difference",
            format!(
                "UDP and encrypted DNS answers for {} differed; response timing was marked as unusual",
                result.target
            ),
        )
    } else {
        (
            "answer_difference",
            format!("DNS answers for {} differed from the expected or encrypted DNS answers", result.target),
        )
    };
    let mut evidence = diagnosis_evidence(
        result,
        &["udpAddresses", "encryptedAddresses", "udpLatencyMs", "encryptedLatencyMs", "expected"],
    );
    evidence.push(format!("observation={observation_kind}"));
    sink.push(Diagnosis {
        code: "dns_tampering".to_string(),
        summary,
        severity: "negative".to_string(),
        target: Some(result.target.clone()),
        evidence,
        recommendation: None,
        control_validated: None,
    });
    hard_failure_codes.insert("dns_tampering".to_string());
}

fn push_dns_blockpage_fingerprint(
    result: &ProbeResult,
    domain_outcomes: &BTreeMap<String, Vec<&ProbeResult>>,
    hard_failure_codes: &mut BTreeSet<String>,
    sink: &mut DiagnosisSink,
) {
    if !domain_outcomes
        .get(&normalize_host(&result.target))
        .is_some_and(|matches| matches.iter().any(|probe| probe.outcome == "http_blockpage"))
    {
        return;
    }

    sink.push(Diagnosis {
        code: "dns_blockpage_fingerprint".to_string(),
        summary: format!("{} appears redirected to a blockpage fingerprint", result.target),
        severity: "negative".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(result, &["udpAddresses", "encryptedAddresses"]),
        recommendation: None,
        control_validated: None,
    });
    hard_failure_codes.insert("dns_blockpage_fingerprint".to_string());
}

fn push_dns_injection(result: &ProbeResult, sink: &mut DiagnosisSink) {
    if failure_detail_value(result, "dnsInjectionSuspected") != Some("true") {
        return;
    }

    sink.push(Diagnosis {
        code: "dns_injection_suspected".to_string(),
        summary: format!(
            "UDP and encrypted DNS answers for {} differed; response timing was marked as unusual",
            result.target
        ),
        severity: "negative".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(
            result,
            &["udpAddresses", "encryptedAddresses", "udpLatencyMs", "encryptedLatencyMs"],
        ),
        recommendation: None,
        control_validated: None,
    });
}

fn push_dns_response_anomaly(result: &ProbeResult, sink: &mut DiagnosisSink) {
    let Some(score_str) = failure_detail_value(result, "udpTamperingScore") else {
        return;
    };
    let score: u32 = score_str.parse().unwrap_or(0);
    if score < 45 {
        return;
    }

    let signals = failure_detail_value(result, "udpAnomalySignals").unwrap_or_default();
    sink.push(Diagnosis {
        code: "dns_response_anomaly".to_string(),
        summary: format!(
            "DNS response for {} shows protocol-level anomalies (score {score}): {signals}",
            result.target
        ),
        severity: "warning".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(
            result,
            &[
                "udpTamperingScore",
                "udpAnomalySignals",
                "udpResponseSize",
                "udpAaFlag",
                "udpHasEdns0",
                "udpAuthorityCount",
                "udpMinTtl",
                "udpMaxTtl",
            ],
        ),
        recommendation: Some(
            "Compare UDP and encrypted DNS results; anomalies alone do not identify their cause".to_string(),
        ),
        control_validated: None,
    });
}

fn push_dns_cname_redirect(result: &ProbeResult, sink: &mut DiagnosisSink) {
    let cname_targets = failure_detail_value(result, "udpCnameTargets").unwrap_or_default();
    if cname_targets.is_empty() {
        return;
    }

    sink.push(Diagnosis {
        code: "dns_cname_redirect".to_string(),
        summary: format!("DNS response for {} contains CNAME aliases to {}", result.target, cname_targets),
        severity: "warning".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(result, &["udpCnameTargets", "udpAddresses", "encryptedAddresses"]),
        recommendation: Some("Compare the CNAME answers from UDP and encrypted DNS".to_string()),
        control_validated: None,
    });
}

fn push_dns_record_divergence(result: &ProbeResult, sink: &mut DiagnosisSink) {
    let Some(score_str) = failure_detail_value(result, "comparisonScore") else {
        return;
    };
    let score: u32 = score_str.parse().unwrap_or(0);
    if score < 30 {
        return;
    }

    let signals = failure_detail_value(result, "comparisonSignals").unwrap_or_default();
    sink.push(Diagnosis {
        code: "dns_record_divergence".to_string(),
        summary: format!(
            "DNS records for {} diverge between UDP and encrypted resolvers (score {score}): {signals}",
            result.target
        ),
        severity: "warning".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(
            result,
            &[
                "comparisonScore",
                "comparisonSignals",
                "recordTypeMismatch",
                "extraCnames",
                "ttlDivergence",
                "authorityMismatch",
                "udpRecordTypes",
                "encryptedRecordTypes",
            ],
        ),
        recommendation: Some(
            "Compare UDP and encrypted DNS results; record differences alone do not identify their cause".to_string(),
        ),
        control_validated: None,
    });
}

fn push_dns_injection_suspected(result: &ProbeResult, sink: &mut DiagnosisSink) {
    sink.push(Diagnosis {
        code: "dns_injection_suspected".to_string(),
        summary: format!(
            "UDP and encrypted DNS answers for {} differed; response timing was marked as unusual",
            result.target
        ),
        severity: "negative".to_string(),
        target: Some(result.target.clone()),
        evidence: diagnosis_evidence(
            result,
            &["udpAddresses", "encryptedAddresses", "udpLatencyMs", "encryptedLatencyMs"],
        ),
        recommendation: None,
        control_validated: None,
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::ProbeDetail;

    #[test]
    fn dns_differences_do_not_claim_record_deletion_or_injection() {
        for (outcome, expected) in [
            (
                "dns_nxdomain_mismatch",
                "UDP DNS returned NXDOMAIN for target.example while encrypted DNS returned addresses",
            ),
            (
                "dns_sinkhole_substitution",
                "UDP and encrypted DNS answers for target.example differed; response timing was marked as unusual",
            ),
        ] {
            let result = ProbeResult {
                probe_type: "dns_integrity".into(),
                target: "target.example".into(),
                outcome: outcome.into(),
                details: vec![ProbeDetail { key: "dnsInjectionSuspected".into(), value: "true".into() }],
            };
            let mut sink = DiagnosisSink::new();
            classify_dns_diagnoses(&[result], &BTreeMap::new(), &mut BTreeSet::new(), &mut sink);
            let diagnoses = sink.into_vec();
            assert_eq!(diagnoses.iter().find(|diagnosis| diagnosis.code == "dns_tampering").unwrap().summary, expected);
            assert!(diagnoses.iter().all(|diagnosis| !diagnosis.summary.contains("injection")));
            assert!(
                diagnoses
                    .iter()
                    .all(|diagnosis| !diagnosis.evidence.iter().any(|value| value.starts_with("mechanism=")))
            );
        }
    }
}
