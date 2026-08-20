use crate::CandidateAttemptCorrelationId;
use crate::types::ProbeResult;

pub struct ProbeSample {
    pub result: ProbeResult,
    pub success: bool,
    pub weight: usize,
    pub quality: usize,
    pub latency_ms: u64,
    /// The domain this sample was probed against, for per-domain outcome tracking.
    pub domain: Option<String>,
    /// Whether the exact planned domain target is a neutral control.
    pub is_control: bool,
    pub attempt_token: Option<CandidateAttemptCorrelationId>,
}
