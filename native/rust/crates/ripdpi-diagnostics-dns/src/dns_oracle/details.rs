use crate::types::ProbeDetail;

use super::contracts::DnsOracleAssessment;
use super::resolver_label::resolver_label;

pub(super) fn detail_entries<T>(assessment: &DnsOracleAssessment<T>) -> Vec<ProbeDetail> {
    let attempted = assessment.attempts.iter().map(|attempt| attempt.resolver_id.clone()).collect::<Vec<_>>();
    let succeeded = assessment
        .attempts
        .iter()
        .filter(|attempt| attempt.error.is_none() && !attempt.answers.is_empty())
        .map(|attempt| attempt.resolver_id.clone())
        .collect::<Vec<_>>();
    let failed = assessment
        .attempts
        .iter()
        .filter(|attempt| attempt.error.is_some() || attempt.answers.is_empty())
        .map(|attempt| attempt.resolver_id.clone())
        .collect::<Vec<_>>();
    let attempt_diagnostics = assessment
        .attempts
        .iter()
        .map(|attempt| {
            let code =
                attempt.error.as_deref().unwrap_or(if attempt.answers.is_empty() { "empty_answer" } else { "ok" });
            format!("{}:{code}:{}:{}", attempt.resolver_id, attempt.latency_ms, attempt.answers.len())
        })
        .collect::<Vec<_>>();

    let mut details = Vec::with_capacity(10);
    push_detail(&mut details, "oracleTrust", assessment.trust.as_str().to_string());
    push_detail(&mut details, "oracleTermination", assessment.termination.as_str().to_string());
    push_detail(&mut details, "oracleConfidenceScore", assessment.confidence_score.to_string());
    push_detail(
        &mut details,
        "oracleSelectedResolverId",
        assessment.selected.as_ref().map(|candidate| resolver_label(&candidate.endpoint)).unwrap_or_default(),
    );
    push_detail(&mut details, "oracleAgreementResolvers", assessment.agreement_resolver_ids.join("|"));
    push_detail(&mut details, "oracleDisagreementResolvers", assessment.disagreement_resolver_ids.join("|"));
    push_detail(&mut details, "oracleTriedResolvers", attempted.join("|"));
    push_detail(&mut details, "oracleSuccessfulResolvers", succeeded.join("|"));
    push_detail(&mut details, "oracleFailedResolvers", failed.join("|"));
    push_detail(&mut details, "oracleAttemptDiagnostics", attempt_diagnostics.join("|"));
    details
}

#[inline(never)]
fn push_detail(details: &mut Vec<ProbeDetail>, key: &str, value: String) {
    details.push(ProbeDetail { key: key.to_string(), value });
}
