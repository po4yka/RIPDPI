use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeOutboundProgress, RuntimeSessionState};

pub(super) struct UdpFlowSession {
    state: RuntimeSessionState,
    quic_initial_ids: Vec<(Vec<u8>, Vec<u8>)>,
    quic_context_overflow: bool,
}

impl UdpFlowSession {
    pub(super) fn new() -> Self {
        Self { state: RuntimeState::new_session_state(), quic_initial_ids: Vec::new(), quic_context_overflow: false }
    }

    pub(super) fn observe_upstream_response(&mut self, payload: &[u8]) {
        RuntimeState::observe_session_inbound_payload(&mut self.state, payload);
    }

    pub(super) fn observe_datagram_outbound(&mut self, payload: &[u8]) -> RuntimeOutboundProgress {
        if !self.quic_context_overflow
            && let Some((version, dcid, scid)) = quic_long_header_ids(payload)
            && matches!((version, payload[0] & 0xf0), (1, 0xc0) | (0x6b33_43cf, 0xd0))
            && !self.quic_initial_ids.iter().any(|(sent_dcid, sent_scid)| dcid == sent_dcid && scid == sent_scid)
        {
            // One UDP socket can carry several connections; never evict an active real context.
            if self.quic_initial_ids.len() == 64 {
                self.quic_context_overflow = true;
            } else {
                self.quic_initial_ids.push((dcid.to_vec(), scid.to_vec()));
            }
        }
        RuntimeState::observe_session_datagram_outbound_payload(&mut self.state, payload)
    }

    pub(super) fn response_matches_outbound(&self, payload: &[u8]) -> bool {
        if !self.feedback_is_eligible() {
            return false;
        }
        if self.quic_initial_ids.is_empty() {
            return true;
        }
        if payload.first().is_none_or(|first| first & 0x80 == 0) {
            return true;
        }
        let Some((version, dcid, scid)) = quic_long_header_ids(payload) else {
            return false;
        };
        self.quic_initial_ids.iter().any(|(client_dcid, client_scid)| {
            dcid == client_scid.as_slice() && (version != 0 || scid == client_dcid.as_slice())
        })
    }

    pub(super) fn feedback_is_eligible(&self) -> bool {
        !self.quic_context_overflow
    }

    pub(super) fn round_count(&self) -> u32 {
        RuntimeState::session_round_count(&self.state)
    }
}

fn quic_long_header_ids(payload: &[u8]) -> Option<(u32, &[u8], &[u8])> {
    if payload.first()? & 0x80 == 0 {
        return None;
    }
    let version = u32::from_be_bytes(payload.get(1..5)?.try_into().ok()?);
    let dcid_len = usize::from(*payload.get(5)?);
    if dcid_len > 20 {
        return None;
    }
    let dcid = payload.get(6..6 + dcid_len)?;
    let scid_offset = 6 + dcid_len;
    let scid_len = usize::from(*payload.get(scid_offset)?);
    if scid_len > 20 {
        return None;
    }
    let scid = payload.get(scid_offset + 1..scid_offset + 1 + scid_len)?;
    Some((version, dcid, scid))
}
