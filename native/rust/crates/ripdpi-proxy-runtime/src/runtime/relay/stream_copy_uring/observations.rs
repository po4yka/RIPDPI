use crate::sync::{Arc, Mutex};

use std::io;

use ripdpi_proxy_runtime_adapter::session::{OutboundProgress, SessionState};

pub(super) fn observe_inbound_payload(session: &Arc<Mutex<SessionState>>, payload: &[u8]) {
    if let Ok(mut state) = session.lock() {
        state.observe_inbound(payload);
    }
}

pub(super) fn observe_outbound_payload(
    session: &Arc<Mutex<SessionState>>,
    payload: &[u8],
) -> io::Result<OutboundProgress> {
    let mut state = session.lock().map_err(|_| io::Error::other("session mutex poisoned"))?;
    Ok(state.observe_outbound(payload))
}
