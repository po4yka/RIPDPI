use ripdpi_dns_resolver::EncryptedDnsEndpoint;

use crate::connectivity::adapters::dns_oracle::{DnsOracleAssessment, DnsOracleResponse};
use crate::types::DnsResponseSemantics;

type EncryptedObservation = (EncryptedDnsEndpoint, DnsResponseSemantics, Option<Vec<u8>>);

pub(super) struct EncryptedResponseEvidence<'a> {
    pub(super) endpoint: &'a EncryptedDnsEndpoint,
    pub(super) response: Option<&'a DnsResponseSemantics>,
    pub(super) latency_ms: u128,
    pub(super) role: &'static str,
}

pub(super) fn encrypted_response_evidence<'a>(
    primary: &'a EncryptedDnsEndpoint,
    assessment: &'a DnsOracleAssessment<DnsOracleResponse>,
    observations: &'a [EncryptedObservation],
) -> EncryptedResponseEvidence<'a> {
    let selected = assessment.selected.as_ref();
    let index = if let Some(selected) = selected {
        observations
            .iter()
            .position(|(endpoint, _, raw)| endpoint == &selected.endpoint && raw == &selected.value.raw_response)
    } else {
        observations.iter().position(|(_, _, raw)| raw.is_some()).or_else(|| (!observations.is_empty()).then_some(0))
    };
    let observation = index.and_then(|index| observations.get(index));
    EncryptedResponseEvidence {
        endpoint: selected
            .map(|selected| &selected.endpoint)
            .or_else(|| observation.map(|value| &value.0))
            .unwrap_or(primary),
        response: observation.map(|value| &value.1),
        latency_ms: selected
            .map(|selected| selected.latency_ms)
            .or_else(|| index.and_then(|index| assessment.attempts.get(index)).map(|attempt| attempt.latency_ms))
            .unwrap_or(0),
        role: if selected.is_some() {
            "selected"
        } else if observation.is_some() {
            "observed_first"
        } else {
            "not_observed"
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::DnsResponseOutcome;
    use ripdpi_diagnostics_dns::dns::{
        build_dns_query_with_type, encrypted_dns_endpoint_for_resolver_id, parse_dns_response_semantics,
    };
    use ripdpi_diagnostics_dns::dns_oracle::{
        DnsOracleAttempt, DnsOracleCandidate, DnsOracleTermination, DnsOracleTrust,
    };

    fn observation(id: &str, rcode: u8) -> EncryptedObservation {
        let query = build_dns_query_with_type("a.example", 1, 1).unwrap();
        let mut raw = query.clone();
        raw[2..4].copy_from_slice(&(0x8180u16 | u16::from(rcode)).to_be_bytes());
        if rcode == 0 {
            raw[6..8].copy_from_slice(&[0, 1]);
            raw.extend_from_slice(&[0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 1, 1, 1]);
        }
        let (_, facts) = parse_dns_response_semantics(&query, &raw).unwrap();
        (encrypted_dns_endpoint_for_resolver_id(id), facts, Some(raw))
    }

    fn assessment() -> DnsOracleAssessment<DnsOracleResponse> {
        DnsOracleAssessment {
            trust: DnsOracleTrust::Unavailable,
            confidence_score: 0,
            selected: None,
            agreement_resolver_ids: vec![],
            disagreement_resolver_ids: vec![],
            attempts: ["adguard", "dnssb"]
                .into_iter()
                .map(|id| DnsOracleAttempt {
                    resolver_id: id.to_string(),
                    is_primary: id == "adguard",
                    latency_ms: if id == "adguard" { 7 } else { 17 },
                    answers: vec![],
                    error: None,
                })
                .collect(),
            termination: DnsOracleTermination::FallbackLimitReached,
        }
    }

    #[test]
    fn selected_fallback_facts_keep_the_selected_endpoint_and_latency() {
        let observations = [observation("adguard", 3), observation("dnssb", 0), observation("google_ip", 0)];
        let mut assessment = assessment();
        assessment.trust = DnsOracleTrust::TrustedAgreement;
        assessment.confidence_score = 100;
        assessment.agreement_resolver_ids = vec!["dnssb".to_string(), "google_ip".to_string()];
        assessment.selected = Some(DnsOracleCandidate {
            endpoint: observations[1].0.clone(),
            value: DnsOracleResponse {
                addresses: vec!["1.1.1.1".to_string()],
                raw_response: observations[1].2.clone(),
            },
            answers: vec!["1.1.1.1".to_string()],
            is_primary: false,
            latency_ms: 17,
        });
        let evidence = encrypted_response_evidence(&observations[0].0, &assessment, &observations);
        assert_eq!(evidence.endpoint.resolver_id.as_deref(), Some("dnssb"));
        assert_eq!(evidence.response.unwrap().outcome, DnsResponseOutcome::Answer);
        assert_eq!(evidence.latency_ms, 17);
        assert_eq!(evidence.role, "selected");
    }

    #[test]
    fn disagreement_keeps_observed_facts_separate_from_selection() {
        let mut observations = [observation("adguard", 0), observation("dnssb", 0)];
        *observations[1].2.as_mut().unwrap().last_mut().unwrap() = 2;
        let mut assessment = assessment();
        assessment.trust = DnsOracleTrust::Disagreement;
        assessment.confidence_score = 25;
        assessment.disagreement_resolver_ids = vec!["adguard".to_string(), "dnssb".to_string()];
        let evidence = encrypted_response_evidence(&observations[0].0, &assessment, &observations);
        assert_eq!(evidence.endpoint.resolver_id.as_deref(), Some("adguard"));
        assert_eq!(evidence.response.unwrap().outcome, DnsResponseOutcome::Answer);
        assert_eq!(evidence.role, "observed_first");
        assert!(assessment.selected.is_none());
    }

    #[test]
    fn unselected_negative_is_observed_without_claiming_consensus() {
        let observations = [observation("adguard", 3), observation("dnssb", 3)];
        let assessment = assessment();
        let evidence = encrypted_response_evidence(&observations[0].0, &assessment, &observations);
        assert_eq!(evidence.endpoint.resolver_id.as_deref(), Some("adguard"));
        assert_eq!(evidence.response.unwrap().outcome, DnsResponseOutcome::Nxdomain);
        assert_eq!(evidence.latency_ms, 7);
        assert_eq!(evidence.role, "observed_first");
        assert!(assessment.selected.is_none());
    }

    #[test]
    fn first_wire_reply_does_not_inherit_the_failed_primary_metadata() {
        let mut observations = [observation("adguard", 3), observation("dnssb", 3)];
        observations[0].1 = DnsResponseSemantics::unobserved("A", DnsResponseOutcome::TransportError);
        observations[0].2 = None;
        let assessment = assessment();
        let evidence = encrypted_response_evidence(&observations[0].0, &assessment, &observations);
        assert_eq!(evidence.endpoint.resolver_id.as_deref(), Some("dnssb"));
        assert_eq!(evidence.response.unwrap().outcome, DnsResponseOutcome::Nxdomain);
        assert_eq!(evidence.latency_ms, 17);
        assert_eq!(evidence.role, "observed_first");
    }
}
