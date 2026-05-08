use crate::sync::{Arc, Mutex};

use std::io;

use ripdpi_proxy_runtime_adapter::model::session::{
    observe_inbound_payload as observe_session_inbound_payload,
    observe_outbound_payload as observe_session_outbound_payload, outbound_payload_count_this_round, OutboundProgress,
    SessionState,
};

pub(super) fn observe_inbound_payload(session: &Arc<Mutex<SessionState>>, payload: &[u8]) {
    if let Ok(mut state) = session.lock() {
        observe_session_inbound_payload(&mut state, payload);
    }
}

pub(super) fn observe_outbound_payload(
    session: &Arc<Mutex<SessionState>>,
    payload: &[u8],
) -> io::Result<(bool, OutboundProgress)> {
    let mut state = session.lock().map_err(|_| io::Error::other("session mutex poisoned"))?;
    let is_new_round = outbound_payload_count_this_round(&state) == 0;
    let progress = observe_session_outbound_payload(&mut state, payload);
    Ok((is_new_round, progress))
}
