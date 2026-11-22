use std::net::IpAddr;
use std::sync::{Arc, atomic::AtomicBool};
use std::time::{Duration, Instant};

use ring::digest::{Context, SHA256};
use ripdpi_diagnostics_contracts::util::{active_scan_io_deadline, with_scan_io_deadline};
use ripdpi_diagnostics_contracts::{Http3Evidence, Http3ProbeConfig};
use ripdpi_diagnostics_http::http3::probe_http3_download;
use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::tls::{ApplicationProtocolPolicy, ProbeStreamOptions, TlsClientProfile};
use crate::connectivity::adapters::transport::TransportConfig;
use crate::types::{DownloadVerification, ProbeDetail, ProbeResult, ThroughputTarget, TransferMeasurement};

use super::target_parse::parse_http_target;
use super::throughput::measure_throughput_with_options;
use super::transfer::{interruption, matches_hex_digest};
use super::types::ThroughputSample;

const PAIR_BUDGET: Duration = Duration::from_secs(15);

pub(in crate::connectivity) fn run_http3_comparison(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
    progress: &mut dyn FnMut(&TransferMeasurement),
) -> ProbeResult {
    let Some((config, proof)) = prepare_pair(target, transport) else {
        return report(target, Leg::not_run(0), Leg::not_run(0));
    };
    let started = Instant::now();
    let own_deadline = started + PAIR_BUDGET;
    let deadline = active_scan_io_deadline().map_or(own_deadline, |scan_deadline| scan_deadline.min(own_deadline));
    if interruption(cancel, deadline).is_some() {
        return report(target, Leg::not_run(0), Leg::not_run(0));
    }
    // Reserve half the original budget for HTTP/3 even when TCP makes no progress.
    let tcp_deadline = started + deadline.saturating_duration_since(started) / 2;
    let options = ProbeStreamOptions {
        profile: TlsClientProfile::ComparisonTls13,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        certificate_policy: verifier.into(),
        key_log: None,
    };
    let tcp_target = ThroughputTarget { connect_ip: config.connect_ip.clone(), ..target.clone() };
    let tcp = with_scan_io_deadline(Some(tcp_deadline), || {
        tcp_leg(&measure_throughput_with_options(&tcp_target, transport, &options, cancel, 1, progress))
    });
    let http3 = if interruption(cancel, deadline).is_some() {
        Leg::not_run(0)
    } else {
        with_scan_io_deadline(Some(deadline), || measure_http3(&config, transport, verifier, cancel, proof))
    };
    report(target, tcp, http3)
}

fn prepare_pair<'a>(
    target: &'a ThroughputTarget,
    transport: &TransportConfig,
) -> Option<(Http3ProbeConfig, &'a DownloadVerification)> {
    if !matches!(transport, TransportConfig::Direct { route_experiment: None }) || target.validate_download().is_err() {
        return None;
    }
    let proof = target.verification.as_ref()?;
    let pin = target.connect_ip.as_deref()?.parse::<IpAddr>().ok()?.to_canonical().to_string();
    let parsed = parse_http_target(&target.url, Some(&pin), &[], None).ok()?;
    let config = Http3ProbeConfig {
        host: parsed.host,
        port: parsed.port,
        path: parsed.path,
        connect_ip: Some(pin),
        max_response_bytes: target.window_bytes as u64,
        timeout_ms: PAIR_BUDGET.as_millis() as u64,
        ..Http3ProbeConfig::default()
    };
    config.validate().ok()?;
    Some((config, proof))
}

fn measure_http3(
    config: &Http3ProbeConfig,
    transport: &TransportConfig,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
    proof: &DownloadVerification,
) -> Leg {
    let mut digest = Context::new(&SHA256);
    let facts = probe_http3_download(config, transport, cancel, verifier, &mut |bytes| digest.update(bytes));
    http3_leg(&facts, proof, digest)
}

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum Stage {
    Connect,
    Tls,
    Request,
    Headers,
    Body,
    Integrity,
    Complete,
    NotRun,
}

impl Stage {
    fn code(self) -> &'static str {
        match self {
            Self::Connect => "connect",
            Self::Tls => "tls",
            Self::Request => "request",
            Self::Headers => "headers",
            Self::Body => "body",
            Self::Integrity => "integrity",
            Self::Complete => "complete",
            Self::NotRun => "not_run",
        }
    }

    fn failure(self, timeout: bool) -> &'static str {
        match self {
            Self::Connect => "connection_failed",
            Self::Tls => "tls_failed",
            Self::Body if timeout => "body_timeout",
            _ if timeout => "response_timeout",
            _ => "http_failed",
        }
    }
}

