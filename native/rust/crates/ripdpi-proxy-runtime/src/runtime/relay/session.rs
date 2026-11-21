use crate::sync::{Arc, Mutex};

use std::io;

use crate::runtime::desync::{EvolutionAttempt, FakeTtlAttempt};

use ripdpi_proxy_runtime_adapter::model::tcp_rotation::CircularTcpRotationController;

use super::pending_response::PendingFirstResponse;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeOutboundProgress, RuntimeRelayRotationSeed, RuntimeSessionState};

mod evolution_feedback;
mod fake_ttl_feedback;
use evolution_feedback::EvolutionFeedbackLedger;

pub(super) use fake_ttl_feedback::FakeTtlFeedback;
use fake_ttl_feedback::FakeTtlFeedbackLedger;

#[cfg(test)]
mod tests;

#[derive(Clone, Copy, Default)]
pub(super) enum FirstResponseOutcome {
    #[default]
    Unobserved,
    Pending,
    Accepted,
    Rejected,
    Neutral,
}

impl FirstResponseOutcome {
    fn permits_success(self) -> bool {
        matches!(self, Self::Unobserved | Self::Accepted)
    }

    fn permits_rotation(self) -> bool {
        matches!(self, Self::Unobserved | Self::Accepted)
    }
}

/// Adaptive observations owned by one relay success.
pub(crate) struct AdaptiveFeedbackAttempts {
    pub(super) fake_ttl: Option<FakeTtlAttempt>,
    pub(super) server_ttl: Option<u8>,
    pub(super) evolution: Option<EvolutionAttempt>,
}

pub(super) struct RelaySession {
    state: RuntimeSessionState,
    first_response: FirstResponseOutcome,
    pending_response: Option<PendingFirstResponse>,
    pub(super) fake_ttl_attempt: Option<FakeTtlAttempt>,
    pub(super) server_ttl: Option<u8>,
    pub(super) evolution_attempt: Option<EvolutionAttempt>,
    pub(super) lua_flow: Option<std::sync::Arc<ripdpi_proxy_runtime_adapter::desync_platform::SocketLuaFlow>>,
}

pub(super) struct FirstOutboundSession {
    state: RuntimeSessionState,
    first_response: FirstResponseOutcome,
    pending_response: Option<PendingFirstResponse>,
    pub(super) fake_ttl_attempt: Option<FakeTtlAttempt>,
    pub(super) server_ttl: Option<u8>,
    pub(super) evolution_attempt: Option<EvolutionAttempt>,
    pub(super) lua_flow: Option<std::sync::Arc<ripdpi_proxy_runtime_adapter::desync_platform::SocketLuaFlow>>,
}

impl FirstOutboundSession {
    pub(super) fn new() -> Self {
        Self {
            state: RuntimeState::new_session_state(),
            first_response: FirstResponseOutcome::Unobserved,
            pending_response: None,
            fake_ttl_attempt: None,
            server_ttl: None,
            evolution_attempt: None,
            lua_flow: None,
        }
    }

    pub(super) fn into_relay_session(self) -> RelaySession {
        RelaySession {
            state: self.state,
            first_response: self.first_response,
            pending_response: self.pending_response,
            fake_ttl_attempt: self.fake_ttl_attempt,
            server_ttl: self.server_ttl,
            evolution_attempt: self.evolution_attempt,
            lua_flow: self.lua_flow,
        }
    }

    pub(super) fn defer_first_response(&mut self, pending: PendingFirstResponse) {
        self.first_response = FirstResponseOutcome::Pending;
        self.pending_response = Some(pending);
    }

    pub(super) fn reject_first_response(&mut self) {
        self.first_response = FirstResponseOutcome::Rejected;
    }

    pub(super) fn accept_first_response(&mut self, server_ttl: Option<u8>) {
        self.first_response = FirstResponseOutcome::Accepted;
        self.server_ttl = server_ttl;
    }

    pub(super) fn neutral_first_response(&mut self) {
        self.first_response = FirstResponseOutcome::Neutral;
    }

    pub(super) fn observe_first_outbound_payload(&mut self, original_request: &[u8]) -> RuntimeOutboundProgress {
        RuntimeState::observe_session_outbound_payload(&mut self.state, original_request)
    }

    pub(super) fn observe_first_response_payload(&mut self, bytes: &[u8]) -> bool {
        RuntimeState::observe_session_first_response_payload(&mut self.state, bytes)
    }

