//! `ProbeStageRegistration` — descriptor/factory platform for the scheduled
//! connectivity stages.
//!
//! Pairs each connectivity stage's descriptor metadata with the engine-side
//! scheduling metadata (`ExecutionStageId`, optional `ProbeTaskFamily`
//! selector) and the runner factory. Two central edits previously needed for
//! every new connectivity probe — the [`super::execution_coordinator`]
//! runners vector and the [`crate::engine::plan::connectivity_stage_order`]
//! `match` — now flow from this table.
//!
//! ## Scope
//!
//! The 4 strategy stage runners (`StrategyDnsBaselineRunner`,
//! `StrategyTcpRunner`, `StrategyQuicRunner`,
//! `StrategyRecommendationRunner`) are intentionally NOT registered here:
//! they consume `StrategyCandidateSpec` inputs rather than the
//! `ripdpi_diagnostics_probes::Probe` trait, and the descriptor table in
//! `ripdpi-diagnostics-probes` excludes them on the same principle.
//! [`super::execution_coordinator`] appends them after the registered
//! connectivity runners in their canonical order.
//!
//! ## Why metadata is inlined here
//!
//! `ripdpi-monitor-engine` deliberately does NOT depend on
//! `ripdpi-diagnostics-probes` — the lane-adapter is the seam between the
//! engine and the concrete probe crates, enforced by
//! `scripts/ci/check_native_architecture_contracts.py` (the
//! `monitor_engine_dependency_violations` check). So the registry inlines
//! the descriptor fields a probe stage needs (`runner_name`, `label`,
//! `path_requirement`) rather than reaching into the probes crate for them.
//!
//! Drift between this table and `ripdpi_diagnostics_probes::PROBE_DESCRIPTORS`
//! / `SCHEDULED_PROBE_INVENTORY` is covered transitively:
//!
//! - [`super::parity`] freezes `(runner, phase, artifact_source)` for every
//!   connectivity runner — that pins the `probe_type` (`artifact_source`)
//!   and `runner_name` this registry advertises.
//! - The probes crate pins its descriptor table to its inventory via
//!   `probe_descriptor::tests::every_scheduled_inventory_row_has_one_descriptor`.
//! - The engine-side drift tests below pin this registry to a canonical,
//!   hand-written expected sequence — any reorder fails the test.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::engine::runtime::{ExecutionStageId, ExecutionStageRunner};

use super::connectivity::{
    CircumventionRunner, DnsRunner, EnvironmentRunner, QuicRunner, ServiceRunner, TcpRunner, TelegramRunner,
    ThroughputRunner, WebRunner,
};

/// Factory function for a connectivity stage runner.
///
/// `fn` pointer (not `Box<dyn Fn>`) so that [`PROBE_STAGE_REGISTRATIONS`] can
/// live in `const` context and is trivially `Sync` for use in static tables.
pub(in crate::engine) type ProbeRunnerFactory = fn() -> Box<dyn ExecutionStageRunner + Send + Sync>;

/// One scheduled connectivity stage paired with its runner factory and the
/// descriptor metadata an audit / JSON-export surface needs.
#[derive(Clone)]
pub(in crate::engine) struct ProbeStageRegistration {
    /// The `ProbeResult.probe_type` this stage emits — also the join key
    /// against `ripdpi_diagnostics_probes::PROBE_DESCRIPTORS`. The frozen
    /// `parity.rs` table pins this byte-identical to each runner's
    /// `ARTIFACT_SOURCE` const.
    pub probe_type: &'static str,
    /// The `Probe::id` of the backing [`ripdpi_diagnostics_probes::Probe`]
    /// implementation. Stable telemetry / golden contract; may differ from
    /// `probe_type` (e.g. `quic_probe_offline` vs `quic_reachability`).
    pub probe_id: &'static str,
    /// The engine stage id used in `plan.stage_order` and the coordinator's
    /// runner map.
    pub stage_id: ExecutionStageId,
    /// The probe task family that maps a `ScanRequest::probe_tasks` entry to
    /// this stage. `None` for always-on stages (today: `Environment`) that
    /// are not selectable from user-supplied probe tasks.
    pub task_family_selector: Option<ProbeTaskFamily>,
    /// The runner type name — pinned to `parity.rs`'s frozen
    /// `(runner_name, …)` row for byte identity.
    pub runner_name: &'static str,
    /// Short human-readable label for inventory / debug surfaces.
    pub label: &'static str,
    /// Builds the runner trait object on demand.
    pub make_runner: ProbeRunnerFactory,
}