#[derive(PartialEq, Eq)]
struct Leg {
    stage: Stage,
    outcome: &'static str,
    bytes: u64,
}

impl Leg {
    fn new(stage: Stage, outcome: &'static str, bytes: u64) -> Self {
        Self { stage, outcome, bytes }
    }

    fn not_run(bytes: u64) -> Self {
        Self::new(Stage::NotRun, "not_run", bytes)
    }
}

fn tcp_leg(sample: &ThroughputSample) -> Leg {
    let bytes = sample.bytes_read as u64;
    let stage = match sample.failure_stage.as_str() {
        "tls_handshake" => Stage::Tls,
        "request_write" => Stage::Request,
        "response_headers" | "response_status" => Stage::Headers,
        "body_read" => Stage::Body,
        _ => Stage::Connect,
    };
    match sample.verification_outcome {
        Some("verified_download_complete") => Leg::new(Stage::Complete, "verified", bytes),
        Some("verified_download_size_mismatch") => Leg::new(Stage::Integrity, "size_mismatch", bytes),
        Some("verified_download_hash_mismatch") => Leg::new(Stage::Integrity, "hash_mismatch", bytes),
        Some("verified_download_early_eof") if sample.measurement.as_ref().is_some_and(|m| m.response_complete) => {
            Leg::new(Stage::Integrity, "size_mismatch", bytes)
        }
        Some("verified_download_early_eof") => Leg::new(stage.max(Stage::Headers), "early_eof", bytes),
        Some("verified_download_inconclusive") => Leg::not_run(bytes),
        outcome => Leg::new(stage, stage.failure(outcome == Some("verified_download_timeout")), bytes),
    }
}

fn http3_leg(facts: &Http3Evidence, proof: &DownloadVerification, digest: Context) -> Leg {
    let bytes = facts.response_bytes;
    let stage = match facts.stage.as_str() {
        "HTTP_REQUEST" => Stage::Request,
        "HTTP_HEADERS" => Stage::Headers,
        "HTTP_BODY" => Stage::Body,
        _ if facts.tls_validated || matches!(facts.reason.as_deref(), Some("tls_error" | "alpn_mismatch")) => {
            Stage::Tls
        }
        _ => Stage::Connect,
    };
    match facts.status.as_str() {
        "CANCELLED" | "INVALID" | "UNSUPPORTED" => Leg::not_run(bytes),
        "TIMEOUT" if facts.stage == "DNS" => Leg::not_run(bytes),
        "BODY_LIMIT" => Leg::new(Stage::Integrity, "size_mismatch", bytes),
        "COMPLETE" if facts.body_complete => {
            if bytes != proof.expected_body_bytes as u64 {
                Leg::new(Stage::Integrity, "size_mismatch", bytes)
            } else if matches_hex_digest(digest.finish().as_ref(), &proof.sha256) {
                Leg::new(Stage::Complete, "verified", bytes)
            } else {
                Leg::new(Stage::Integrity, "hash_mismatch", bytes)
            }
        }
        _ if facts.reason.as_deref() == Some("early_eof") => Leg::new(Stage::Body, "early_eof", bytes),
        status => Leg::new(stage, stage.failure(status == "TIMEOUT"), bytes),
    }
}

fn report(target: &ThroughputTarget, tcp: Leg, http3: Leg) -> ProbeResult {
    let incomplete = tcp.stage == Stage::NotRun || http3.stage == Stage::NotRun;
    let divergence = if incomplete {
        Stage::NotRun.code()
    } else if tcp == http3 {
        "none"
    } else {
        tcp.stage.min(http3.stage).code()
    };
    let mut details = Vec::with_capacity(7);
    for (prefix, leg) in [("tcp", tcp), ("http3", http3)] {
        details.push(ProbeDetail { key: format!("{prefix}Outcome"), value: leg.outcome.into() });
        details.push(ProbeDetail { key: format!("{prefix}Stage"), value: leg.stage.code().into() });
        details.push(ProbeDetail { key: format!("{prefix}Bytes"), value: leg.bytes.to_string() });
    }
    details.push(ProbeDetail { key: "differenceStage".into(), value: divergence.into() });
    ProbeResult {
        probe_type: "throughput_window".into(),
        target: target.label.clone(),
        outcome: if incomplete {
            "http3_transfer_comparison_inconclusive"
        } else {
            "http3_transfer_comparison_recorded"
        }
        .into(),
        details,
    }
}