    pub(super) fn observe_retry_response_payload(&mut self, bytes: &[u8]) {
        RuntimeState::observe_session_retry_response_payload(&mut self.state, bytes);
    }
}

impl RelaySession {
    pub(super) fn take_pending_response(&mut self) -> Option<PendingFirstResponse> {
        self.pending_response.take()
    }

    pub(super) fn permits_success(&self) -> bool {
        self.first_response.permits_success()
    }

    pub(super) fn into_shared(self) -> RelaySharedSession {
        let neutral = matches!(self.first_response, FirstResponseOutcome::Rejected | FirstResponseOutcome::Neutral);
        let mut fake_ttl = FakeTtlFeedbackLedger::new(self.fake_ttl_attempt, neutral);
        let mut evolution = EvolutionFeedbackLedger::new(self.evolution_attempt, neutral);
        if matches!(self.first_response, FirstResponseOutcome::Accepted) {
            let _ = fake_ttl.accept_response(self.server_ttl);
            let _ = evolution.finish_response(true);
        }
        RelaySharedSession {
            state: Arc::new(Mutex::new(self.state)),
            evolution_feedback: Arc::new(Mutex::new(evolution)),
            fake_ttl_feedback: Arc::new(Mutex::new(fake_ttl)),
            first_response: Arc::new(Mutex::new(self.first_response)),
            lua_flow: self.lua_flow,
        }
    }

    pub(super) fn has_inbound_payload(&self) -> bool {
        RuntimeState::session_has_inbound_payload(&self.state)
    }

    pub(super) fn rotation_controller(
        rotation_seed: Option<RuntimeRelayRotationSeed>,
    ) -> Option<Arc<Mutex<CircularTcpRotationController>>> {
        // Create the controller now, but do not use it until the shared session
        // has an accepted response. A pending observer can activate it later.
        rotation_seed
            .and_then(|(group, policy)| CircularTcpRotationController::new(group, policy))
            .map(|controller| Arc::new(Mutex::new(controller)))
    }
}

#[derive(Clone)]
pub(super) struct RelaySharedSession {
    // Never nest these locks. Copy state or claim an event, release the guard,
    // then perform socket I/O, cache feedback, or acquire another session lock.
    fake_ttl_feedback: Arc<Mutex<FakeTtlFeedbackLedger>>,
    evolution_feedback: Arc<Mutex<EvolutionFeedbackLedger>>,
    state: Arc<Mutex<RuntimeSessionState>>,
    first_response: Arc<Mutex<FirstResponseOutcome>>,
    pub(super) lua_flow: Option<std::sync::Arc<ripdpi_proxy_runtime_adapter::desync_platform::SocketLuaFlow>>,
}

impl RelaySharedSession {
    pub(super) fn begin_evolution_send(&self) -> io::Result<Option<EvolutionAttempt>> {
        self.evolution_feedback.lock().map_err(|_| io::Error::other("evolution feedback mutex poisoned"))?.begin_send()
    }

    pub(super) fn finish_evolution_send(
        &self,
        state: &RuntimeState,
        attempt: Option<EvolutionAttempt>,
    ) -> io::Result<()> {
        let feedback = self
            .evolution_feedback
            .lock()
            .map_err(|_| io::Error::other("evolution feedback mutex poisoned"))?
            .finish_send(attempt)?;
        apply_evolution_feedback(state, feedback);
        Ok(())
    }

    pub(super) fn finish_evolution_response(&self, state: &RuntimeState, success: bool) -> io::Result<()> {
        let feedback = self
            .evolution_feedback
            .lock()
            .map_err(|_| io::Error::other("evolution feedback mutex poisoned"))?
            .finish_response(success);
        apply_evolution_feedback(state, feedback);
        Ok(())
    }

    pub(super) fn begin_fake_ttl_send(&self) -> io::Result<Option<FakeTtlAttempt>> {
        self.fake_ttl_feedback.lock().map_err(|_| io::Error::other("fake TTL feedback mutex poisoned"))?.begin_send()
    }

    pub(super) fn finish_fake_ttl_send(&self, attempt: Option<FakeTtlAttempt>) -> io::Result<Option<FakeTtlFeedback>> {
        self.fake_ttl_feedback
            .lock()
            .map_err(|_| io::Error::other("fake TTL feedback mutex poisoned"))?
            .finish_send(attempt)
    }

