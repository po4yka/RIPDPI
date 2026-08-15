use std::collections::BTreeSet;
use std::sync::atomic::{AtomicBool, Ordering};

use crate::connectivity::adapters::dns::{resolve_via_encrypted_dns_with_raw, resolve_via_udp_with_observations};
use crate::connectivity::adapters::dns_oracle::{DnsOracleConfig, DnsOracleResponse, evaluate_dns_oracles};
use crate::connectivity::adapters::transport::TransportConfig;
use crate::connectivity::adapters::util::{DEFAULT_DNS_SERVER, is_suspected_dns_tampering_outcome};
use crate::probe_context::ProbeExecutionContext;
use crate::types::{DnsTarget, ProbeDetail, ProbeResult, ScanPathMode};

use super::super::trigger_fuzzing::append_dns_trigger_fuzzing_details;

mod classification;
mod details;
mod response_analysis;

use classification::{append_dns_classifier_details, classify_dns_probe_outcome, oracle_result_for_probe};
use details::{DnsProbeDetailInputs, append_injection_profile_details, build_dns_probe_details};
use response_analysis::{append_record_comparison_details, append_udp_response_analysis};

/// Classify DNS latency into a human-readable quality tier.
///
/// Thresholds:
/// - UDP > 3000ms => "throttled"
/// - encrypted < 100ms => "fast"
/// - encrypted 100..=500ms => "normal"
/// - encrypted > 500ms => "slow"
/// - parse failure => "unknown"
pub fn classify_dns_latency_quality(udp_latency_ms: &str, encrypted_latency_ms: &str) -> String {
    let udp: u64 = udp_latency_ms.parse().unwrap_or(0);
    let encrypted: u64 = encrypted_latency_ms.parse().unwrap_or(0);
    if udp > 3000 {
        return "throttled".to_string();
    }
    if encrypted == 0 && udp == 0 {
        return "unknown".to_string();
    }
    // When UDP is suspiciously fast relative to encrypted, flag as injected.
    if udp > 0 {
        let ratio = (encrypted as f64) / (udp as f64);
        if ratio >= 20.0 {
            return "injected".to_string();
        }
    }
    match encrypted {
        0..=99 => "fast".to_string(),
        100..=500 => "normal".to_string(),
        _ => "slow".to_string(),
    }
}

/// Returns `true` when the UDP DNS response arrived suspiciously fast (<=5ms)
/// while the encrypted resolver returned different answers -- a strong signal
/// of in-path DNS injection (e.g., middlebox DPI equipment racing forged responses).
pub fn is_dns_injection_suspected(udp_latency_ms: &str, outcome: &str) -> bool {
    let udp: u64 = udp_latency_ms.parse().unwrap_or(u64::MAX);
    udp <= 5 && is_suspected_dns_tampering_outcome(outcome)
}

pub fn run_dns_probe(target: &DnsTarget, transport: &TransportConfig, path_mode: &ScanPathMode) -> ProbeResult {
    let context = ProbeExecutionContext::new(transport.clone());
    let cancel = AtomicBool::new(false);
    run_dns_probe_with_context(target, &context, path_mode, &cancel)
}

