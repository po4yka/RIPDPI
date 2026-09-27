use std::collections::HashSet;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::Instant;

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::ProbeExecutionContext;
use crate::engine::runtime::ExecutionPlan;
use crate::http::{HttpResponse, execute_doh_json_request};
use crate::transport::TargetAddress;
use crate::types::{DnsTarget, ProbeDetail, ProbeResult};
use crate::util::{DEFAULT_DOH_JSON_RESOLVERS, encode_doh_json_query_name, parse_doh_json_ip_answer};

use super::interrupted;
use super::support::ConnectivityProbeFamily;

/// Measure vendor JSON DoH APIs for explicitly selected DNS targets.
pub(in crate::engine::runners) struct DohJsonSurveyRunner;

struct DohJsonSurveyFamily;

impl ConnectivityProbeFamily for DohJsonSurveyFamily {
    type Target = DnsTarget;

    const PHASE: &'static str = "doh_json";
    const ARTIFACT_SOURCE: &'static str = "doh_json_survey";

    fn targets(plan: &ExecutionPlan) -> Vec<Self::Target> {
        unique_targets(&plan.request.dns_targets)
    }

    fn message(target: &Self::Target) -> String {
        format!("DoH-JSON {}", target.domain)
    }

    fn run_probe(
        target: &Self::Target,
        plan: &ExecutionPlan,
        _probe_context: &ProbeExecutionContext,
        cancel: &AtomicBool,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        survey(&target.domain, cancel, |host, path| {
            execute_doh_json_request(&TargetAddress::Host(host.to_string()), &plan.transport, host, path, cancel)
        })
    }
}

fn unique_targets(targets: &[DnsTarget]) -> Vec<DnsTarget> {
    let mut seen = HashSet::new();
    targets.iter().filter(|target| seen.insert(target.domain.to_ascii_lowercase())).cloned().collect()
}

fn survey(
    domain: &str,
    cancel: &AtomicBool,
    mut fetch: impl FnMut(&str, &str) -> Result<HttpResponse, String>,
) -> ProbeResult {
    let mut result = ProbeResult {
        probe_type: "doh_json_survey".to_string(),
        target: domain.to_string(),
        outcome: "inconclusive".to_string(),
        details: vec![ProbeDetail { key: "surveyStatus".to_string(), value: "incomplete".to_string() }],
    };
    let Some(query_name) = encode_doh_json_query_name(domain) else {
        result.details[0].value = "invalid_target".to_string();
        return result;
    };

    let mut answered = false;
    let mut no_address = 0;
    for &(label, host, base_path) in DEFAULT_DOH_JSON_RESOLVERS {
        if interrupted(cancel) {
            return result;
        }
        let path = format!("{base_path}?name={query_name}&type=A&ct=application/dns-json");
        let started = Instant::now();
        let response = fetch(host, &path);
        let elapsed_ms = started.elapsed().as_millis();
        let (status, address) = match response {
            Ok(response) if (200..300).contains(&response.status_code) => {
                match parse_doh_json_ip_answer(&response.body) {
                    Ok(Some(ip)) => {
                        answered = true;
                        ("answered".to_string(), Some(ip.to_string()))
                    }
                    Ok(None) => {
                        no_address += 1;
                        ("no_address".to_string(), None)
                    }
                    Err(_) => ("invalid_response".to_string(), None),
                }
            }
            Ok(response) => (format!("http_{}", response.status_code), None),
            Err(error) => (transport_status(&error).to_string(), None),
        };
        result.details.push(ProbeDetail { key: format!("{label}.status"), value: status });
        result.details.push(ProbeDetail { key: format!("{label}.roundTripMs"), value: elapsed_ms.to_string() });
        if let Some(address) = address {
            result.details.push(ProbeDetail { key: format!("{label}.answerIp"), value: address });
        }
    }
    if !interrupted(cancel) {
        let (outcome, status) = if answered {
            ("ok", "answered")
        } else if no_address == DEFAULT_DOH_JSON_RESOLVERS.len() {
            ("inconclusive", "no_address")
        } else {
            ("inconclusive", "unavailable")
        };
        result.outcome = outcome.to_string();
        result.details[0].value = status.to_string();
    }
    result
}

fn transport_status(error: &str) -> &'static str {
    let lower = error.to_ascii_lowercase();
    if lower.contains("timeout") || lower.contains("timed out") || lower.contains("deadline") {
        "timeout"
    } else if lower.contains("tls") || lower.contains("certificate") {
        "tls_error"
    } else {
        "network_error"
    }
}

