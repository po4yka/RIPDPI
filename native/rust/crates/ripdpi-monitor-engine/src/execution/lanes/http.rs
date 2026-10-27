use crate::candidates::StrategyCandidateSpec;
use crate::http::try_http_request_targets;
use crate::transport::{TransportConfig, domain_connect_targets};
use crate::types::{DomainTarget, ProbeDetail, ProbeResult};
use crate::util::now_ms;

use super::support::candidate_probe_details;
use crate::execution::scoring::ProbeSample;

mod outcome;

use outcome::classify_http_observation;

pub(super) fn run_http_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = now_ms();
    let http_port = target.http_port.unwrap_or(80);
    let connect_targets = domain_connect_targets(target);
    let observation =
        try_http_request_targets(&connect_targets, http_port, transport, &target.host, &target.http_path, false);
    let latency_ms = now_ms().saturating_sub(started);
    let (outcome, fingerprint_name) = classify_http_observation(&observation);
    let h3 = observation.response.as_ref().and_then(|r| r.headers.get("alt-svc")).is_some_and(|v| v.contains("h3"));
    let mut details = candidate_probe_details(candidate, "HTTP", latency_ms);
    details.extend([
        ProbeDetail { key: "status".to_string(), value: observation.status },
        ProbeDetail { key: "error".to_string(), value: observation.error.unwrap_or_else(|| "none".to_string()) },
        ProbeDetail {
            key: "redirectLocation".to_string(),
            value: if outcome == "http_redirect" {
                observation
                    .response
                    .as_ref()
                    .and_then(|r| r.headers.get("location"))
                    .cloned()
                    .unwrap_or_else(|| "none".to_string())
            } else {
                "none".to_string()
            },
        },
    ]);
    if let Some(fp) = &fingerprint_name {
        details.push(ProbeDetail { key: "blockpageFingerprint".to_string(), value: fp.clone() });
    }
    details.push(ProbeDetail { key: "h3Advertised".to_string(), value: h3.to_string() });
    // An ISP block stub is commonly a 302 to another site, so only a same-site redirect proves the
    // candidate reached the origin.
    let off_site_redirect = outcome == "http_redirect"
        && observation
            .response
            .as_ref()
            .and_then(|r| r.headers.get("location"))
            .is_some_and(|location| redirect_leaves_site(&target.host, location));
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_http".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details,
        },
        success: outcome == "http_ok" || (outcome == "http_redirect" && !off_site_redirect),
        weight: 1,
        domain: Some(target.host.clone()),
        is_control: target.is_control,
        attempt_token: None,
        quality: if outcome == "http_ok" {
            3
        } else if outcome == "http_redirect" && !off_site_redirect {
            2
        } else if outcome == "http_blockpage" || outcome == "http_redirect" {
            1
        } else {
            0
        },
        latency_ms,
    }
}

/// Same-site rule of the runtime redirect trigger: the Location host must equal the requested host
/// or be its parent or child; a relative Location stays on the site.
fn redirect_leaves_site(host: &str, location: &str) -> bool {
    let request = format!("GET / HTTP/1.1\r\nHost: {host}\r\n\r\n");
    let response = format!("HTTP/1.1 302 Found\r\nLocation: {location}\r\n\r\n");
    ripdpi_packets::is_http_redirect(request.as_bytes(), response.as_bytes())
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener};

    use super::{redirect_leaves_site, run_http_strategy_probe};
    use crate::transport::TransportConfig;
    use crate::types::DomainTarget;

    fn probe_redirect(location: &str) -> crate::execution::scoring::ProbeSample {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture");
        let port = listener.local_addr().expect("fixture addr").port();
        let response = format!("HTTP/1.1 302 Found\r\nLocation: {location}\r\nContent-Length: 0\r\n\r\n");
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().expect("accept probe");
            let mut request = [0u8; 1024];
            let _ = socket.read(&mut request);
            socket.write_all(response.as_bytes()).expect("write redirect");
        });
        let target = DomainTarget {
            host: "localhost".to_string(),
            connect_ip: Some(Ipv4Addr::LOCALHOST.to_string()),
            connect_ips: Vec::new(),
            https_port: None,
            http_port: Some(port),
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        };
        let candidate = crate::candidates::candidate_spec(
            "test_id",
            "Test Label",
            "test_family",
            ripdpi_proxy_config::ProxyUiConfig::default(),
        );
        let sample = run_http_strategy_probe(&TransportConfig::Direct { route_experiment: None }, &target, &candidate);
        server.join().expect("fixture thread");
        sample
    }

    #[test]
    fn off_site_redirect_is_not_a_candidate_success() {
        let off_site = probe_redirect("http://warning.isp-stub.example/");
        assert_eq!(off_site.result.outcome, "http_redirect");
        assert!(!off_site.success);
        let same_site = probe_redirect("/login");
        assert_eq!(same_site.result.outcome, "http_redirect");
        assert!(same_site.success);
        assert!(same_site.quality > off_site.quality);
    }

    #[test]
    fn only_off_site_redirects_leave_the_site() {
        assert!(!redirect_leaves_site("example.com", "/login"));
        assert!(!redirect_leaves_site("example.com", "https://example.com/"));
        assert!(!redirect_leaves_site("example.com", "https://www.example.com/"));
        assert!(redirect_leaves_site("example.com", "http://warning.isp-stub.example/"));
        assert!(redirect_leaves_site("example.com", "http://203.0.113.9/"));
    }
}
