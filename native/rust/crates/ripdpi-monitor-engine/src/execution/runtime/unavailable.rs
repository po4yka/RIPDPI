use super::contracts::{
    CandidateProbeRuntime, CandidateRuntimeError, CandidateRuntimeLauncher, PreparedCandidateRuntime,
};

/// Default launcher used when no candidate runtime backend is wired into the
/// session. Every launch attempt fails with [`CandidateRuntimeError::LauncherUnavailable`].
pub struct UnavailableCandidateRuntimeLauncher;

impl CandidateRuntimeLauncher for UnavailableCandidateRuntimeLauncher {
    fn start_candidate_runtime(
        &self,
        _prepared: PreparedCandidateRuntime,
    ) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
        Err(CandidateRuntimeError::LauncherUnavailable)
    }
}