impl_connectivity_runner!(DohJsonSurveyRunner, DohJsonSurveyFamily, DohJsonSurvey);

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::{Duration, Instant};

    use crate::http::HttpResponse;
    use crate::util::{DEFAULT_DOH_JSON_RESOLVERS, encode_doh_json_query_name, with_scan_io_deadline};

    use super::{survey, unique_targets};

    #[test]
    fn expanded_resolver_candidates_are_surveyed_once_per_domain() {
        let targets = [
            serde_json::from_str(r#"{"domain":"Selected.Example","encryptedResolverId":"google"}"#).unwrap(),
            serde_json::from_str(r#"{"domain":"selected.example","encryptedResolverId":"cloudflare"}"#).unwrap(),
        ];
        let unique = unique_targets(&targets);
        assert_eq!(unique.len(), 1);
        assert_eq!(unique[0].domain, "Selected.Example");
    }

    fn response(status_code: u16, body: &[u8]) -> HttpResponse {
        HttpResponse { status_code, reason: String::new(), headers: HashMap::new(), body: body.to_vec() }
    }

    #[test]
    fn surveys_only_selected_domain_and_preserves_each_resolver_status() {
        let mut requests = Vec::new();
        let result = survey("selected&extra.example", &AtomicBool::new(false), |host, path| {
            requests.push((host.to_string(), path.to_string()));
            if host == "dns.google" {
                Ok(response(200, br#"{"Status":0,"Answer":[{"type":1,"data":"203.0.113.7"}]}"#))
            } else {
                Ok(response(503, b"unavailable"))
            }
        });
        assert_eq!(requests.len(), DEFAULT_DOH_JSON_RESOLVERS.len());
        assert!(requests.iter().all(|(_, path)| {
            path.contains("name=selected%26extra.example&type=A") && !path.contains("name=selected&extra")
        }));
        assert_eq!(result.outcome, "ok");
        assert!(result.details.iter().any(|detail| detail.key == "google.answerIp" && detail.value == "203.0.113.7"));
        assert!(result.details.iter().any(|detail| detail.key == "cloudflare.status" && detail.value == "http_503"));
    }

    #[test]
    fn invalid_or_empty_responses_do_not_count_as_answers() {
        let result = survey("selected.example", &AtomicBool::new(false), |host, _| match host {
            "dns.google" => Ok(response(200, br#"{"Status":0,"Answer":[]}"#)),
            "cloudflare-dns.com" => Ok(response(200, b"not json")),
            _ => Err("certificate invalid".to_string()),
        });
        assert_eq!(result.outcome, "inconclusive");
        assert!(result.details.iter().any(|detail| detail.key == "surveyStatus" && detail.value == "unavailable"));
        assert!(
            result.details.iter().any(|detail| detail.key == "cloudflare.status" && detail.value == "invalid_response")
        );
        assert!(result.details.iter().any(|detail| detail.key == "adguard.status" && detail.value == "tls_error"));
    }

    #[test]
    fn no_address_from_all_resolvers_is_inconclusive() {
        let result = survey("selected.example", &AtomicBool::new(false), |_, _| Ok(response(200, br#"{"Status":3}"#)));
        assert_eq!(result.outcome, "inconclusive");
        assert!(result.details.iter().any(|detail| detail.key == "surveyStatus" && detail.value == "no_address"));
    }

    #[test]
    fn cancellation_or_deadline_stops_following_requests() {
        let cancel = AtomicBool::new(false);
        let mut count = 0;
        let result = survey("selected.example", &cancel, |_, _| {
            count += 1;
            cancel.store(true, Ordering::Release);
            Ok(response(200, br#"{"Status":0,"Answer":[{"type":1,"data":"203.0.113.7"}]}"#))
        });
        assert_eq!(count, 1);
        assert_eq!(result.outcome, "inconclusive");

        let mut count = 0;
        let result = with_scan_io_deadline(Some(Instant::now() - Duration::from_millis(1)), || {
            survey("selected.example", &AtomicBool::new(false), |_, _| {
                count += 1;
                Ok(response(200, b"{}"))
            })
        });
        assert_eq!(count, 0);
        assert_eq!(result.outcome, "inconclusive");
    }

    #[test]
    fn empty_and_oversized_domains_do_not_start_requests() {
        for domain in ["", &"x".repeat(254)] {
            let result = survey(domain, &AtomicBool::new(false), |_, _| panic!("unexpected request"));
            assert_eq!(result.outcome, "inconclusive");
            assert!(
                result.details.iter().any(|detail| detail.key == "surveyStatus" && detail.value == "invalid_target")
            );
        }
        assert_eq!(
            encode_doh_json_query_name("name\r\nHost: attacker"),
            Some("name%0D%0AHost%3A%20attacker".to_string())
        );
    }
}