/// Registered connectivity stages in canonical scheduling order.
///
/// MUST stay byte-identical to the previous hand-rolled order in
/// `execution_coordinator()` and the default branch of
/// `connectivity_stage_order()`. The drift tests pin this.
pub(in crate::engine) const PROBE_STAGE_REGISTRATIONS: &[ProbeStageRegistration] = &[
    ProbeStageRegistration {
        probe_type: "network_environment",
        probe_id: "network_environment_probe",
        stage_id: ExecutionStageId::Environment,
        // Environment is always prepended to the stage order; it is not
        // selectable through `ScanRequest::probe_tasks`.
        task_family_selector: None,
        runner_name: "EnvironmentRunner",
        label: "Network environment",
        make_runner: || Box::new(EnvironmentRunner),
    },
    ProbeStageRegistration {
        probe_type: "dns_integrity",
        probe_id: "dns_integrity_probe",
        stage_id: ExecutionStageId::Dns,
        task_family_selector: Some(ProbeTaskFamily::Dns),
        runner_name: "DnsRunner",
        label: "DNS integrity",
        make_runner: || Box::new(DnsRunner),
    },
    ProbeStageRegistration {
        probe_type: "domain_reachability",
        probe_id: "domain_reachability_probe",
        stage_id: ExecutionStageId::Web,
        task_family_selector: Some(ProbeTaskFamily::Web),
        runner_name: "WebRunner",
        label: "Domain reachability",
        make_runner: || Box::new(WebRunner),
    },
    ProbeStageRegistration {
        probe_type: "quic_reachability",
        probe_id: "quic_probe_offline",
        stage_id: ExecutionStageId::Quic,
        task_family_selector: Some(ProbeTaskFamily::Quic),
        runner_name: "QuicRunner",
        label: "QUIC reachability",
        make_runner: || Box::new(QuicRunner),
    },
    ProbeStageRegistration {
        probe_type: "tcp_fat_header",
        probe_id: "tcp_fat_header_probe",
        stage_id: ExecutionStageId::Tcp,
        task_family_selector: Some(ProbeTaskFamily::Tcp),
        runner_name: "TcpRunner",
        label: "TCP fat header",
        make_runner: || Box::new(TcpRunner),
    },
    ProbeStageRegistration {
        probe_type: "service_reachability",
        probe_id: "service_reachability_probe",
        stage_id: ExecutionStageId::Service,
        task_family_selector: Some(ProbeTaskFamily::Service),
        runner_name: "ServiceRunner",
        label: "Service reachability",
        make_runner: || Box::new(ServiceRunner),
    },
    ProbeStageRegistration {
        probe_type: "circumvention_reachability",
        probe_id: "circumvention_reachability_probe",
        stage_id: ExecutionStageId::Circumvention,
        task_family_selector: Some(ProbeTaskFamily::Circumvention),
        runner_name: "CircumventionRunner",
        label: "Circumvention reachability",
        make_runner: || Box::new(CircumventionRunner),
    },
    ProbeStageRegistration {
        probe_type: "telegram",
        probe_id: "mtproto_reachability_probe",
        stage_id: ExecutionStageId::Telegram,
        task_family_selector: Some(ProbeTaskFamily::Telegram),
        runner_name: "TelegramRunner",
        label: "Telegram reachability",
        make_runner: || Box::new(TelegramRunner),
    },
    ProbeStageRegistration {
        probe_type: "throughput_window",
        probe_id: "throughput_probe",
        stage_id: ExecutionStageId::Throughput,
        task_family_selector: Some(ProbeTaskFamily::Throughput),
        runner_name: "ThroughputRunner",
        label: "Throughput window",
        make_runner: || Box::new(ThroughputRunner),
    },
];