pub fn run_dns_probe_with_context(
    target: &DnsTarget,
    context: &ProbeExecutionContext,
    path_mode: &ScanPathMode,
    cancel: &AtomicBool,
) -> ProbeResult {
    let udp_server = target.udp_server.clone().unwrap_or_else(|| DEFAULT_DNS_SERVER.to_string());
    let resolvers = match context.resolvers_for_dns_target(target) {
        Ok(value) => value,
        Err(err) => return dns_probe_unavailable_result(target, err),
    };
    let udp_resolution = resolve_via_udp_with_observations(&target.domain, &udp_server, context.transport());
    let udp_latency_ms = udp_resolution.latency_ms.to_string();
    let oracle_assessment = evaluate_dns_oracles(
        resolvers.primary.clone(),
        &resolvers.fallback,
        2,
        DnsOracleConfig::default(),
        || cancel.load(Ordering::Acquire),
        |endpoint, _| {
            let (result, raw_response) =
                resolve_via_encrypted_dns_with_raw(&target.domain, endpoint.clone(), context.transport());
            result.map(|addresses| DnsOracleResponse { addresses, raw_response })
        },
        |answer| answer.addresses.clone(),
    );
    let encrypted_result = oracle_result_for_probe(&oracle_assessment);
    let raw_encrypted_response =
        oracle_assessment.selected.as_ref().and_then(|selected| selected.value.raw_response.clone());
    let encrypted_latency_ms = oracle_assessment.preferred_latency_ms().to_string();

    let expected: BTreeSet<String> = target.expected_ips.iter().cloned().collect();
    let outcome = classify_dns_probe_outcome(
        &udp_resolution.result,
        &encrypted_result,
        path_mode,
        &udp_latency_ms,
        &expected,
        udp_resolution.error_kind.as_deref(),
        udp_resolution.attempt_count,
        udp_resolution.retry_recovered,
    );
    let injection_suspected = is_dns_injection_suspected(&udp_latency_ms, &outcome);
    let selected_endpoint =
        oracle_assessment.selected.as_ref().map_or(&resolvers.primary, |selected| &selected.endpoint);
    let selected_bootstrap_ips = selected_endpoint.bootstrap_ips.iter().map(ToString::to_string).collect::<Vec<_>>();
    let encrypted_addresses = match &encrypted_result {
        Ok(addresses) if !addresses.is_empty() => addresses.join("|"),
        Ok(_) => "[]".to_string(),
        Err(err) => err.clone(),
    };

    let mut result = ProbeResult {
        probe_type: "dns_integrity".to_string(),
        target: target.domain.clone(),
        outcome,
        details: build_dns_probe_details(DnsProbeDetailInputs {
            udp_server: &udp_server,
            udp_result: &udp_resolution.result,
            udp_latency_ms: &udp_latency_ms,
            udp_attempt_count: udp_resolution.attempt_count,
            udp_success_count: udp_resolution.success_count,
            udp_error_kind: udp_resolution.error_kind.as_deref(),
            udp_retry_recovered: udp_resolution.retry_recovered,
            udp_cache_hit: udp_resolution.cache_hit,
            encrypted_endpoint: &resolvers.primary,
            encrypted_bootstrap_ips: &resolvers.bootstrap_ips,
            selected_bootstrap_ips: &selected_bootstrap_ips,
            encrypted_result: &encrypted_result,
            encrypted_addresses: &encrypted_addresses,
            encrypted_latency_ms: &encrypted_latency_ms,
            injection_suspected,
            expected: &expected,
            oracle_assessment: &oracle_assessment,
        }),
    };
    append_dns_classifier_details(
        &mut result,
        &target.domain,
        &udp_resolution.result,
        &encrypted_result,
        selected_endpoint,
        context.transport(),
        &oracle_assessment,
    );
    result.details.extend(oracle_assessment.detail_entries());

    if is_suspected_dns_tampering_outcome(result.outcome.as_str()) {
        append_injection_profile_details(
            &mut result,
            &udp_resolution.result,
            &encrypted_result,
            &udp_latency_ms,
            &encrypted_latency_ms,
        );
    }

    if let Some(raw) = udp_resolution.raw_response.as_deref() {
        append_udp_response_analysis(&mut result, raw);
    }

    if let (Some(udp_raw), Some(enc_raw)) = (udp_resolution.raw_response.as_deref(), raw_encrypted_response.as_deref())
    {
        append_record_comparison_details(&mut result, udp_raw, enc_raw);
    }

    if should_run_dns_trigger_fuzzing(result.outcome.as_str()) {
        append_dns_trigger_fuzzing_details(
            &mut result.details,
            target,
            context.transport(),
            result.outcome.as_str(),
            &encrypted_result,
        );
    }

    result
}

fn should_run_dns_trigger_fuzzing(outcome: &str) -> bool {
    is_suspected_dns_tampering_outcome(outcome)
}

fn dns_probe_unavailable_result(target: &DnsTarget, err: String) -> ProbeResult {
    ProbeResult {
        probe_type: "dns_integrity".to_string(),
        target: target.domain.clone(),
        outcome: "dns_unavailable".to_string(),
        details: vec![ProbeDetail { key: "encryptedDnsError".to_string(), value: err }],
    }
}

#[cfg(test)]
mod tests {
    use super::classify_dns_latency_quality;

    #[test]
    fn dns_latency_quality_throttled_for_slow_udp() {
        assert_eq!(classify_dns_latency_quality("6000", "100"), "throttled");
        assert_eq!(classify_dns_latency_quality("3001", "50"), "throttled");
    }

    #[test]
    fn dns_latency_quality_fast_for_quick_encrypted() {
        assert_eq!(classify_dns_latency_quality("20", "50"), "fast");
        assert_eq!(classify_dns_latency_quality("20", "99"), "fast");
    }

    #[test]
    fn dns_latency_quality_normal_for_moderate() {
        assert_eq!(classify_dns_latency_quality("20", "250"), "normal");
    }

    #[test]
    fn dns_latency_quality_slow_for_high_encrypted() {
        // UDP 50ms, encrypted 600ms => ratio 12 (below injected threshold)
        assert_eq!(classify_dns_latency_quality("50", "600"), "slow");
    }

    #[test]
    fn dns_latency_quality_unknown_for_zero() {
        assert_eq!(classify_dns_latency_quality("0", "0"), "unknown");
    }

    #[test]
    fn dns_latency_quality_injected_for_high_ratio() {
        // UDP 3ms, encrypted 200ms => ratio ~66.7 => "injected"
        assert_eq!(classify_dns_latency_quality("3", "200"), "injected");
        // UDP 5ms, encrypted 100ms => ratio 20.0 => "injected"
        assert_eq!(classify_dns_latency_quality("5", "100"), "injected");
    }

    #[test]
    fn dns_latency_quality_not_injected_below_threshold() {
        // UDP 10ms, encrypted 99ms => ratio 9.9 => "fast" (below 20x)
        assert_eq!(classify_dns_latency_quality("10", "99"), "fast");
    }
}
