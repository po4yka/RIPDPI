//! Ownership of a pending listener and its flow-attribution generation.

use std::time::Instant as StdInstant;

use ripdpi_flow_app_attribution::FlowRegistrationId;
use smoltcp::iface::SocketHandle;

use crate::io_loop::packet::TcpFlowKey;

pub(crate) struct PendingListener {
    pub(crate) handle: SocketHandle,
    pub(crate) created_at: StdInstant,
    attribution: PendingAttribution,
}

/// The listener owns the registration until admission transfers it to a session.
struct PendingAttribution(Option<FlowRegistrationId>);

impl Drop for PendingAttribution {
    fn drop(&mut self) {
        if let Some(registration_id) = self.0.take() {
            ripdpi_flow_app_attribution::evict_flow_if_current(registration_id);
        }
    }
}

impl PendingListener {
    pub(crate) fn new(handle: SocketHandle, key: TcpFlowKey) -> Self {
        let registration_id =
            ripdpi_flow_app_attribution::note_flow(crate::uid_policy::PROTO_TCP, key.src, key.dst).registration_id;
        Self { handle, created_at: StdInstant::now(), attribution: PendingAttribution(Some(registration_id)) }
    }

    pub(crate) fn attribution_id(&self) -> &FlowRegistrationId {
        // Infallible: only admission takes the registration ID, after removing this listener from the pending map.
        self.attribution.0.as_ref().expect("pending listener owns its attribution")
    }

    pub(crate) fn take_attribution(&mut self) -> FlowRegistrationId {
        // Infallible: admission removes the unique listener and calls this exactly once before dropping it.
        self.attribution.0.take().expect("attribution transfers once at admission")
    }
}