/// Resolve a registration by `ProbeTaskFamily` — the lookup
/// `connectivity_stage_order` performs for each requested probe task.
///
/// Takes the family by reference because `ProbeTaskFamily` is not `Copy`.
pub(in crate::engine) fn registration_for_family(family: &ProbeTaskFamily) -> Option<&'static ProbeStageRegistration> {
    PROBE_STAGE_REGISTRATIONS.iter().find(|reg| reg.task_family_selector.as_ref() == Some(family))
}

/// Render the registered descriptors as a stable JSON array.
///
/// Output is one object per [`PROBE_STAGE_REGISTRATIONS`] entry in
/// registration order, with the fields a diagnostics UI / debug surface
/// needs to enumerate probes. The function is pure (no I/O), allocates only
/// the returned `String`, and is panic-safe — suitable for a future JNI
/// export.
///
/// The output is hand-serialized so that no descriptor type needs a
/// `Serialize` derive and the engine's dependency graph stays minimal.
pub fn probe_descriptors_as_json() -> String {
    // Pre-size for the typical 9-stage table (~150 bytes per entry).
    let mut out = String::with_capacity(64 + PROBE_STAGE_REGISTRATIONS.len() * 160);
    out.push('[');
    for (idx, registration) in PROBE_STAGE_REGISTRATIONS.iter().enumerate() {
        if idx > 0 {
            out.push(',');
        }
        out.push('{');
        push_json_field(&mut out, "probe_id", registration.probe_id, false);
        push_json_field(&mut out, "probe_type", registration.probe_type, true);
        push_json_field(&mut out, "runner", registration.runner_name, true);
        push_json_field(&mut out, "label", registration.label, true);
        out.push('}');
    }
    out.push(']');
    out
}

fn push_json_field(out: &mut String, key: &str, value: &str, leading_comma: bool) {
    if leading_comma {
        out.push(',');
    }
    out.push('"');
    out.push_str(key);
    out.push_str("\":\"");
    push_json_escaped(out, value);
    out.push('"');
}

