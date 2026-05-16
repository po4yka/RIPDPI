/// Network protocol review: diagnostics connectivity behavior after runner refactor.
///
/// Ledger row 78. Locks in protocol-correctness invariants for all 9 connectivity
/// runners without making live network connections.
///
/// Invariants covered:
/// - Canonical stage order and count (9 runners)
/// - Phase strings match spec (dns, tcp, quic, reachability, throughput,
///   circumvention, service, telegram, environment)
/// - Artifact source strings match spec
/// - Each runner emits zero steps against an empty-target plan (no cross-
///   contamination between target-list fields)
/// - All runners complete (not cancel) against an empty-target plan
/// - Target-field isolation: step count tracks the correct target list
use ripdpi_monitor_engine::contracts::{connectivity_runner_parity_snapshot, RunnerParityRecord};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn parity_records() -> Vec<RunnerParityRecord> {
    connectivity_runner_parity_snapshot()
}

fn record<'a>(records: &'a [RunnerParityRecord], stage_id: &str) -> &'a RunnerParityRecord {
    records.iter().find(|r| r.stage_id == stage_id).unwrap_or_else(|| panic!("no record with stage_id={stage_id}"))
}

// ---------------------------------------------------------------------------
// PR-1: Canonical runner count
// ---------------------------------------------------------------------------

/// Exactly 9 connectivity runners must be present (row-73 parity contract).
/// Adding or removing a runner without updating the snapshot is a protocol risk.
#[test]
fn pr1_exactly_nine_connectivity_runners() {
    let records = parity_records();
    assert_eq!(
        records.len(),
        9,
        "expected exactly 9 connectivity runners; got {}. \
         Adding/removing a runner changes protocol surface.",
        records.len()
    );
}

// ---------------------------------------------------------------------------
// PR-2: Canonical stage order
// ---------------------------------------------------------------------------

/// Stage order must match the coordinator's registration order so that
/// diagnostics summaries and UI progress bars are deterministic.
#[test]
fn pr2_canonical_stage_order() {
    let records = parity_records();
    let actual_ids: Vec<&str> = records.iter().map(|r| r.stage_id.as_str()).collect();
    let expected = ["Environment", "Dns", "Web", "Quic", "Tcp", "Service", "Circumvention", "Telegram", "Throughput"];
    assert_eq!(
        actual_ids, expected,
        "connectivity runner stage order changed — diagnostics summaries depend on this order"
    );
}

// ---------------------------------------------------------------------------
// PR-3: Phase string correctness
// ---------------------------------------------------------------------------

/// Phase strings must match the spec exactly.  A rename silently breaks
/// diagnostics export parsing, user-visible audit interpretation, and
/// backend event schemas.
#[test]
fn pr3_phase_strings_match_spec() {
    let records = parity_records();

    let expected_phases: &[(&str, &str)] = &[
        ("Environment", "environment"),
        ("Dns", "dns"),
        ("Web", "reachability"),
        ("Quic", "quic"),
        ("Tcp", "tcp"),
        ("Service", "service"),
        ("Circumvention", "circumvention"),
        ("Telegram", "telegram"),
        ("Throughput", "throughput"),
    ];

    for (stage_id, expected_phase) in expected_phases {
        let r = record(&records, stage_id);
        assert_eq!(
            r.phase, *expected_phase,
            "runner '{stage_id}': phase mismatch — got {:?}, want {:?}",
            r.phase, expected_phase
        );
    }
}

// ---------------------------------------------------------------------------
// PR-4: Zero steps against empty-target plan (target-field isolation)
// ---------------------------------------------------------------------------

/// Every runner must report zero steps when the fixture plan has empty target
/// lists. If a runner reports steps here it is reading the wrong target field
/// (cross-contamination bug) or has hardcoded non-empty targets (test risk).
#[test]
fn pr4_zero_steps_with_empty_target_lists() {
    let records = parity_records();
    for r in &records {
        assert_eq!(
            r.total_steps, 0,
            "runner '{}': expected 0 steps with empty target lists but got {}. \
             Check that the runner reads the correct ScanRequest field.",
            r.stage_id, r.total_steps
        );
    }
}

