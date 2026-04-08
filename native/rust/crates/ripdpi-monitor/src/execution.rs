use std::net::{SocketAddr, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use rustls::client::danger::ServerCertVerifier;

use ripdpi_packets::{build_realistic_quic_initial, parse_quic_initial, QUIC_V1_VERSION};
use ripdpi_proxy_config::{
    runtime_config_from_ui, ProxyConfigPayload, ProxyRuntimeContext, ProxyUiConfig, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
};
use ripdpi_runtime::{runtime, EmbeddedProxyControl};

use crate::blockpage_fingerprints::{load_fingerprints, BlockpageFingerprint};
use crate::candidates::target_probe_pause_ms;
use crate::candidates::{CandidateWarmup, StrategyCandidateSpec};
use crate::http::{classify_http_response_with_fingerprints, is_blockpage, try_http_request};
use crate::tls::{try_tls_handshake, TlsClientProfile};
use crate::transport::{
    domain_connect_target, quic_connect_target, relay_udp_payload, wait_for_listener, TransportConfig,
};
use crate::types::{DomainTarget, ProbeDetail, ProbeResult, QuicTarget, StrategyProbeCandidateSummary};
use crate::util::{now_ms, stable_probe_hash, CONNECT_TIMEOUT};

use ripdpi_config::RuntimeConfig;

// --- Types ---

#[derive(Debug)]
pub(crate) struct CandidateExecution {
    pub(crate) summary: StrategyProbeCandidateSummary,
    pub(crate) results: Vec<ProbeResult>,
    pub(crate) cancelled: bool,
}

#[derive(Default)]
pub(crate) struct CandidateScore {
    pub(crate) results: Vec<ProbeResult>,
    pub(crate) succeeded_targets: usize,
    pub(crate) total_targets: usize,
    pub(crate) weighted_success_score: usize,
    pub(crate) total_weight: usize,
    pub(crate) quality_score: usize,
    pub(crate) latency_sum_ms: u64,
    pub(crate) latency_count: usize,
}

impl CandidateScore {
    pub(crate) fn add(&mut self, sample: ProbeSample) {
        self.results.push(sample.result);
        self.total_targets += 1;
        self.total_weight += sample.weight;
        self.quality_score += sample.quality * sample.weight;
        if sample.success {
            self.succeeded_targets += 1;
            self.weighted_success_score += sample.weight;
            self.latency_sum_ms += sample.latency_ms;
            self.latency_count += 1;
        }
    }

    pub(crate) fn average_latency_ms(&self) -> Option<u64> {
        (self.latency_count > 0).then(|| self.latency_sum_ms / self.latency_count as u64)
    }

    pub(crate) fn is_full_success(&self) -> bool {
        self.total_targets > 0 && self.succeeded_targets == self.total_targets
    }
}

pub(crate) struct ProbeSample {
    pub(crate) result: ProbeResult,
    pub(crate) success: bool,
    pub(crate) weight: usize,
    pub(crate) quality: usize,
    pub(crate) latency_ms: u64,
}

pub(crate) struct TemporaryProxyRuntime {
    pub(crate) addr: SocketAddr,
    pub(crate) control: Arc<EmbeddedProxyControl>,
    pub(crate) handle: Option<JoinHandle<Result<(), String>>>,
}

impl TemporaryProxyRuntime {
    pub(crate) fn start(config: RuntimeConfig, runtime_context: Option<ProxyRuntimeContext>) -> Result<Self, String> {
        let listener = runtime::create_listener(&config).map_err(|err| err.to_string())?;
        let addr = listener.local_addr().map_err(|err| err.to_string())?;
        let control = Arc::new(EmbeddedProxyControl::new_with_context(None, runtime_context));
        let worker_control = control.clone();
        let handle = thread::spawn(move || {
            runtime::run_proxy_with_embedded_control(config, listener, worker_control).map_err(|err| err.to_string())
        });
        wait_for_listener(addr)?;
        Ok(Self { addr, control, handle: Some(handle) })
    }

    pub(crate) fn transport(&self) -> TransportConfig {
        TransportConfig::Socks5 { host: "127.0.0.1".to_string(), port: self.addr.port() }
    }
}

impl Drop for TemporaryProxyRuntime {
    fn drop(&mut self) {
        self.control.request_shutdown();
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

static BLOCKPAGE_FINGERPRINTS: LazyLock<Vec<BlockpageFingerprint>> = LazyLock::new(load_fingerprints);

// --- Functions ---

pub(crate) fn execute_tcp_candidate(
    spec: &StrategyCandidateSpec,
    targets: &[DomainTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
    probe_seed: u64,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
) -> CandidateExecution {
    if targets.is_empty() {
        return not_applicable_candidate_execution(spec, 0, 3, "No HTTP or HTTPS targets configured");
    }
    let probe_started = std::time::Instant::now();
    match probe_runtime_transport(spec, runtime_context) {
        Ok(runtime) => {
            let transport = runtime.transport();
            run_candidate_warmup(spec, &transport, targets, tls_verifier);
            if cancel.load(Ordering::Acquire) {
                drop(runtime);
                return cancelled_candidate_execution(spec, CandidateScore::default(), 3);
            }
            let mut score = CandidateScore::default();
            let mut ordered_targets = targets.to_vec();
            ordered_targets
                .sort_by_key(|target| stable_probe_hash(stable_probe_hash(probe_seed, spec.id), &target.host));

            // Test domains in parallel batches to reduce per-candidate probe time.
            // Batch size of 3 keeps concurrency safe (different destinations, no DPI
            // state collision) while cutting wall-clock time from ~15-20s to ~6-8s.
            const PARALLEL_DOMAIN_BATCH_SIZE: usize = 3;
            let chunks: Vec<&[DomainTarget]> = ordered_targets.chunks(PARALLEL_DOMAIN_BATCH_SIZE).collect();
            for (chunk_index, chunk) in chunks.iter().enumerate() {
                if cancel.load(Ordering::Acquire) {
                    drop(runtime);
                    return cancelled_candidate_execution(spec, score, 3);
                }
                if chunk_index > 0 {
                    // Inter-chunk pause: use the first target in the chunk as the key.
                    thread::sleep(Duration::from_millis(target_probe_pause_ms(probe_seed, spec, &chunk[0].host)));
                }
                // Run HTTP + HTTPS for each domain in this chunk concurrently.
                let chunk_results: Vec<Vec<ProbeSample>> = thread::scope(|s| {
                    chunk
                        .iter()
                        .map(|target| {
                            let transport = transport.clone();
                            s.spawn(move || {
                                let samples = vec![
                                    run_http_strategy_probe(&transport, target, spec),
                                    run_https_strategy_probe(&transport, target, spec, tls_verifier),
                                ];
                                samples
                            })
                        })
                        .collect::<Vec<_>>()
                        .into_iter()
                        .map(|handle| handle.join().unwrap_or_default())
                        .collect()
                });
                for samples in chunk_results {
                    for sample in samples {
                        score.add(sample);
                    }
                }
                if cancel.load(Ordering::Acquire) {
                    drop(runtime);
                    return cancelled_candidate_execution(spec, score, 3);
                }
            }
            drop(runtime);
            let candidate_id = spec.id.to_string();
            metrics::histogram!(
                "ripdpi_strategy_probe_duration_seconds",
                "candidate_id" => candidate_id,
                "family" => "tcp",
            )
            .record(probe_started.elapsed().as_secs_f64());
            build_candidate_execution(spec, score, 3)
        }
        Err(err) => failed_candidate_execution(spec, targets.len() * 2, 3, err),
    }
}

pub(crate) fn execute_quic_candidate(
    spec: &StrategyCandidateSpec,
    targets: &[QuicTarget],
    runtime_context: Option<&ProxyRuntimeContext>,
    probe_seed: u64,
    cancel: &AtomicBool,
) -> CandidateExecution {
    if targets.is_empty() {
        return not_applicable_candidate_execution(spec, 0, 2, "No QUIC targets configured");
    }
    let probe_started = std::time::Instant::now();
    match probe_runtime_transport(spec, runtime_context) {
        Ok(runtime) => {
            let transport = runtime.transport();
            let mut score = CandidateScore::default();
            let mut ordered_targets = targets.to_vec();
            ordered_targets
                .sort_by_key(|target| stable_probe_hash(stable_probe_hash(probe_seed, spec.id), &target.host));
            for (index, target) in ordered_targets.iter().enumerate() {
                if cancel.load(Ordering::Acquire) {
                    drop(runtime);
                    return cancelled_candidate_execution(spec, score, 2);
                }
                if index > 0 {
                    thread::sleep(Duration::from_millis(target_probe_pause_ms(probe_seed, spec, &target.host)));
                }
                score.add(run_quic_strategy_probe(&transport, target, spec));
            }
            drop(runtime);
            let candidate_id = spec.id.to_string();
            metrics::histogram!(
                "ripdpi_strategy_probe_duration_seconds",
                "candidate_id" => candidate_id,
                "family" => "quic",
            )
            .record(probe_started.elapsed().as_secs_f64());
            build_candidate_execution(spec, score, 2)
        }
        Err(err) => failed_candidate_execution(spec, targets.len(), 2, err),
    }
}

/// Compute adaptive connect timeout based on observed control RTT.
/// Uses max(MIN_ADAPTIVE_TIMEOUT, control_rtt * RTT_MULTIPLIER) capped at CONNECT_TIMEOUT.
/// Currently a building block for future per-candidate timeout tuning.
#[allow(dead_code)]
pub(crate) fn adaptive_connect_timeout(control_rtt_ms: Option<u64>) -> Duration {
    const MIN_ADAPTIVE_TIMEOUT: Duration = Duration::from_millis(1500);
    const RTT_MULTIPLIER: u64 = 15;

    match control_rtt_ms {
        Some(rtt) if rtt > 0 => {
            let scaled = Duration::from_millis(rtt * RTT_MULTIPLIER);
            scaled.max(MIN_ADAPTIVE_TIMEOUT).min(CONNECT_TIMEOUT)
        }
        _ => CONNECT_TIMEOUT,
    }
}

pub(crate) fn probe_runtime_transport(
    spec: &StrategyCandidateSpec,
    runtime_context: Option<&ProxyRuntimeContext>,
) -> Result<TemporaryProxyRuntime, String> {
    let mut runtime_config = spec.config.clone();
    runtime_config.listen.ip = "127.0.0.1".to_string();
    runtime_config.host_autolearn.enabled = false;
    runtime_config.host_autolearn.store_path = None;
    if !spec.preserve_adaptive_fake_ttl {
        freeze_adaptive_fake_ttl_for_probe(&mut runtime_config);
    }
    let mut config = runtime_config_from_ui(runtime_config).map_err(|err| {
        tracing::warn!(candidate = spec.id, error = %err, "probe runtime config validation failed");
        err.to_string()
    })?;
    let _ = ripdpi_proxy_config::presets::apply_runtime_preset("ripdpi_default", &mut config);
    config.network.listen.listen_port = 0;
    if let Some(ctx) = runtime_context {
        if let Some(ref path) = ctx.protect_path {
            config.process.protect_path = Some(path.clone());
        }
    }
    match TemporaryProxyRuntime::start(config, runtime_context.cloned()) {
        Ok(runtime) => {
            tracing::debug!(candidate = spec.id, addr = %runtime.addr, "probe runtime started");
            Ok(runtime)
        }
        Err(err) => {
            tracing::warn!(candidate = spec.id, error = %err, "probe runtime failed to start");
            Err(err)
        }
    }
}

pub(crate) fn run_candidate_warmup(
    spec: &StrategyCandidateSpec,
    transport: &TransportConfig,
    targets: &[DomainTarget],
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) {
    if spec.warmup != CandidateWarmup::AdaptiveFakeTtl {
        return;
    }
    for target in targets {
        let http_port = target.http_port.unwrap_or(80);
        let https_port = target.https_port.unwrap_or(443);
        let _ = try_http_request(
            &domain_connect_target(target),
            http_port,
            transport,
            &target.host,
            &target.http_path,
            false,
        );
        let _ = try_tls_handshake(
            &domain_connect_target(target),
            https_port,
            transport,
            &target.host,
            true,
            TlsClientProfile::Tls13Only,
            tls_verifier,
        );
    }
}

pub(crate) fn freeze_adaptive_fake_ttl_for_probe(runtime_config: &mut ProxyUiConfig) {
    if !runtime_config.fake_packets.adaptive_fake_ttl_enabled {
        return;
    }
    let min_ttl = runtime_config.fake_packets.adaptive_fake_ttl_min.clamp(1, 255);
    let max_ttl = runtime_config.fake_packets.adaptive_fake_ttl_max.clamp(min_ttl, 255);
    let fallback = if runtime_config.fake_packets.adaptive_fake_ttl_fallback > 0 {
        runtime_config.fake_packets.adaptive_fake_ttl_fallback
    } else if runtime_config.fake_packets.fake_ttl > 0 {
        runtime_config.fake_packets.fake_ttl
    } else {
        ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK
    };
    runtime_config.fake_packets.fake_ttl = fallback.clamp(min_ttl, max_ttl);
    runtime_config.fake_packets.adaptive_fake_ttl_enabled = false;
}

pub(crate) fn build_candidate_execution(
    spec: &StrategyCandidateSpec,
    score: CandidateScore,
    quality_floor: usize,
) -> CandidateExecution {
    let outcome = if score.is_full_success() {
        "success"
    } else if score.succeeded_targets > 0 && score.quality_score >= quality_floor {
        "partial"
    } else {
        "failed"
    };
    let rationale = format!("{} of {} targets succeeded", score.succeeded_targets, score.total_targets);
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            outcome: outcome.to_string(),
            rationale,
            succeeded_targets: score.succeeded_targets,
            total_targets: score.total_targets,
            weighted_success_score: score.weighted_success_score,
            total_weight: score.total_weight,
            quality_score: score.quality_score,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[]),
            average_latency_ms: score.average_latency_ms(),
            skipped: false,
        },
        results: score.results,
        cancelled: false,
    }
}

fn cancelled_candidate_execution(
    spec: &StrategyCandidateSpec,
    score: CandidateScore,
    quality_floor: usize,
) -> CandidateExecution {
    let mut execution = build_candidate_execution(spec, score, quality_floor);
    execution.cancelled = true;
    execution
}

pub(crate) fn failed_candidate_execution(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    err: String,
) -> CandidateExecution {
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            outcome: "failed".to_string(),
            rationale: err,
            succeeded_targets: 0,
            total_targets,
            weighted_success_score: 0,
            total_weight: total_targets * total_weight_per_target,
            quality_score: 0,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[]),
            average_latency_ms: None,
            skipped: false,
        },
        results: Vec::new(),
        cancelled: false,
    }
}

