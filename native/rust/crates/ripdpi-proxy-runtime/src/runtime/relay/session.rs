use ripdpi_proxy_runtime_adapter::model::session::{has_inbound_payload, SessionState};

pub(super) struct RelaySession {
    state: SessionState,
}

impl RelaySession {
    pub(super) fn from_state(state: SessionState) -> Self {
        Self { state }
    }

    pub(super) fn into_state(self) -> SessionState {
        self.state
    }

    pub(super) fn has_inbound_payload(&self) -> bool {
        has_inbound_payload(&self.state)
    }
}
