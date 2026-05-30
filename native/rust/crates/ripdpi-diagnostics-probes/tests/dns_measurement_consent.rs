//! Privacy guard: DNS measurement is bounded to the user-requested target.
//!
//! The Encrypted DNS / HTTPS-SVCB classifier epic adopts the C-Saw
//! "measurement with consent" posture: DNS measurement happens *only* for the
//! destination the user is actually trying to reach. There is no preloaded
//! destination catalog, and the measurement surface must never silently expand
//! to popular-domain lists (such as the connectivity-warmup catalogs in
//! `ripdpi-proxy-runtime` / `ripdpi-runtime-services`).
//!
//! These tests exercise the actual measurement mechanism — the DoH survey
//! runners — with a recording HTTP client and assert that every request the
//! runner issues is derived from (a configured resolver endpoint + the single
//! supplied target host), and never from a hardcoded destination list.
//!
//! See `docs/contributor/dns-measurement-consent.md` for the full rule.

use std::sync::Mutex;
use std::time::Duration;

use ripdpi_diagnostics_probes::probes::doh_json_survey::{
    default_json_resolvers, DohJsonResolverEndpoint, DohJsonSurveyRunner,
};
use ripdpi_diagnostics_probes::probes::doh_survey::{
    DohHttpClient, DohHttpFuture, DohResolverEndpoint, DohSurveyRunner,
};
use ripdpi_diagnostics_probes::ProbeContext;

/// Domains that the connectivity-warmup catalogs probe. They must NEVER appear
/// on the DNS-measurement path — measurement is flow-scoped, warmup is not.
const WARMUP_CATALOG_DOMAINS: &[&str] = &["youtube.com", "discord.com", "telegram.org", "signal.org", "instagram.com"];

/// Records every URL requested and returns a canned A-record answer so the
/// survey runner exercises its full request-building path.
struct RecordingClient {
    urls: Mutex<Vec<String>>,
}

impl RecordingClient {
    fn new() -> Self {
        Self { urls: Mutex::new(Vec::new()) }
    }

    fn recorded(&self) -> Vec<String> {
        self.urls.lock().expect("recording mutex is never poisoned in tests").clone()
    }
}

impl DohHttpClient for RecordingClient {
    fn get<'a>(&'a self, url: &'a str, _timeout: Duration) -> DohHttpFuture<'a> {
        self.urls.lock().expect("recording mutex is never poisoned in tests").push(url.to_string());
        Box::pin(async move { Ok((200u16, br#"{"Status":0,"Answer":[{"type":1,"data":"203.0.113.1"}]}"#.to_vec())) })
    }
}

fn block_on<F: std::future::Future>(future: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread()
        .build()
        .expect("current-thread runtime builds with the rt feature")
        .block_on(future)
}

fn assert_only_measures_target(urls: &[String], target: &str) {
    assert!(!urls.is_empty(), "expected at least one measurement request");
    for url in urls {
        assert!(url.contains(&format!("name={target}")), "request did not target the supplied host: {url}");
        for forbidden in WARMUP_CATALOG_DOMAINS {
            assert!(!url.contains(forbidden), "measurement leaked a preloaded warmup destination ({forbidden}): {url}");
        }
    }
}

#[test]
fn doh_json_survey_only_measures_the_supplied_target() {
    let target = "user-requested.example";
    let resolvers = default_json_resolvers();
    let runner = DohJsonSurveyRunner {
        query_host: target.to_string(),
        timeout: Duration::from_secs(2),
        resolvers: resolvers.clone(),
        client: RecordingClient::new(),
    };

    let _probe = block_on(runner.survey(&ProbeContext::empty()));

    let urls = runner.client.recorded();
    // Exactly one request per configured resolver endpoint — no preloaded
    // destination catalog expands the measurement set.
    assert_eq!(urls.len(), resolvers.len(), "measured a different number of endpoints than were configured");
    assert_only_measures_target(&urls, target);
}

#[test]
fn doh_wire_survey_only_measures_the_supplied_target() {
    let target = "another-user-host.example";
    let resolvers = vec![
        DohResolverEndpoint { label: "google".to_string(), url: "https://dns.google/dns-query".to_string() },
        DohResolverEndpoint {
            label: "cloudflare".to_string(),
            url: "https://cloudflare-dns.com/dns-query".to_string(),
        },
    ];
    let runner = DohSurveyRunner {
        query_host: target.to_string(),
        timeout: Duration::from_secs(2),
        resolvers: resolvers.clone(),
        client: RecordingClient::new(),
    };

    // Empty context: no user-active resolver hint, so only the configured
    // resolver endpoints are contacted — and only for the supplied target.
    let _probe = block_on(runner.survey(&ProbeContext::empty()));

    let urls = runner.client.recorded();
    assert_eq!(urls.len(), resolvers.len());
    assert_only_measures_target(&urls, target);
}

#[test]
fn json_default_panel_holds_resolver_infrastructure_not_destinations() {
    // The only hardcoded hosts on the measurement path are resolver
    // *endpoints* (infrastructure), never user-facing destination domains.
    let resolver_hosts: Vec<String> =
        default_json_resolvers().into_iter().map(|r: DohJsonResolverEndpoint| r.url).collect();
    for url in &resolver_hosts {
        for forbidden in WARMUP_CATALOG_DOMAINS {
            assert!(!url.contains(forbidden), "resolver panel must not contain destination domains ({forbidden})");
        }
    }
}
