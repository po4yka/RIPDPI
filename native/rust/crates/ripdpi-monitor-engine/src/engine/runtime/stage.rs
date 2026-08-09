use std::sync::Arc;
use std::sync::atomic::AtomicBool;

use rustls::client::danger::ServerCertVerifier;

use super::plan::ExecutionPlan;
use super::recording::{CollectedStageOutcome, record_collected_outcome};
use super::state::ExecutionRuntime;

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub(in crate::engine) enum ExecutionStageId {
    Environment,
    Dns,
    Web,
    Quic,
    Tcp,
    Service,
    Circumvention,
    Telegram,
    Throughput,
    DohJsonSurvey,
    StrategyDnsBaseline,
    StrategyTcpCandidates,
    StrategyQuicCandidates,
    StrategyConnectionConcurrency,
    StrategyRecommendation,
}

impl ExecutionStageId {
    pub(in crate::engine) const fn as_str(&self) -> &'static str {
        match self {
            Self::Environment => "environment",
            Self::Dns => "dns",
            Self::Web => "web",
            Self::Quic => "quic",
            Self::Tcp => "tcp",
            Self::Service => "service",
            Self::Circumvention => "circumvention",
            Self::Telegram => "telegram",
            Self::Throughput => "throughput",
            Self::DohJsonSurvey => "doh_json_survey",
            Self::StrategyDnsBaseline => "strategy_dns_baseline",
            Self::StrategyTcpCandidates => "strategy_tcp_candidates",
            Self::StrategyQuicCandidates => "strategy_quic_candidates",
            Self::StrategyConnectionConcurrency => "strategy_connection_concurrency",
            Self::StrategyRecommendation => "strategy_recommendation",
        }
    }
}

pub(in crate::engine) enum RunnerOutcome {
    Completed,
    Cancelled,
    Finished,
    Failed(String),
}

pub(in crate::engine) trait ExecutionStageRunner {
    fn id(&self) -> ExecutionStageId;

    fn phase(&self) -> &'static str;

    fn total_steps(&self, plan: &ExecutionPlan) -> usize;

    fn run(
        &self,
        plan: &ExecutionPlan,
        runtime: &mut ExecutionRuntime,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> RunnerOutcome {
        let deadline = runtime.scan_deadline();
        let collected = ripdpi_diagnostics_contracts::util::with_scan_io_deadline(deadline, || {
            self.run_collecting(plan, runtime.cancel_token(), tls_verifier)
        });
        record_collected_outcome(plan, runtime, collected)
    }

    /// Run without touching `runtime`, returning all steps collected independently.
    /// Used by the parallel connectivity runner path.
    /// Returns collected partial steps alongside a cancellation marker if the
    /// runner was cancelled mid-stage.
    fn run_collecting(
        &self,
        _plan: &ExecutionPlan,
        _cancel: &AtomicBool,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        CollectedStageOutcome::Cancelled(Vec::new())
    }
}