    pub(super) fn accept_fake_ttl_response(&self, server_ttl: Option<u8>) -> io::Result<Option<FakeTtlFeedback>> {
        let mut ledger =
            self.fake_ttl_feedback.lock().map_err(|_| io::Error::other("fake TTL feedback mutex poisoned"))?;
        Ok(ledger.accept_response(server_ttl))
    }

    /// The active pending reader owns content completion; Neutral revokes that ownership.
    pub(super) fn response_owner_revoked(&self) -> io::Result<bool> {
        let outcome = self.first_response.lock().map_err(|_| io::Error::other("response mutex poisoned"))?;
        Ok(matches!(*outcome, FirstResponseOutcome::Neutral))
    }

    pub(super) fn snapshot(&self) -> io::Result<RelaySession> {
        let first_response = *self.first_response.lock().map_err(|_| io::Error::other("response mutex poisoned"))?;
        let fake_ttl = self
            .fake_ttl_feedback
            .lock()
            .map_err(|_| io::Error::other("fake TTL feedback mutex poisoned"))?
            .final_feedback();
        let evolution_attempt = self
            .evolution_feedback
            .lock()
            .map_err(|_| io::Error::other("evolution feedback mutex poisoned"))?
            .final_attempt();
        self.state.lock().map_err(|_| io::Error::other("session mutex poisoned")).map(|state| RelaySession {
            state: state.clone(),
            first_response,
            pending_response: None,
            fake_ttl_attempt: fake_ttl.map(|feedback| feedback.attempt),
            server_ttl: fake_ttl.and_then(|feedback| feedback.server_ttl),
            evolution_attempt,
            lua_flow: self.lua_flow.clone(),
        })
    }

    pub(super) fn finish_first_response(&self, outcome: FirstResponseOutcome) -> io::Result<()> {
        if matches!(outcome, FirstResponseOutcome::Neutral) {
            // Close the feedback ledger before publishing Neutral. A delayed
            // send receipt must not claim an earlier queued success after it.
            self.evolution_feedback
                .lock()
                .map_err(|_| io::Error::other("evolution feedback mutex poisoned"))?
                .mark_neutral_response();
        }
        {
            let mut current = self.first_response.lock().map_err(|_| io::Error::other("response mutex poisoned"))?;
            if !matches!(*current, FirstResponseOutcome::Neutral) {
                *current = outcome;
            }
        }
        if matches!(outcome, FirstResponseOutcome::Neutral) {
            self.fake_ttl_feedback
                .lock()
                .map_err(|_| io::Error::other("fake TTL feedback mutex poisoned"))?
                .discard_response();
        }
        Ok(())
    }

    pub(super) fn rotation_eligible(&self) -> bool {
        self.first_response.lock().is_ok_and(|outcome| outcome.permits_rotation())
            && self.state.lock().is_ok_and(|state| RuntimeState::session_has_inbound_payload(&state))
    }

    pub(super) fn observe_first_response_payload(&self, payload: &[u8]) -> io::Result<bool> {
        let mut state = self.state.lock().map_err(|_| io::Error::other("session mutex poisoned"))?;
        Ok(RuntimeState::observe_session_first_response_payload(&mut state, payload))
    }

    pub(super) fn observe_retry_response_payload(&self, payload: &[u8]) -> io::Result<()> {
        let mut state = self.state.lock().map_err(|_| io::Error::other("session mutex poisoned"))?;
        RuntimeState::observe_session_retry_response_payload(&mut state, payload);
        Ok(())
    }

    pub(super) fn observe_inbound_payload(&self, payload: &[u8]) {
        if let Ok(mut state) = self.state.lock() {
            RuntimeState::observe_session_inbound_payload(&mut state, payload);
        }
    }

    pub(super) fn observe_outbound_payload(&self, payload: &[u8]) -> io::Result<(bool, RuntimeOutboundProgress)> {
        let mut state = self.state.lock().map_err(|_| io::Error::other("session mutex poisoned"))?;
        let is_new_round = RuntimeState::outbound_payload_count_this_round(&state) == 0;
        let progress = RuntimeState::observe_session_outbound_payload(&mut state, payload);
        Ok((is_new_round, progress))
    }
}

fn apply_evolution_feedback(state: &RuntimeState, feedback: Option<(EvolutionAttempt, bool)>) {
    match feedback {
        Some((attempt, true)) => state.note_evolver_answered(Some(attempt)),
        Some((attempt, false)) => state.discard_evolver_attempt(Some(attempt)),
        None => {}
    }
}
