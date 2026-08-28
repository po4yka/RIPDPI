//! Ownership of a pending listener and its flow-attribution generation.

use std::time::Instant as StdInstant;

use ripdpi_flow_app_attribution::FlowAttributionToken;
use smoltcp::iface::SocketHandle;

use crate::io_loop::packet::TcpFlowKey;

pub(crate) struct PendingListener {
    pub(crate) handle: SocketHandle,
    pub(crate) created_at: StdInstant,
    attribution: PendingAttribution,
}

/// The listener owns the registration until admission transfers it to a session.
struct PendingAttribution(Option<FlowAttributionToken>);

impl Drop for PendingAttribution {
    fn drop(&mut self) {
        if let Some(token) = self.0.take() {
            ripdpi_flow_app_attribution::evict_flow(token);
        }
    }
}

impl PendingListener {
    pub(crate) fn new(handle: SocketHandle, key: TcpFlowKey) -> Self {
        let token = ripdpi_flow_app_attribution::note_flow(crate::uid_policy::PROTO_TCP, key.src, key.dst).token;
        Self { handle, created_at: StdInstant::now(), attribution: PendingAttribution(Some(token)) }
    }

    pub(crate) fn attribution_token(&self) -> &FlowAttributionToken {
        // Infallible: only admission takes the token, after removing this listener from the pending map.
        self.attribution.0.as_ref().expect("pending listener owns its attribution")
    }

    pub(crate) fn take_attribution(&mut self) -> FlowAttributionToken {
        // Infallible: admission removes the unique listener and calls this exactly once before dropping it.
        self.attribution.0.take().expect("attribution transfers once at admission")
    }
}
