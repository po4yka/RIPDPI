use ripdpi_proxy_runtime_adapter::udp_desync::{
    QuicInitialResponseContext, QuicInitialResponseDisposition, classify_quic_initial_response_context,
    parse_quic_initial_response_context,
};

use crate::runtime::state::RuntimeState;
use crate::runtime::types::{RuntimeOutboundProgress, RuntimeSessionState};

struct TrackedInitial {
    context: QuicInitialResponseContext,
    unverifiable_offer: bool,
}

pub(super) struct UdpFlowSession {
    state: RuntimeSessionState,
    quic_contexts: Vec<TrackedInitial>,
    quic_context_overflow: bool,
    pending_context: Option<usize>,
    pending_initial: Option<Vec<u8>>,
}

impl UdpFlowSession {
    pub(super) fn new() -> Self {
        Self {
            state: RuntimeState::new_session_state(),
            quic_contexts: Vec::new(),
            quic_context_overflow: false,
            pending_context: None,
            pending_initial: None,
        }
    }

    pub(super) fn response_matches_outbound(&mut self, payload: &[u8]) -> bool {
        if !self.feedback_is_eligible() {
            return false;
        }
        if self.quic_contexts.is_empty() {
            return self.pending_initial.is_none();
        }
        for tracked in &mut self.quic_contexts {
            match classify_quic_initial_response_context(&tracked.context, payload) {
                QuicInitialResponseDisposition::Accepted => return true,
                QuicInitialResponseDisposition::UnverifiableClientOffer => tracked.unverifiable_offer = true,
                QuicInitialResponseDisposition::Rejected => {}
            }
        }
        false
    }

    pub(super) fn has_unverifiable_client_offer(&self) -> bool {
        self.pending_context.is_some_and(|index| self.quic_contexts[index].unverifiable_offer)
    }

    pub(super) fn reset_response_evidence(&mut self) {
        self.quic_contexts.clear();
        self.quic_context_overflow = false;
        self.pending_context = None;
        self.pending_initial = None;
    }

    pub(super) fn pending_initial(&self) -> Option<&[u8]> {
        self.pending_initial.as_deref()
    }

    pub(super) fn observe_upstream_response(&mut self, payload: &[u8]) {
        self.reset_response_evidence();
        RuntimeState::observe_session_inbound_payload(&mut self.state, payload);
    }

    pub(super) fn observe_datagram_outbound(&mut self, payload: &[u8]) -> RuntimeOutboundProgress {
        RuntimeState::observe_session_datagram_outbound_payload(&mut self.state, payload)
    }

    pub(super) fn prepare_initial(
        &mut self,
        payload: &[u8],
        awaiting_first_response: bool,
    ) -> Option<QuicInitialResponseContext> {
        if !awaiting_first_response {
            return None;
        }
        let context = parse_quic_initial_response_context(payload)?;
        // Keep the attempted Initial for immediate hard-error fallback, even if its write fails.
        self.pending_initial = Some(payload.to_vec());
        Some(context)
    }

    pub(super) fn register_sent_initial(&mut self, context: Option<QuicInitialResponseContext>) {
        let Some(context) = context else {
            return;
        };
        if self.quic_context_overflow {
            return;
        }
        if let Some(index) = self.quic_contexts.iter().position(|tracked| tracked.context.matches_connection(&context))
        {
            self.quic_contexts[index].context = context;
            self.pending_context = Some(index);
        } else if self.quic_contexts.len() == 64 {
            // One UDP socket can carry several connections; never evict an active real context.
            self.quic_context_overflow = true;
            self.pending_context = None;
        } else {
            self.pending_context = Some(self.quic_contexts.len());
            self.quic_contexts.push(TrackedInitial { context, unverifiable_offer: false });
        }
    }

    #[cfg(test)]
    pub(super) fn observe_committed_datagram(&mut self, payload: &[u8], awaiting_first_response: bool) {
        let context = self.prepare_initial(payload, awaiting_first_response);
        self.register_sent_initial(context);
    }

    pub(super) fn feedback_is_eligible(&self) -> bool {
        !self.quic_context_overflow
    }
    pub(super) fn round_count(&self) -> u32 {
        RuntimeState::session_round_count(&self.state)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const REQUEST: &[u8] = include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../ripdpi-packets/tests/fixtures/quic/rfc9001-client-initial.bin"
    ));
    const RESPONSE: &[u8] = include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../ripdpi-packets/tests/fixtures/quic/rfc9001-server-initial.bin"
    ));

    #[test]
    fn dpi24_retention_is_bounded_and_released_after_acceptance() {
        let mut request = REQUEST.to_vec();
        request.resize(usize::from(u16::MAX), 0);
        let mut session = UdpFlowSession::new();
        session.observe_committed_datagram(&request, true);
        session.observe_committed_datagram(b"follow-up", true);
        assert_eq!(session.pending_initial(), Some(request.as_slice()));
        assert!(session.response_matches_outbound(RESPONSE));
        session.observe_upstream_response(RESPONSE);
        assert!(session.pending_initial().is_none());
        session.observe_committed_datagram(REQUEST, false);
        assert!(session.pending_initial().is_none());
        request.push(0);
        let mut fresh = UdpFlowSession::new();
        fresh.observe_committed_datagram(&request, true);
        assert!(fresh.pending_initial().is_none());
    }

    #[test]
    fn dpi24_neutral_evidence_resets_on_reselection_and_acceptance() {
        let request = include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-v2-partial-client.bin"
        ));
        let response = include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../ripdpi-packets/tests/fixtures/quic/compatible/v1-v2-server.bin"
        ));
        let mut session = UdpFlowSession::new();
        session.observe_committed_datagram(request, true);
        assert!(!session.response_matches_outbound(response));
        assert!(session.has_unverifiable_client_offer());
        let mut malformed = request.to_vec();
        *malformed.last_mut().unwrap() ^= 1;
        session.observe_committed_datagram(&malformed, true);
        assert_eq!(session.pending_initial(), Some(request.as_slice()));
        assert!(session.has_unverifiable_client_offer(), "bad integrity cannot replace retained evidence");
        session.observe_committed_datagram(request, true);
        assert!(session.has_unverifiable_client_offer(), "same-CID retransmit keeps evidence");
        let compatible = include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../ripdpi-packets/tests/fixtures/quic/compatible/v2-v1-client.bin"
        ));
        session.observe_committed_datagram(compatible, true);
        assert!(session.has_unverifiable_client_offer(), "compatible version keeps CID identity");
        session.observe_committed_datagram(request, true);
        session.reset_response_evidence();
        assert!(!session.has_unverifiable_client_offer());
        session.observe_committed_datagram(request, true);
        assert!(!session.response_matches_outbound(response));
        assert!(session.response_matches_outbound(RESPONSE));
        session.observe_upstream_response(RESPONSE);
        assert!(!session.has_unverifiable_client_offer());
        assert!(session.pending_initial().is_none());
        assert!(!UdpFlowSession::new().has_unverifiable_client_offer());
    }
}
