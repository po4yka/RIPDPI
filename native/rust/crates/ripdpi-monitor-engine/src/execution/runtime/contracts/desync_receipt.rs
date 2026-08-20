mod validation;

use crate::types::StrategyDesyncExecutionReceipt;

use super::CandidateAttemptCorrelationId;

#[derive(Clone, PartialEq, Eq)]
pub struct CandidateDesyncExecutionReceipt {
    generation: u64,
    attempt_token: CandidateAttemptCorrelationId,
    receipt: StrategyDesyncExecutionReceipt,
}

impl CandidateDesyncExecutionReceipt {
    pub fn checked(
        generation: u64,
        attempt_token: CandidateAttemptCorrelationId,
        receipt: StrategyDesyncExecutionReceipt,
    ) -> Option<Self> {
        let candidate = Self { generation, attempt_token, receipt };
        validation::is_valid(&candidate).then_some(candidate)
    }

    pub(crate) fn generation(&self) -> u64 {
        self.generation
    }

    pub(crate) fn attempt_token(&self) -> &CandidateAttemptCorrelationId {
        &self.attempt_token
    }

    pub(crate) fn receipt(&self) -> &StrategyDesyncExecutionReceipt {
        &self.receipt
    }

    pub(crate) fn is_valid(&self) -> bool {
        validation::is_valid(self)
    }
}

impl std::fmt::Debug for CandidateDesyncExecutionReceipt {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("CandidateDesyncExecutionReceipt")
            .field("generation", &self.generation)
            .field("attempt_token", &self.attempt_token)
            .field("receipt", &self.receipt)
            .finish()
    }
}

pub type CandidateDesyncExecutionDisposition = crate::types::StrategyExecutionDisposition;
