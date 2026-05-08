use std::io;

use ripdpi_proxy_runtime_adapter::model::session::OutboundProgress;

use super::super::session::RelaySharedSession;

pub(super) fn observe_inbound_payload(session: &RelaySharedSession, payload: &[u8]) {
    session.observe_inbound_payload(payload);
}

pub(super) fn observe_outbound_payload(session: &RelaySharedSession, payload: &[u8]) -> io::Result<OutboundProgress> {
    session.observe_outbound_payload(payload).map(|(_, progress)| progress)
}