pub(crate) fn not_applicable_candidate_execution(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    rationale: &str,
) -> CandidateExecution {
    CandidateExecution {
        summary: StrategyProbeCandidateSummary {
            id: spec.id.to_string(),
            label: spec.label.to_string(),
            family: spec.family.to_string(),
            outcome: "not_applicable".to_string(),
            rationale: rationale.to_string(),
            succeeded_targets: 0,
            total_targets,
            weighted_success_score: 0,
            total_weight: total_targets * total_weight_per_target,
            quality_score: 0,
            proxy_config_json: candidate_proxy_config_json(spec),
            notes: candidate_notes(spec, &[rationale]),
            average_latency_ms: None,
            skipped: false,
        },
        results: Vec::new(),
        cancelled: false,
    }
}

pub(crate) fn skipped_candidate_summary(
    spec: &StrategyCandidateSpec,
    total_targets: usize,
    total_weight_per_target: usize,
    rationale: &str,
) -> StrategyProbeCandidateSummary {
    StrategyProbeCandidateSummary {
        id: spec.id.to_string(),
        label: spec.label.to_string(),
        family: spec.family.to_string(),
        outcome: "skipped".to_string(),
        rationale: rationale.to_string(),
        succeeded_targets: 0,
        total_targets,
        weighted_success_score: 0,
        total_weight: total_targets * total_weight_per_target,
        quality_score: 0,
        proxy_config_json: candidate_proxy_config_json(spec),
        notes: candidate_notes(spec, &[rationale]),
        average_latency_ms: None,
        skipped: true,
    }
}

