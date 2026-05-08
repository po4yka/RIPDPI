use crate::sync::{Arc, Mutex};

use ripdpi_proxy_runtime_adapter::model::config::{DesyncGroup, RotationPolicy};
use ripdpi_proxy_runtime_adapter::model::session::{has_inbound_payload, SessionState};
use ripdpi_proxy_runtime_adapter::model::tcp_rotation::CircularTcpRotationController;

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
