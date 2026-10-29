use crate::sync::{Arc, Mutex};

use std::io;

use ripdpi_proxy_runtime_adapter::model::tcp_rotation::CircularTcpRotationController;

use super::pending_response::PendingFirstResponse;
use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeOutboundProgress, RuntimeRelayRotationSeed, RuntimeSessionState};

#[derive(Clone, Copy, Default)]
pub(super) enum FirstResponseOutcome {
    #[default]
    Unobserved,
    Pending,
    Accepted {
        success_recorded: bool,
    },
    Rejected,
}

impl FirstResponseOutcome {
    fn permits_success(self) -> bool {
        matches!(self, Self::Unobserved | Self::Accepted { success_recorded: false })
    }

    fn permits_rotation(self) -> bool {
        matches!(self, Self::Unobserved | Self::Accepted { .. })
    }
}

pub(super) struct RelaySession {
    state: RuntimeSessionState,
    first_response: FirstResponseOutcome,
    pending_response: Option<PendingFirstResponse>,
    pub(super) lua_flow: Option<std::sync::Arc<ripdpi_proxy_runtime_adapter::desync_platform::SocketLuaFlow>>,
}

pub(super) struct FirstOutboundSession {
    state: RuntimeSessionState,
    first_response: FirstResponseOutcome,
    pending_response: Option<PendingFirstResponse>,
    pub(super) lua_flow: Option<std::sync::Arc<ripdpi_proxy_runtime_adapter::desync_platform::SocketLuaFlow>>,
}

impl FirstOutboundSession {
    pub(super) fn new() -> Self {
        Self {
            state: RuntimeState::new_session_state(),
            first_response: FirstResponseOutcome::Unobserved,
            pending_response: None,
            lua_flow: None,
        }
    }

    pub(super) fn into_relay_session(self) -> RelaySession {
        RelaySession {
            state: self.state,
            first_response: self.first_response,
            pending_response: self.pending_response,
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
        RelaySharedSession {
            state: Arc::new(Mutex::new(self.state)),
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
    state: Arc<Mutex<RuntimeSessionState>>,
    first_response: Arc<Mutex<FirstResponseOutcome>>,
    pub(super) lua_flow: Option<std::sync::Arc<ripdpi_proxy_runtime_adapter::desync_platform::SocketLuaFlow>>,
}

impl RelaySharedSession {
    pub(super) fn snapshot(&self) -> io::Result<RelaySession> {
        let first_response = *self.first_response.lock().map_err(|_| io::Error::other("response mutex poisoned"))?;
        self.state.lock().map_err(|_| io::Error::other("session mutex poisoned")).map(|state| RelaySession {
            state: state.clone(),
            first_response,
            pending_response: None,
            lua_flow: self.lua_flow.clone(),
        })
    }

    pub(super) fn finish_first_response(&self, outcome: FirstResponseOutcome) -> io::Result<()> {
        *self.first_response.lock().map_err(|_| io::Error::other("response mutex poisoned"))? = outcome;
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