pub(crate) fn eliminated_candidate_summary(
    spec: &StrategyCandidateSpec,
    qualifier_succeeded: usize,
    qualifier_total: usize,
    total_weight_per_target: usize,
) -> StrategyProbeCandidateSummary {
    let rationale = format!("Eliminated in qualifier: {qualifier_succeeded}/{qualifier_total} succeeded");
    StrategyProbeCandidateSummary {
        id: spec.id.to_string(),
        label: spec.label.to_string(),
        family: spec.family.to_string(),
        outcome: "eliminated".to_string(),
        rationale: rationale.clone(),
        succeeded_targets: qualifier_succeeded,
        total_targets: qualifier_total,
        weighted_success_score: 0,
        total_weight: qualifier_total * total_weight_per_target,
        quality_score: 0,
        proxy_config_json: candidate_proxy_config_json(spec),
        notes: candidate_notes(spec, &[&rationale]),
        average_latency_ms: None,
        skipped: false,
    }
}

pub(crate) fn candidate_proxy_config_json(spec: &StrategyCandidateSpec) -> Option<String> {
    serde_json::to_string(&ProxyConfigPayload::Ui {
        strategy_preset: None,
        config: spec.config.clone(),
        runtime_context: None,
        log_context: None,
    })
    .ok()
}

