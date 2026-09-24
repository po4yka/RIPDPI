# Scan Stages Reference

## Connectivity Scan Stages

These stages execute in order for `ScanKind::Connectivity` scans. The default
order is shown below; when `probe_tasks` are specified, only the stages
matching requested `ProbeTaskFamily` variants are included (deduplicated,
preserving request order).

| Stage ID | Phase | What it checks | Key types | Output |
|----------|-------|---------------|-----------|--------|
| `Environment` | `environment` | Network snapshot: transport type, validation, captive portal detection. Aborts scan early if `transport == "none"`. | `NetworkSnapshot` | `ProbeResult` with `probe_type = "network_environment"`, outcome = transport type (e.g., `wifi`, `cellular`, `none`) |
| `Dns` | `dns` | DNS integrity for each `DnsTarget`. Compares system UDP resolution against encrypted DNS answers. Detects tampering (NXDOMAIN, substitution, injection). | `DnsTarget`, `DnsObservationFact` | `probe_type = "dns_integrity"`, outcomes: `dns_match`, `dns_nxdomain`, `dns_substitution` |
| `Web` | `web` | Domain reachability for each `DomainTarget`. Tests TLS 1.3, TLS 1.2, TLS ECH, HTTP GET. Detects blockpages, certificate anomalies, transport failures. | `DomainTarget`, `DomainObservationFact` | `probe_type = "domain_reachability"`, outcomes: `tls_ok`, `tls_timeout`, `tls_reset`, `tls_cert_invalid`, `blockpage_detected`, etc. |
| `Quic` | `quic` | QUIC reachability for each `QuicTarget`. Tests QUIC handshake completion. | `QuicTarget`, `QuicObservationFact` | `probe_type = "quic_reachability"`, outcomes: `quic_ok`, `quic_timeout`, `quic_blocked` |
| `Tcp` | `tcp` | TCP "fat header" probe for each `TcpTarget`. Tests raw TCP connectivity to known IP:port pairs, checks for SNI-based blocking via whitelist SNI comparison. | `TcpTarget`, `TcpObservationFact` | `probe_type = "tcp_fat_header"`, outcomes: `whitelist_sni_ok`, `whitelist_sni_failed`, `tcp_connect_failed` |
| `Service` | `service` | Service reachability (Telegram, Signal, WhatsApp, Discord). Tests bootstrap URL fetch and TCP endpoint connectivity. | `ServiceTarget`, `ServiceObservationFact` | `probe_type = "service_reachability"`, outcomes: `service_ok`, `service_degraded`, `service_blocked` |
| `Circumvention` | `circumvention` | Circumvention tool reachability (Tor, Psiphon, RiseupVPN). Tests bootstrap URL and handshake host TLS. | `CircumventionTarget`, `CircumventionObservationFact` | `probe_type = "circumvention_reachability"`, outcomes: `circumvention_ok`, `circumvention_blocked` |
| `Telegram` | `telegram` | Telegram-specific deep probe: DC endpoint connectivity, media download, file upload, stall detection, verdict classification. | `TelegramTarget`, `TelegramObservationFact` | `probe_type = "telegram_availability"`, outcomes: `telegram_ok`, `telegram_slow`, `telegram_blocked`, etc. |
| `Throughput` | `throughput` | Throughput measurement for each `ThroughputTarget`. Downloads test files with windowed measurements to detect throttling. | `ThroughputTarget`, `ThroughputObservationFact` | `probe_type = "throughput_window"`, outcomes: `throughput_ok`, `throughput_throttled`, `throughput_failed` |

## Strategy Probe Stages

These stages execute for `ScanKind::StrategyProbe` scans, in the order
`strategy_stage_order()` (`engine/plan.rs`) builds. The order is not a
fixed constant: `Web` is inserted after `Environment` only for in-path
scans with a route and domain targets, and whether TCP or QUIC candidates
run first depends on `confirm_good_dpi_evidence`. Re-read
`strategy_stage_order()` before restating this table.

| Stage ID | Phase | What it checks | Key types | Output |
|----------|-------|---------------|-----------|--------|
| `Environment` | `environment` | Same as connectivity Environment stage. | `NetworkSnapshot` | Network environment probe result |
| `Web` | `web` | Conditional: only when in-path mode with a route and domain targets configured. | `DomainTarget` | Domain reachability results ahead of strategy evaluation |
| `StrategyDnsBaseline` | `dns_baseline` | DNS tampering detection via system vs encrypted DNS comparison. If tampering found, short-circuits the entire scan -- skips candidate evaluation and recommends resolver override. | `StrategyProbeBaseline`, `ClassifiedFailure` | `probe_type = "dns_integrity"` results; sets `baseline_failure` in strategy state |
| `StrategyTcpCandidates` | `tcp` | Evaluates each TCP bypass strategy candidate against the target domain set. For each candidate: configures proxy, probes targets via HTTP/HTTPS, records success/failure. Candidates include baseline, hostfake, fake, split, disorder, OOB, TLS record splitting, etc. | `StrategyCandidateSpec`, `StrategyProbeCandidateSummary` | Per-candidate summary with `succeeded_targets`, `weighted_success_score`, `quality_score`, `proxy_config_json` |
| `StrategyQuicCandidates` | `quic` | Evaluates QUIC bypass strategy candidates (disabled, compat burst, realistic burst, full burst). Same per-target evaluation as TCP but over QUIC. | `StrategyCandidateSpec`, `StrategyProbeCandidateSummary` | Per-candidate QUIC summary |
| `StrategyConnectionConcurrency` | `connection_concurrency` | Opens simultaneous TLS connections to eligible targets across every available TLS profile at increasing concurrency levels to detect concurrency-triggered DPI/middlebox behavior a single-connection probe cannot see. | `ConnectionConcurrencyCell`, `ConnectionConcurrencyVerdict` | `ConnectionConcurrencyAssessment` per target |
| `StrategyRecommendation` | `recommendation` | Selects TCP and QUIC winners by quality score. Builds `StrategyProbeRecommendation` with winning config JSON. Computes `AuditAssessment` with coverage metrics and confidence level. | `StrategyProbeRecommendation`, `StrategyProbeAuditAssessment`, `StrategyProbeReport` | Final `StrategyProbeReport` embedded in `ScanReport` |

## Runner Registration

All runners are instantiated in `engine/runners/mod.rs` via
`execution_coordinator()`: the connectivity runners come from the
`PROBE_STAGE_REGISTRATIONS` descriptor/factory registry in
`engine/runners/registry.rs` (adding a connectivity probe is a one-line
addition there), and the strategy runners (`Strategy*Runner`) are
hand-rolled in `execution_coordinator()` because they need a
`CandidateRuntimeLauncher` constructor argument and consume
`StrategyCandidateSpec` inputs rather than the `Probe` trait. Read both
places for the current runner set instead of relying on a copied list.
The plan determines
which subset runs and in what order. Runners for stages not in
`plan.stage_order` are never invoked.

## Observation Mapping

Each probe result is optionally mapped to a structured `ProbeObservation` via
`observations.rs`. The observation includes an `ObservationKind` discriminator
and a fact struct specific to the probe type (e.g., `DnsObservationFact`,
`DomainObservationFact`). Observations power the classifier
(`DiagnosticsFindingProjector`) which produces `Diagnosis` entries.

The analysis version is tracked as `ENGINE_ANALYSIS_VERSION = "observations_v1"`
and included in the report as `engine_analysis_version`.