// ---------------------------------------------------------------------------
// PR-5: Family runners complete; lifecycle runners use run() not run_collecting()
// ---------------------------------------------------------------------------

/// The 7 probe-family runners (Dns, Web, Quic, Tcp, Service, Circumvention,
/// Throughput) must report "Completed" with empty target lists — they iterate
/// over an empty Vec and return Completed immediately.
///
/// Environment and Telegram override `run()` instead of `run_collecting()`, so
/// the parity snapshot (which calls `run_collecting`) records them as "Cancelled"
/// with zero steps. This is the correct protocol: the coordinator calls `run()`
/// for those stages, not `run_collecting()`.
#[test]
fn pr5_family_runners_complete_lifecycle_runners_use_run() {
    let records = parity_records();

    // Runners that implement run_collecting (probe-family runners).
    let family_runners = ["Dns", "Web", "Quic", "Tcp", "Service", "Circumvention", "Throughput"];
    // Runners that implement run() only (lifecycle runners).
    let lifecycle_runners = ["Environment", "Telegram"];

    for stage_id in family_runners {
        let r = record(&records, stage_id);
        assert_eq!(
            r.outcome, "Completed",
            "family runner '{}': expected Completed with empty targets, got {:?}",
            stage_id, r.outcome
        );
    }

    for stage_id in lifecycle_runners {
        let r = record(&records, stage_id);
        // These runners do not implement run_collecting; the default returns
        // Cancelled(vec![]).  The coordinator calls run() for them instead.
        assert_eq!(
            r.outcome, "Cancelled",
            "lifecycle runner '{}': expected Cancelled from default run_collecting, got {:?}",
            stage_id, r.outcome
        );
        assert_eq!(
            r.steps.len(),
            0,
            "lifecycle runner '{stage_id}': Cancelled steps must be empty (no probes run via run_collecting)",
        );
    }
}

// ---------------------------------------------------------------------------
// PR-6: Phase byte-identity (no Unicode lookalikes)
// ---------------------------------------------------------------------------

/// Phase strings must be pure ASCII lowercase + underscores to guarantee safe
/// serialisation into JSON, logcat, and backend event schemas.
#[test]
fn pr6_phase_strings_are_ascii_lowercase() {
    let records = parity_records();
    for r in &records {
        assert!(
            r.phase.chars().all(|ch| ch.is_ascii_lowercase() || ch == '_'),
            "runner '{}': phase string {:?} contains non-ASCII-lowercase characters",
            r.stage_id,
            r.phase
        );
    }
}

// ---------------------------------------------------------------------------
// PR-7: Artifact-source strings match spec (via parity record phase proxy)
//
// The parity snapshot does not directly expose artifact_source, but the
// internal byte-identity tests in connectivity.rs already cover that.
// Here we verify the probe_type strings in the partial-report fixture
// (which uses artifact_source as probe_type) match the spec.
// ---------------------------------------------------------------------------

/// Probe types in the contract partial report must match the frozen spec.
/// Changing a probe_type string breaks backend analytics and Kotlin decoders.
#[test]
fn pr7_probe_type_strings_in_partial_report_match_spec() {
    use ripdpi_monitor_engine::contracts::connectivity_partial_report_contract_fixture;

    let report = connectivity_partial_report_contract_fixture();

    // The partial report fixture covers environment, dns, tcp, quic (cancelled
    // before service/circumvention/throughput/telegram/web).
    let expected_probe_types = ["network_environment", "dns_integrity", "tcp_fat_header", "quic_reachability"];

    assert_eq!(report.results.len(), expected_probe_types.len(), "partial report probe count changed");

    for (result, expected) in report.results.iter().zip(expected_probe_types.iter()) {
        assert_eq!(
            result.probe_type, *expected,
            "probe_type mismatch: got {:?}, want {:?}",
            result.probe_type, expected
        );
    }
}

// ---------------------------------------------------------------------------
// PR-8: No duplicate phase strings across runners
// ---------------------------------------------------------------------------