pub(crate) fn candidate_notes(spec: &StrategyCandidateSpec, extra_notes: &[&str]) -> Vec<String> {
    spec.notes.iter().copied().chain(extra_notes.iter().copied()).map(str::to_string).collect()
}

pub(crate) fn winning_candidate_index(candidates: &[StrategyProbeCandidateSummary]) -> Option<usize> {
    candidates
        .iter()
        .enumerate()
        .filter(|(_, candidate)| !candidate.skipped && candidate.outcome != "not_applicable")
        .max_by_key(|(index, candidate)| {
            (
                candidate.weighted_success_score,
                candidate.quality_score,
                std::cmp::Reverse(candidate.average_latency_ms.unwrap_or(u64::MAX)),
                std::cmp::Reverse(*index),
            )
        })
        .map(|(index, _)| index)
}

pub(crate) fn run_http_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = now_ms();
    let http_port = target.http_port.unwrap_or(80);
    let observation =
        try_http_request(&domain_connect_target(target), http_port, transport, &target.host, &target.http_path, false);
    let latency_ms = now_ms().saturating_sub(started);
    // Try fingerprint-based classification first, then fall back to heuristics.
    let (outcome, fingerprint_name) = if let Some(response) = &observation.response {
        let (fp_outcome, fp_name) = classify_http_response_with_fingerprints(response, &BLOCKPAGE_FINGERPRINTS);
        let outcome = if fp_name.is_some() {
            fp_outcome
        } else if is_blockpage(&observation) {
            "http_blockpage".to_string()
        } else if observation.status == "http_ok" {
            "http_ok".to_string()
        } else if observation.status.starts_with("http_status_3") {
            "http_redirect".to_string()
        } else if observation.error.is_some() {
            "http_unreachable".to_string()
        } else {
            observation.status.clone()
        };
        (outcome, fp_name)
    } else if observation.error.is_some() {
        ("http_unreachable".to_string(), None)
    } else {
        (observation.status.clone(), None)
    };
    let h3_advertised =
        observation.response.as_ref().and_then(|r| r.headers.get("alt-svc")).is_some_and(|v| v.contains("h3"));
    let mut details = vec![
        ProbeDetail { key: "candidateId".to_string(), value: candidate.id.to_string() },
        ProbeDetail { key: "candidateLabel".to_string(), value: candidate.label.to_string() },
        ProbeDetail { key: "candidateFamily".to_string(), value: candidate.family.to_string() },
        ProbeDetail { key: "protocol".to_string(), value: "HTTP".to_string() },
        ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
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
    ];
    if let Some(fp) = &fingerprint_name {
        details.push(ProbeDetail { key: "blockpageFingerprint".to_string(), value: fp.clone() });
    }
    details.push(ProbeDetail { key: "h3Advertised".to_string(), value: h3_advertised.to_string() });
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_http".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details,
        },
        success: outcome == "http_ok" || outcome == "http_redirect",
        weight: 1,
        quality: if outcome == "http_ok" {
            3
        } else if outcome == "http_redirect" {
            2
        } else if outcome == "http_blockpage" {
            1
        } else {
            0
        },
        latency_ms,
    }
}

