use crate::types::StrategyDesyncExecutionReceipt;

use super::{CandidateAttemptCorrelationId, CandidateDesyncExecutionReceipt};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CandidateRuntimeExecutionEvidence {
    Desync(CandidateDesyncExecutionReceipt),
}

impl CandidateRuntimeExecutionEvidence {
    pub fn checked_desync(
        generation: u64,
        attempt_token: CandidateAttemptCorrelationId,
        receipt: StrategyDesyncExecutionReceipt,
    ) -> Option<Self> {
        CandidateDesyncExecutionReceipt::checked(generation, attempt_token, receipt).map(Self::Desync)
    }

    pub(super) fn generation(&self) -> u64 {
        match self {
            Self::Desync(receipt) => receipt.generation(),
        }
    }
}

pub(super) fn execution_evidence_matches_generation(
    generation: u64,
    execution_evidence: &[CandidateRuntimeExecutionEvidence],
) -> bool {
    execution_evidence.iter().all(|evidence| evidence.generation() == generation)
}