fn push_json_escaped(out: &mut String, value: &str) {
    for ch in value.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Frozen registry order. Any change here is a behavioral change to the
    /// connectivity scheduling sequence — the surrounding parity tests in
    /// `parity.rs` and the probes-crate's `SCHEDULED_PROBE_INVENTORY` test
    /// will also fail.
    const EXPECTED_REGISTRY_ORDER: &[(&str, &str, &str)] = &[
        ("network_environment", "EnvironmentRunner", "network_environment_probe"),
        ("dns_integrity", "DnsRunner", "dns_integrity_probe"),
        ("domain_reachability", "WebRunner", "domain_reachability_probe"),
        ("quic_reachability", "QuicRunner", "quic_probe_offline"),
        ("tcp_fat_header", "TcpRunner", "tcp_fat_header_probe"),
        ("service_reachability", "ServiceRunner", "service_reachability_probe"),
        ("circumvention_reachability", "CircumventionRunner", "circumvention_reachability_probe"),
        ("telegram", "TelegramRunner", "mtproto_reachability_probe"),
        ("throughput_window", "ThroughputRunner", "throughput_probe"),
    ];

    /// The registry has exactly 9 connectivity stages — the 4 strategy
    /// runners are intentionally excluded.
    #[test]
    fn strategy_runners_remain_out_of_scope() {
        const STRATEGY_RUNNER_NAMES: &[&str] =
            &["StrategyDnsBaselineRunner", "StrategyTcpRunner", "StrategyQuicRunner", "StrategyRecommendationRunner"];
        assert_eq!(PROBE_STAGE_REGISTRATIONS.len(), 9, "registry must cover only the 9 connectivity stages");
        for registration in PROBE_STAGE_REGISTRATIONS {
            assert!(
                !STRATEGY_RUNNER_NAMES.contains(&registration.runner_name),
                "strategy runner {} must not have a registration",
                registration.runner_name,
            );
        }
    }

    /// Registration order is byte-identical to the canonical scheduling
    /// sequence. Drift between this table, `parity.rs`'s frozen table, and
    /// the probes crate's `SCHEDULED_PROBE_INVENTORY` is what the surrounding
    /// tests pin.
    #[test]
    fn registration_order_matches_canonical_sequence() {
        let actual: Vec<(&str, &str, &str)> =
            PROBE_STAGE_REGISTRATIONS.iter().map(|reg| (reg.probe_type, reg.runner_name, reg.probe_id)).collect();
        let expected: Vec<(&str, &str, &str)> = EXPECTED_REGISTRY_ORDER.to_vec();
        assert_eq!(actual, expected, "PROBE_STAGE_REGISTRATIONS drifted from EXPECTED_REGISTRY_ORDER");
    }

    /// Every registration carries non-empty descriptor fields.
    #[test]
    fn every_registration_has_complete_metadata() {
        for registration in PROBE_STAGE_REGISTRATIONS {
            assert!(!registration.probe_type.is_empty(), "empty probe_type");
            assert!(!registration.probe_id.is_empty(), "empty probe_id");
            assert!(!registration.runner_name.is_empty(), "empty runner_name");
            assert!(!registration.label.is_empty(), "empty label");
        }
    }

    /// `probe_type`s are unique — required because `probe_type` is the join
    /// key against `PROBE_DESCRIPTORS`.
    #[test]
    fn probe_types_are_unique() {
        let mut seen: Vec<&str> = Vec::new();
        for registration in PROBE_STAGE_REGISTRATIONS {
            assert!(!seen.contains(&registration.probe_type), "duplicate probe_type: {}", registration.probe_type);
            seen.push(registration.probe_type);
        }
    }

    /// `registration_for_family` round-trips every selectable registration
    /// and returns `None` for `Environment` (which has no family selector).
    #[test]
    fn registration_for_family_resolves_selectable_stages() {
        for registration in PROBE_STAGE_REGISTRATIONS {
            if let Some(family) = registration.task_family_selector.as_ref() {
                let resolved = registration_for_family(family).expect("registration round-trip");
                assert_eq!(resolved.probe_type, registration.probe_type);
            }
        }
        // Environment has no selector → no probe task resolves to it.
        let environment = PROBE_STAGE_REGISTRATIONS
            .iter()
            .find(|reg| reg.probe_type == "network_environment")
            .expect("environment registration");
        assert!(environment.task_family_selector.is_none(), "environment must not be selectable by ProbeTaskFamily");
    }

    /// JSON export is valid and contains one object per registration with
    /// the expected fields.
    #[test]
    fn probe_descriptors_as_json_is_valid_and_complete() {
        let json = probe_descriptors_as_json();
        let parsed: serde_json::Value =
            serde_json::from_str(&json).expect("probe_descriptors_as_json must emit valid JSON");
        let array = parsed.as_array().expect("top level array");
        assert_eq!(array.len(), PROBE_STAGE_REGISTRATIONS.len());
        for (entry, registration) in array.iter().zip(PROBE_STAGE_REGISTRATIONS) {
            let obj = entry.as_object().expect("entry is object");
            assert_eq!(obj["probe_id"], registration.probe_id);
            assert_eq!(obj["probe_type"], registration.probe_type);
            assert_eq!(obj["runner"], registration.runner_name);
            assert_eq!(obj["label"], registration.label);
        }
    }

    /// JSON escaping handles `"`, `\`, and control bytes correctly.
    #[test]
    fn push_json_escaped_handles_quotes_backslashes_and_control_bytes() {
        let mut out = String::new();
        push_json_escaped(&mut out, "a\"b\\c\nd\re\tf\u{1}g");
        assert_eq!(out, "a\\\"b\\\\c\\nd\\re\\tf\\u0001g");
    }
}