pub(crate) fn run_https_strategy_probe(
    transport: &TransportConfig,
    target: &DomainTarget,
    candidate: &StrategyCandidateSpec,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> ProbeSample {
    let started = now_ms();
    let https_port = target.https_port.unwrap_or(443);
    let tls13 = try_tls_handshake(
        &domain_connect_target(target),
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13Only,
        tls_verifier,
    );
    let tls12 = try_tls_handshake(
        &domain_connect_target(target),
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls12Only,
        tls_verifier,
    );
    let tls_ech = try_tls_handshake(
        &domain_connect_target(target),
        https_port,
        transport,
        &target.host,
        true,
        TlsClientProfile::Tls13WithEch,
        tls_verifier,
    );
    let latency_ms = now_ms().saturating_sub(started);
    let outcome = if tls13.certificate_anomaly || tls12.certificate_anomaly {
        "tls_cert_invalid".to_string()
    } else if tls13.status == "tls_ok" && tls12.status == "tls_ok" {
        "tls_ok".to_string()
    } else if tls13.status == "tls_ok" || tls12.status == "tls_ok" {
        "tls_version_split".to_string()
    } else if tls_ech.status == "tls_ok" {
        "tls_ech_only".to_string()
    } else {
        "tls_handshake_failed".to_string()
    };
    // Pick timing and cert info from the preferred successful observation (tls13 first).
    let preferred = if tls13.tcp_connect_ms.is_some() { &tls13 } else { &tls12 };
    let tcp_connect_ms = preferred.tcp_connect_ms;
    let tls_handshake_ms = preferred.tls_handshake_ms;
    let cert_chain_length = preferred.cert_chain_length.or(tls12.cert_chain_length);
    let cert_issuer = preferred.cert_issuer.clone().or_else(|| tls12.cert_issuer.clone());
    let observed_server_ttl = preferred.observed_server_ttl;
    let estimated_hop_count = preferred.estimated_hop_count;
    let ja3_fingerprint = preferred.ja3_fingerprint.clone().or_else(|| tls12.ja3_fingerprint.clone());

    let mut details = vec![
        ProbeDetail { key: "candidateId".to_string(), value: candidate.id.to_string() },
        ProbeDetail { key: "candidateLabel".to_string(), value: candidate.label.to_string() },
        ProbeDetail { key: "candidateFamily".to_string(), value: candidate.family.to_string() },
        ProbeDetail { key: "protocol".to_string(), value: "HTTPS".to_string() },
        ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
        ProbeDetail { key: "tls13Status".to_string(), value: tls13.status },
        ProbeDetail { key: "tls12Status".to_string(), value: tls12.status },
        ProbeDetail { key: "tlsEchStatus".to_string(), value: tls_ech.status },
        ProbeDetail {
            key: "tlsEchVersion".to_string(),
            value: tls_ech.version.unwrap_or_else(|| "unknown".to_string()),
        },
        ProbeDetail {
            key: "tlsEchError".to_string(),
            value: tls_ech.error.clone().unwrap_or_else(|| "none".to_string()),
        },
        ProbeDetail {
            key: "tlsEchResolutionDetail".to_string(),
            value: tls_ech.ech_resolution_detail.unwrap_or_else(|| "none".to_string()),
        },
        ProbeDetail {
            key: "tlsError".to_string(),
            value: tls13.error.or(tls12.error).or(tls_ech.error).unwrap_or_else(|| "none".to_string()),
        },
    ];

    if let Some(ms) = tcp_connect_ms {
        details.push(ProbeDetail { key: "tcpConnectMs".to_string(), value: ms.to_string() });
    }
    if let Some(ms) = tls_handshake_ms {
        details.push(ProbeDetail { key: "tlsHandshakeMs".to_string(), value: ms.to_string() });
    }
    if let Some(len) = cert_chain_length {
        details.push(ProbeDetail { key: "tlsCertChainLength".to_string(), value: len.to_string() });
    }
    if let Some(issuer) = cert_issuer {
        details.push(ProbeDetail { key: "tlsCertIssuer".to_string(), value: issuer });
    }
    if let Some(ttl) = observed_server_ttl {
        details.push(ProbeDetail { key: "observedServerTtl".to_string(), value: ttl.to_string() });
    }
    if let Some(hops) = estimated_hop_count {
        details.push(ProbeDetail { key: "estimatedHopCount".to_string(), value: hops.to_string() });
    }
    if let Some(ja3) = ja3_fingerprint {
        details.push(ProbeDetail { key: "ja3Fingerprint".to_string(), value: ja3 });
    }

    // On total TLS failure, perform a single retry to distinguish consistent
    // blocking from intermittent failures.
    let (retry_count, final_outcome) = if outcome == "tls_handshake_failed" {
        let retry = try_tls_handshake(
            &domain_connect_target(target),
            https_port,
            transport,
            &target.host,
            true,
            TlsClientProfile::Tls13Only,
            tls_verifier,
        );
        let retry_outcome = if retry.status == "tls_ok" { "tls_ok" } else { "tls_handshake_failed" };
        details.push(ProbeDetail { key: "retryOutcome".to_string(), value: retry_outcome.to_string() });
        details.push(ProbeDetail {
            key: "retryError".to_string(),
            value: retry.error.unwrap_or_else(|| "none".to_string()),
        });
        // If the retry succeeded, upgrade the overall outcome.
        let upgraded = if retry_outcome == "tls_ok" { "tls_ok".to_string() } else { outcome.clone() };
        (1_usize, upgraded)
    } else {
        (0, outcome.clone())
    };
    details.push(ProbeDetail { key: "probeRetryCount".to_string(), value: retry_count.to_string() });

    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_https".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: final_outcome.clone(),
            details,
        },
        success: matches!(final_outcome.as_str(), "tls_ok" | "tls_version_split"),
        weight: 2,
        quality: match final_outcome.as_str() {
            "tls_ok" => 4,
            "tls_version_split" => 3,
            _ => 0,
        },
        latency_ms,
    }
}