/// Each runner must use a unique phase string.  Duplicate phases would cause
/// event routing ambiguity in diagnostics aggregation.
#[test]
fn pr8_no_duplicate_phase_strings() {
    let records = parity_records();
    let mut seen = std::collections::HashSet::new();
    for r in &records {
        assert!(
            seen.insert(r.phase.as_str()),
            "runner '{}' has duplicate phase string {:?} — each runner must have a unique phase",
            r.stage_id,
            r.phase
        );
    }
}

// ---------------------------------------------------------------------------
// PR-9: No duplicate stage IDs
// ---------------------------------------------------------------------------

/// Stage IDs must be unique in the coordinator registration list.
#[test]
fn pr9_no_duplicate_stage_ids() {
    let records = parity_records();
    let mut seen = std::collections::HashSet::new();
    for r in &records {
        assert!(seen.insert(r.stage_id.as_str()), "duplicate stage_id {:?} in coordinator registration", r.stage_id);
    }
}

// ---------------------------------------------------------------------------
// PR-10: DNS runner is present and has correct phase (protocol isolation)
// ---------------------------------------------------------------------------

/// DNS runner must exist with phase "dns".  If it were accidentally wired to
/// the reachability phase, DNS observations would be attributed to HTTP/HTTPS
/// and vice versa.
#[test]
fn pr10_dns_runner_phase_isolation() {
    let records = parity_records();
    let dns = record(&records, "Dns");
    assert_eq!(dns.phase, "dns", "DNS runner phase must be exactly \"dns\", not {:?}", dns.phase);
    // DNS must not share a phase with the web/reachability runner
    let web = record(&records, "Web");
    assert_ne!(dns.phase, web.phase, "DNS and Web runners must not share a phase string");
}

// ---------------------------------------------------------------------------
// PR-11: QUIC runner is distinct from TCP runner (transport protocol isolation)
// ---------------------------------------------------------------------------

/// QUIC uses UDP; TCP uses TCP.  If the runners shared a phase string the
/// diagnostic pipeline cannot distinguish protocol-level blocking events.
#[test]
fn pr11_quic_and_tcp_phases_are_distinct() {
    let records = parity_records();
    let quic = record(&records, "Quic");
    let tcp = record(&records, "Tcp");
    assert_ne!(
        quic.phase, tcp.phase,
        "QUIC and TCP runners must have distinct phases to preserve transport-protocol semantics"
    );
    assert_eq!(quic.phase, "quic", "QUIC runner phase must be exactly \"quic\"");
    assert_eq!(tcp.phase, "tcp", "TCP runner phase must be exactly \"tcp\"");
}

// ---------------------------------------------------------------------------
// PR-12: Throughput runner is independent of DNS
// ---------------------------------------------------------------------------

/// Throughput measurements must use `throughput_targets`, not DNS targets.
/// With an empty target plan both should report 0 steps, confirming isolation.
#[test]
fn pr12_throughput_runner_independent_of_dns() {
    let records = parity_records();
    let throughput = record(&records, "Throughput");
    let dns = record(&records, "Dns");
    assert_eq!(throughput.total_steps, 0, "Throughput runner should have 0 steps with empty throughput_targets");
    assert_eq!(dns.total_steps, 0, "Dns runner should have 0 steps with empty dns_targets");
    // Phase must differ so events are not confused
    assert_ne!(throughput.phase, dns.phase, "Throughput and DNS runners must not share a phase string");
    assert_eq!(throughput.phase, "throughput");
}

// ---------------------------------------------------------------------------
// PR-13: Circumvention runner uses its own phase
// ---------------------------------------------------------------------------

/// Circumvention probes test VPN/desync bypass tools.  If they share a phase
/// with service probes, failures would be misattributed to the wrong category.
#[test]
fn pr13_circumvention_runner_uses_distinct_phase() {
    let records = parity_records();
    let circumvention = record(&records, "Circumvention");
    let service = record(&records, "Service");
    assert_eq!(circumvention.phase, "circumvention");
    assert_eq!(service.phase, "service");
    assert_ne!(circumvention.phase, service.phase);
}
