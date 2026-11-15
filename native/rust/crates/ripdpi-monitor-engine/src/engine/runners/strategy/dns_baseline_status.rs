use crate::engine::runtime::ExecutionRuntime;
use crate::types::ProbeResult;

pub(super) fn baseline_status(
    runtime: &ExecutionRuntime,
    results: &[ProbeResult],
) -> (&'static str, &'static str, &'static str) {
    if let Some(failure) = &runtime.strategy.baseline_failure {
        return (failure.class.as_str(), "Strategy DNS baseline classified a resolver failure", "warn");
    }

    let mut outcomes = results.iter().map(|result| result.outcome.as_str());
    if outcomes.clone().all(|outcome| outcome == "not_applicable") {
        ("not_applicable", "Strategy DNS baseline has no applicable targets", "info")
    } else if outcomes.clone().all(|outcome| outcome == "dns_match") {
        ("dns_match", "Strategy DNS baseline answers matched", "info")
    } else if outcomes.any(|outcome| matches!(outcome, "dns_match" | "dns_compatible_divergence")) {
        ("partial", "Strategy DNS baseline has limited comparison evidence", "info")
    } else {
        ("dns_oracle_unavailable", "Strategy DNS baseline could not compare resolver answers", "info")
    }
}
