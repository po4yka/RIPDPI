use crate::sync::{Arc, Mutex};

use std::io;

use ripdpi_proxy_runtime_adapter::model::config::{DesyncGroup, RotationPolicy};
use ripdpi_proxy_runtime_adapter::model::tcp_rotation::CircularTcpRotationController;

use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeOutboundProgress, RuntimeSessionState};

pub(super) struct RelaySession {
    state: RuntimeSessionState,
}

pub(super) struct FirstOutboundSession {
    state: RuntimeSessionState,
}

impl FirstOutboundSession {
    pub(super) fn new() -> Self {
        Self { state: RuntimeState::new_session_state() }
    }

    pub(super) fn into_relay_session(self) -> RelaySession {
        RelaySession::from_state(self.state)
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
    pub(super) fn from_state(state: RuntimeSessionState) -> Self {
        Self { state }
    }

    pub(super) fn into_shared(self) -> RelaySharedSession {
        RelaySharedSession { state: Arc::new(Mutex::new(self.state)) }
    }

    pub(super) fn has_inbound_payload(&self) -> bool {
        RuntimeState::session_has_inbound_payload(&self.state)
    }

    pub(super) fn rotation_controller(
        &self,
        rotation_seed: Option<(DesyncGroup, RotationPolicy)>,
    ) -> Option<Arc<Mutex<CircularTcpRotationController>>> {
        if !self.has_inbound_payload() {
            return None;
        }
        rotation_seed
            .and_then(|(group, policy)| CircularTcpRotationController::new(group, policy))
            .map(|controller| Arc::new(Mutex::new(controller)))
    }
}

#[derive(Clone)]
pub(super) struct RelaySharedSession {
    state: Arc<Mutex<RuntimeSessionState>>,
}

impl RelaySharedSession {
    pub(super) fn snapshot(&self) -> io::Result<RelaySession> {
        self.state
            .lock()
            .map_err(|_| io::Error::other("session mutex poisoned"))
            .map(|state| RelaySession::from_state(state.clone()))
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
