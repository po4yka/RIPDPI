use crate::types::{
    ProbeResult, StrategyExecutionDisposition, StrategyProbeAttemptExecutionEvidence, StrategyProbeCandidateSummary,
    StrategyProbeResponseStage, StrategyProbeRuntimeTerminalStatus,
};
use crate::{CandidateAttemptCorrelationId, CandidateDesyncExecutionReceipt, CandidateRuntimeExecutionEvidence};

#[derive(Debug)]
pub struct CandidateExecution {
    pub summary: StrategyProbeCandidateSummary,
    pub results: Vec<ProbeResult>,
    pub cancelled: bool,
    pub(in crate::execution::scoring) attempts: Vec<CandidateAttemptExecution>,
    pub(crate) execution_evidence_complete: bool,
}

#[derive(Debug)]
pub(in crate::execution::scoring) struct CandidateAttemptExecution {
    pub(in crate::execution::scoring) token: CandidateAttemptCorrelationId,
    pub(in crate::execution::scoring) success: bool,
    pub(in crate::execution::scoring) receipts: Vec<CandidateDesyncExecutionReceipt>,
}

impl CandidateExecution {
    pub(crate) fn attach_terminal_evidence(
        &mut self,
        generation: u64,
        terminal: &crate::CandidateRuntimeTerminalReceipt,
    ) {
        self.summary.runtime_terminal_status = project_terminal_status(terminal.terminal_status());
        if terminal.generation() != generation {
            self.execution_evidence_complete = false;
            self.summary.execution_evidence_complete = false;
            return;
        }
        if !self.summary.desync_execution_required {
            self.execution_evidence_complete = !terminal.execution_evidence_overflowed()
                && matches!(terminal.terminal_status(), crate::CandidateRuntimeTerminalStatus::CleanShutdown);
            self.summary.execution_evidence_complete = self.execution_evidence_complete;
            return;
        }
        if terminal.execution_evidence_overflowed()
            || !matches!(
                terminal.terminal_status(),
                crate::CandidateRuntimeTerminalStatus::CleanShutdown
                    | crate::CandidateRuntimeTerminalStatus::ForcedAbort
            )
        {
            self.execution_evidence_complete = false;
            return;
        }
        let mut invalid_or_unknown_evidence = false;
        for evidence in terminal.execution_evidence() {
            let CandidateRuntimeExecutionEvidence::Desync(evidence) = evidence;
            if evidence.generation() != generation || !evidence.attempt_token().is_evaluable() || !evidence.is_valid() {
                invalid_or_unknown_evidence = true;
                continue;
            }
            if let Some(attempt) = self.attempts.iter_mut().find(|attempt| attempt.token == *evidence.attempt_token()) {
                attempt.receipts.push(evidence.clone());
            } else {
                invalid_or_unknown_evidence = true;
            }
        }
        for attempt in &mut self.attempts {
            attempt.receipts.sort_by_key(|evidence| evidence.receipt().connection_ordinal);
        }
        self.execution_evidence_complete = !invalid_or_unknown_evidence
            && !self.attempts.is_empty()
            && self.attempts.iter().all(|attempt| !attempt.receipts.is_empty())
            && self.attempts.iter().all(|attempt| {
                attempt
                    .receipts
                    .iter()
                    .enumerate()
                    .all(|(index, evidence)| evidence.receipt().connection_ordinal == (index + 1) as u16)
            })
            && self
                .attempts
                .iter()
                .all(|attempt| attempt.receipts.iter().all(|evidence| evidence.generation() == generation));
        self.summary.execution_evidence_complete = self.execution_evidence_complete;
        self.summary.execution_attempts = self
            .attempts
            .iter()
            .map(|attempt| StrategyProbeAttemptExecutionEvidence {
                probe_succeeded: attempt.success,
                complete: !attempt.receipts.is_empty(),
                response_stage: if attempt.success {
                    StrategyProbeResponseStage::ResponseObserved
                } else {
                    StrategyProbeResponseStage::ResponseNotObserved
                },
                receipts: attempt.receipts.iter().map(|evidence| evidence.receipt().clone()).collect(),
            })
            .collect();
    }

    pub(crate) fn has_applied_success_evidence(&self) -> bool {
        self.execution_evidence_complete
            && self.attempts.iter().any(|attempt| {
                attempt.success
                    && !attempt.receipts.is_empty()
                    && attempt
                        .receipts
                        .iter()
                        .all(|evidence| evidence.receipt().disposition == StrategyExecutionDisposition::Applied)
            })
    }
}

fn project_terminal_status(status: crate::CandidateRuntimeTerminalStatus) -> StrategyProbeRuntimeTerminalStatus {
    match status {
        crate::CandidateRuntimeTerminalStatus::CleanShutdown => StrategyProbeRuntimeTerminalStatus::CleanShutdown,
        crate::CandidateRuntimeTerminalStatus::ForcedAbort => StrategyProbeRuntimeTerminalStatus::ForcedAbort,
        crate::CandidateRuntimeTerminalStatus::RuntimeFailed => StrategyProbeRuntimeTerminalStatus::RuntimeFailed,
        crate::CandidateRuntimeTerminalStatus::RuntimePanicked => StrategyProbeRuntimeTerminalStatus::RuntimePanicked,
        crate::CandidateRuntimeTerminalStatus::AlreadyJoined => StrategyProbeRuntimeTerminalStatus::Unavailable,
    }
}