pub(crate) fn run_quic_strategy_probe(
    transport: &TransportConfig,
    target: &QuicTarget,
    candidate: &StrategyCandidateSpec,
) -> ProbeSample {
    let started = now_ms();
    let connect_target = quic_connect_target(target);
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some(target.host.as_str())).unwrap_or_default();
    let response = relay_udp_payload(&connect_target, target.port, transport, &payload);
    let latency_ms = now_ms().saturating_sub(started);
    let (outcome, status, error) = match response {
        Ok(bytes) if parse_quic_initial(&bytes).is_some() => {
            ("quic_initial_response".to_string(), "quic_initial_response".to_string(), "none".to_string())
        }
        Ok(bytes) if !bytes.is_empty() => {
            ("quic_response".to_string(), "quic_response".to_string(), "none".to_string())
        }
        Ok(_) => ("quic_empty".to_string(), "quic_empty".to_string(), "none".to_string()),
        Err(err) => ("quic_error".to_string(), "quic_error".to_string(), err),
    };
    ProbeSample {
        result: ProbeResult {
            probe_type: "strategy_quic".to_string(),
            target: format!("{} · {}", candidate.label, target.host),
            outcome: outcome.clone(),
            details: vec![
                ProbeDetail { key: "candidateId".to_string(), value: candidate.id.to_string() },
                ProbeDetail { key: "candidateLabel".to_string(), value: candidate.label.to_string() },
                ProbeDetail { key: "candidateFamily".to_string(), value: candidate.family.to_string() },
                ProbeDetail { key: "protocol".to_string(), value: "QUIC".to_string() },
                ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
                ProbeDetail { key: "port".to_string(), value: target.port.to_string() },
                ProbeDetail { key: "status".to_string(), value: status },
                ProbeDetail { key: "error".to_string(), value: error },
            ],
        },
        success: matches!(outcome.as_str(), "quic_initial_response" | "quic_response"),
        weight: 2,
        quality: match outcome.as_str() {
            "quic_initial_response" => 4,
            "quic_response" => 3,
            _ => 0,
        },
        latency_ms,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn winning_candidate_index_selects_highest_weighted_success_score() {
        let candidates = vec![
            summary_with("a", 2, 4, 3, false, None),
            summary_with("b", 4, 4, 6, false, None),
            summary_with("c", 3, 4, 5, false, None),
        ];

        assert_eq!(winning_candidate_index(&candidates), Some(1));
    }

    #[test]
    fn winning_candidate_index_breaks_tie_with_quality_score() {
        let candidates = vec![summary_with("a", 3, 4, 5, false, None), summary_with("b", 3, 4, 8, false, None)];

        assert_eq!(winning_candidate_index(&candidates), Some(1));
    }

    #[test]
    fn winning_candidate_index_skips_skipped_candidates() {
        let candidates = vec![
            summary_with("a", 2, 4, 3, false, None),
            summary_with("b", 10, 10, 20, true, None),
            summary_with("c", 3, 4, 5, false, None),
        ];

        assert_eq!(winning_candidate_index(&candidates), Some(2));
    }

    #[test]
    fn winning_candidate_index_skips_not_applicable_candidates() {
        let mut candidates = vec![summary_with("a", 2, 4, 3, false, None), summary_with("b", 10, 10, 20, false, None)];
        candidates[1].outcome = "not_applicable".to_string();

        assert_eq!(winning_candidate_index(&candidates), Some(0));
    }

    #[test]
    fn winning_candidate_index_returns_none_for_empty_list() {
        assert_eq!(winning_candidate_index(&[]), None);
    }

    #[test]
    fn winning_candidate_index_prefers_lower_latency_on_tie() {
        let candidates =
            vec![summary_with("a", 3, 4, 5, false, Some(200)), summary_with("b", 3, 4, 5, false, Some(100))];

        assert_eq!(winning_candidate_index(&candidates), Some(1));
    }

    #[test]
    fn candidate_score_add_accumulates_weighted_success() {
        let mut score = CandidateScore::default();
        score.add(ProbeSample {
            result: ProbeResult {
                probe_type: "test".to_string(),
                target: "t".to_string(),
                outcome: "ok".to_string(),
                details: vec![],
            },
            success: true,
            weight: 2,
            quality: 4,
            latency_ms: 50,
        });
        score.add(ProbeSample {
            result: ProbeResult {
                probe_type: "test".to_string(),
                target: "t".to_string(),
                outcome: "fail".to_string(),
                details: vec![],
            },
            success: false,
            weight: 1,
            quality: 0,
            latency_ms: 100,
        });

        assert_eq!(score.succeeded_targets, 1);
        assert_eq!(score.total_targets, 2);
        assert_eq!(score.weighted_success_score, 2);
        assert_eq!(score.total_weight, 3);
        assert_eq!(score.quality_score, 8); // 4*2 + 0*1
        assert_eq!(score.average_latency_ms(), Some(50));
        assert!(!score.is_full_success());
    }

    #[test]
    fn candidate_score_full_success_when_all_targets_succeed() {
        let mut score = CandidateScore::default();
        score.add(ProbeSample {
            result: ProbeResult {
                probe_type: "test".to_string(),
                target: "t".to_string(),
                outcome: "ok".to_string(),
                details: vec![],
            },
            success: true,
            weight: 1,
            quality: 3,
            latency_ms: 100,
        });

        assert!(score.is_full_success());
    }

    #[test]
    fn candidate_score_average_latency_none_when_no_success() {
        let score = CandidateScore::default();
        assert_eq!(score.average_latency_ms(), None);
    }

    #[test]
    fn freeze_adaptive_fake_ttl_clamps_fallback_to_range() {
        let mut config = test_ui_config();
        config.fake_packets.fake_ttl = 11;
        config.fake_packets.adaptive_fake_ttl_enabled = true;
        config.fake_packets.adaptive_fake_ttl_min = 3;
        config.fake_packets.adaptive_fake_ttl_max = 9;
        config.fake_packets.adaptive_fake_ttl_fallback = 13;

        freeze_adaptive_fake_ttl_for_probe(&mut config);

        assert_eq!(config.fake_packets.fake_ttl, 9);
        assert!(!config.fake_packets.adaptive_fake_ttl_enabled);
    }

    #[test]
    fn freeze_adaptive_fake_ttl_uses_fake_ttl_when_fallback_is_zero() {
        let mut config = test_ui_config();
        config.fake_packets.fake_ttl = 7;
        config.fake_packets.adaptive_fake_ttl_enabled = true;
        config.fake_packets.adaptive_fake_ttl_min = 3;
        config.fake_packets.adaptive_fake_ttl_max = 12;
        config.fake_packets.adaptive_fake_ttl_fallback = 0;

        freeze_adaptive_fake_ttl_for_probe(&mut config);

        assert_eq!(config.fake_packets.fake_ttl, 7);
        assert!(!config.fake_packets.adaptive_fake_ttl_enabled);
    }

    #[test]
    fn freeze_adaptive_fake_ttl_noop_when_disabled() {
        let mut config = test_ui_config();
        config.fake_packets.fake_ttl = 8;
        config.fake_packets.adaptive_fake_ttl_enabled = false;

        freeze_adaptive_fake_ttl_for_probe(&mut config);

        assert_eq!(config.fake_packets.fake_ttl, 8);
    }

    #[test]
    fn not_applicable_candidate_execution_keeps_ech_notes_and_rationale() {
        let spec = crate::candidates::build_tcp_candidates(&test_ui_config())
            .into_iter()
            .find(|candidate| candidate.id == "ech_split")
            .expect("ech_split candidate");

        let execution =
            not_applicable_candidate_execution(&spec, 4, 3, "No baseline HTTPS target exposed ECH capability");

        assert_eq!(execution.summary.outcome, "not_applicable");
        assert_eq!(execution.summary.rationale, "No baseline HTTPS target exposed ECH capability");
        assert_eq!(execution.summary.total_targets, 4);
        assert_eq!(execution.summary.total_weight, 12);
        assert!(execution
            .summary
            .notes
            .iter()
            .any(|note| note.contains("Runs only when the baseline proves an ECH-capable HTTPS path")));
        assert!(execution.summary.notes.iter().any(|note| note == "No baseline HTTPS target exposed ECH capability"));
    }

    fn summary_with(
        id: &str,
        weighted_success_score: usize,
        total_weight: usize,
        quality_score: usize,
        skipped: bool,
        average_latency_ms: Option<u64>,
    ) -> StrategyProbeCandidateSummary {
        StrategyProbeCandidateSummary {
            id: id.to_string(),
            label: id.to_string(),
            family: "test".to_string(),
            outcome: if skipped { "skipped" } else { "success" }.to_string(),
            rationale: String::new(),
            succeeded_targets: weighted_success_score,
            total_targets: total_weight,
            weighted_success_score,
            total_weight,
            quality_score,
            proxy_config_json: None,
            notes: vec![],
            average_latency_ms,
            skipped,
        }
    }

    fn test_ui_config() -> ProxyUiConfig {
        let mut config = ProxyUiConfig::default();
        config.protocols.desync_udp = true;
        config.chains.tcp_steps = vec![];
        config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
        config
    }

    #[test]
    fn probe_runtime_transport_binds_ephemeral_port() {
        let spec = crate::candidates::candidate_spec("test", "Test", "test", test_ui_config());
        let runtime = probe_runtime_transport(&spec, None).expect("probe runtime should start with ephemeral port");
        assert_ne!(runtime.addr.port(), 0, "OS should assign a non-zero ephemeral port");
    }

    #[test]
    fn probe_runtime_transport_overrides_listen_ip_to_localhost() {
        let mut config = test_ui_config();
        config.listen.ip = "0.0.0.0".to_string();
        let spec = crate::candidates::candidate_spec("test", "Test", "test", config);
        let runtime = probe_runtime_transport(&spec, None).expect("probe runtime should start");
        assert!(runtime.addr.ip().is_loopback(), "probe runtime must bind to localhost");
    }

    // ── Pure logic builder tests ─────────────────────────────────────────

    fn test_spec() -> StrategyCandidateSpec {
        crate::candidates::candidate_spec("test_id", "Test Label", "test_family", test_ui_config())
    }

    #[test]
    fn failed_execution_sets_outcome_and_rationale() {
        let exec = failed_candidate_execution(&test_spec(), 4, 3, "proxy startup failed".to_string());
        assert_eq!(exec.summary.outcome, "failed");
        assert_eq!(exec.summary.rationale, "proxy startup failed");
        assert_eq!(exec.summary.succeeded_targets, 0);
        assert_eq!(exec.summary.total_targets, 4);
        assert_eq!(exec.summary.total_weight, 12);
        assert!(!exec.cancelled);
        assert!(exec.results.is_empty());
    }

    #[test]
    fn cancelled_execution_marks_cancelled_flag() {
        let score = CandidateScore { total_targets: 2, total_weight: 6, ..Default::default() };
        let exec = cancelled_candidate_execution(&test_spec(), score, 0);
        assert!(exec.cancelled);
        assert_eq!(exec.summary.outcome, "failed"); // no succeeded targets
    }

    #[test]
    fn skipped_summary_sets_skipped_flag_and_rationale() {
        let summary = skipped_candidate_summary(&test_spec(), 4, 3, "prerequisite not met");
        assert!(summary.skipped);
        assert_eq!(summary.outcome, "skipped");
        assert_eq!(summary.rationale, "prerequisite not met");
        assert_eq!(summary.total_weight, 12);
        assert!(summary.notes.iter().any(|n| n == "prerequisite not met"));
    }

    #[test]
    fn candidate_proxy_config_json_serializes() {
        let json = candidate_proxy_config_json(&test_spec());
        assert!(json.is_some(), "should produce valid JSON");
        let json_str = json.unwrap();
        // Verify it's valid JSON by parsing it back
        let _: serde_json::Value = serde_json::from_str(&json_str).expect("should be valid JSON");
    }

    #[test]
    fn candidate_notes_collects_extra_notes() {
        let notes = candidate_notes(&test_spec(), &["extra note 1", "extra note 2"]);
        assert!(notes.iter().any(|n| n == "extra note 1"));
        assert!(notes.iter().any(|n| n == "extra note 2"));
    }

    #[test]
    fn candidate_notes_empty_when_no_notes() {
        let spec = crate::candidates::candidate_spec("bare", "Bare", "bare", test_ui_config());
        let notes = candidate_notes(&spec, &[]);
        // spec may have its own notes; just verify no panic and extra is empty
        assert!(notes.iter().all(|n| n != "extra"), "should not contain extras");
    }

    #[test]
    fn build_execution_computes_outcome_success() {
        let mut score = CandidateScore { total_targets: 2, total_weight: 6, ..Default::default() };
        score.succeeded_targets = 2;
        score.weighted_success_score = 6;
        score.quality_score = 10;
        let exec = build_candidate_execution(&test_spec(), score, 0);
        assert_eq!(exec.summary.outcome, "success");
    }

    #[test]
    fn build_execution_computes_outcome_partial() {
        let mut score = CandidateScore { total_targets: 4, total_weight: 12, ..Default::default() };
        score.succeeded_targets = 2;
        score.weighted_success_score = 6;
        score.quality_score = 5;
        let exec = build_candidate_execution(&test_spec(), score, 3);
        assert_eq!(exec.summary.outcome, "partial");
    }

    #[test]
    fn build_execution_computes_outcome_failed() {
        let score = CandidateScore { total_targets: 4, total_weight: 12, ..Default::default() };
        let exec = build_candidate_execution(&test_spec(), score, 0);
        assert_eq!(exec.summary.outcome, "failed");
    }
}
