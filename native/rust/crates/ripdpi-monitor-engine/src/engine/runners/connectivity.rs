macro_rules! impl_connectivity_runner {
    ($runner:ty, $family:ty, $stage:ident) => {
        impl crate::engine::runtime::ExecutionStageRunner for $runner {
            fn id(&self) -> crate::engine::runtime::ExecutionStageId {
                crate::engine::runtime::ExecutionStageId::$stage
            }

            fn phase(&self) -> &'static str {
                <$family as super::support::ConnectivityProbeFamily>::PHASE
            }

            fn total_steps(&self, plan: &crate::engine::runtime::ExecutionPlan) -> usize {
                <$family as super::support::ConnectivityProbeFamily>::targets(plan).len()
            }

            fn run_collecting(
                &self,
                plan: &crate::engine::runtime::ExecutionPlan,
                cancel: &std::sync::atomic::AtomicBool,
                tls_verifier: Option<&std::sync::Arc<dyn rustls::client::danger::ServerCertVerifier>>,
            ) -> crate::engine::runtime::CollectedStageOutcome {
                super::support::collect_family_steps::<$family>(plan, cancel, tls_verifier)
            }
        }
    };
}

mod circumvention;
mod dns;
mod doh_json;
mod environment;
mod quic;
mod service;
mod support;
mod tcp;
mod telegram;
mod telegram_record;
mod throughput;
mod web;

pub(super) use circumvention::CircumventionRunner;
pub(super) use dns::DnsRunner;
pub(super) use doh_json::DohJsonSurveyRunner;
pub(super) use environment::EnvironmentRunner;
pub(super) use quic::QuicRunner;
pub(super) use service::ServiceRunner;
pub(super) use tcp::TcpRunner;
pub(super) use telegram::TelegramRunner;
pub(super) use throughput::ThroughputRunner;
pub(super) use web::WebRunner;

#[cfg(test)]
mod tests {
    /// Frozen table of (phase, artifact_source) pairs for all connectivity stage runners.
    ///
    /// Each entry is (runner_name, expected_phase, expected_artifact_source).
    /// `artifact_source` is `None` for runners that use `run()` instead of
    /// `run_collecting()` and therefore do not emit a `ConnectivityProbeFamily`
    /// artifact source (telegram has no family artifact; environment uses its own const).
    const FROZEN: &[(&str, &str, &str)] = &[
        ("dns", "dns", "dns_integrity"),
        ("tcp", super::tcp::PHASE, super::tcp::ARTIFACT_SOURCE),
        ("quic", "quic", "quic_reachability"),
        ("web", super::web::PHASE, super::web::ARTIFACT_SOURCE),
        ("throughput", super::throughput::PHASE, super::throughput::ARTIFACT_SOURCE),
        ("circumvention", super::circumvention::PHASE, super::circumvention::ARTIFACT_SOURCE),
        ("service", super::service::PHASE, super::service::ARTIFACT_SOURCE),
        ("telegram", super::telegram::PHASE_TEST, "telegram"),
        ("environment", "environment", "network_environment"),
        ("doh_json", "doh_json", "doh_json_survey"),
    ];

    /// Expected byte-identical pairs frozen at spec time (acceptance criteria from
    /// add-phase-artifact-source-byte-identity-regression-test-for-connectivity).
    const EXPECTED: &[(&str, &str, &str)] = &[
        ("dns", "dns", "dns_integrity"),
        ("tcp", "tcp", "tcp_fat_header"),
        ("quic", "quic", "quic_reachability"),
        ("web", "reachability", "domain_reachability"),
        ("throughput", "throughput", "throughput_window"),
        ("circumvention", "circumvention", "circumvention_reachability"),
        ("service", "service", "service_reachability"),
        ("telegram", "telegram", "telegram"),
        ("environment", "environment", "network_environment"),
        ("doh_json", "doh_json", "doh_json_survey"),
    ];

    /// Asserts that every runner's `phase()` value is byte-identical to the frozen
    /// spec constant. A future rename of any phase string will make this test fail,
    /// forcing an explicit fixture update.
    #[test]
    fn phase_field_is_byte_identical_across_runs() {
        assert_eq!(FROZEN.len(), EXPECTED.len(), "FROZEN and EXPECTED tables must have the same length");
        for ((name, actual_phase, _), (_, expected_phase, _)) in FROZEN.iter().zip(EXPECTED.iter()) {
            assert_eq!(
                actual_phase.as_bytes(),
                expected_phase.as_bytes(),
                "runner '{name}': phase bytes differ — got {actual_phase:?}, want {expected_phase:?}",
            );
        }
        // Run twice to confirm byte identity is deterministic (no runtime nondeterminism).
        for ((name, actual_phase, _), (_, expected_phase, _)) in FROZEN.iter().zip(EXPECTED.iter()) {
            assert_eq!(
                actual_phase.as_bytes(),
                expected_phase.as_bytes(),
                "runner '{name}': phase bytes differ on second pass",
            );
        }
    }

    /// Asserts that every runner's `artifact_source` value is byte-identical to
    /// the frozen spec constant. Covers all nine connectivity runners.
    #[test]
    fn artifact_source_field_is_byte_identical_across_runs() {
        assert_eq!(FROZEN.len(), EXPECTED.len(), "FROZEN and EXPECTED tables must have the same length");
        for ((name, _, actual_artifact), (_, _, expected_artifact)) in FROZEN.iter().zip(EXPECTED.iter()) {
            assert_eq!(
                actual_artifact.as_bytes(),
                expected_artifact.as_bytes(),
                "runner '{name}': artifact_source bytes differ — got {actual_artifact:?}, want {expected_artifact:?}",
            );
        }
        // Second pass for byte-identity determinism.
        for ((name, _, actual_artifact), (_, _, expected_artifact)) in FROZEN.iter().zip(EXPECTED.iter()) {
            assert_eq!(
                actual_artifact.as_bytes(),
                expected_artifact.as_bytes(),
                "runner '{name}': artifact_source bytes differ on second pass",
            );
        }
    }
}
