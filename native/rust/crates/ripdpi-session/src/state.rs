use ripdpi_packets::{is_tls_client_hello, is_tls_server_hello, tls_session_id_mismatch};

/// Maximum number of ClientHello bytes to store for session-ID mismatch detection.
pub(crate) const CLIENT_HELLO_PREFIX_CAP: usize = 76;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionPhase {
    Handshake,
    Connected,
    Closed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OutboundProgress {
    pub round: u32,
    pub payload_size: usize,
    pub stream_start: usize,
    pub stream_end: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionState {
    pub phase: SessionPhase,
    pub round_count: u32,
    pub recv_count: usize,
    pub sent_this_round: usize,
    pub outbound_bytes_total: usize,
    pub saw_tls_client_hello: bool,
    pub(crate) client_hello_prefix: Vec<u8>,
}

impl Default for SessionState {
    fn default() -> Self {
        Self {
            phase: SessionPhase::Handshake,
            round_count: 0,
            recv_count: 0,
            sent_this_round: 0,
            outbound_bytes_total: 0,
            saw_tls_client_hello: false,
            client_hello_prefix: Vec::new(),
        }
    }
}

impl SessionState {
    pub fn observe_outbound(&mut self, payload: &[u8]) -> OutboundProgress {
        if self.sent_this_round == 0 {
            self.round_count += 1;
        }
        let stream_start = self.outbound_bytes_total;
        self.sent_this_round += payload.len();
        self.outbound_bytes_total = self.outbound_bytes_total.saturating_add(payload.len());
        if is_tls_client_hello(payload) {
            self.saw_tls_client_hello = true;
            let cap = payload.len().min(CLIENT_HELLO_PREFIX_CAP);
            self.client_hello_prefix.clear();
            self.client_hello_prefix.extend_from_slice(&payload[..cap]);
        }
        OutboundProgress {
            round: self.round_count,
            payload_size: payload.len(),
            stream_start,
            stream_end: stream_start.saturating_add(payload.len().saturating_sub(1)),
        }
    }

    pub fn observe_datagram_outbound(&mut self, payload: &[u8]) -> OutboundProgress {
        self.round_count += 1;
        self.sent_this_round = payload.len();
        let stream_start = self.outbound_bytes_total;
        self.outbound_bytes_total = self.outbound_bytes_total.saturating_add(payload.len());
        OutboundProgress {
            round: self.round_count,
            payload_size: payload.len(),
            stream_start,
            stream_end: stream_start.saturating_add(payload.len().saturating_sub(1)),
        }
    }

    pub fn observe_inbound(&mut self, payload: &[u8]) {
        self.recv_count += payload.len();
        self.sent_this_round = 0;
        if self.saw_tls_client_hello
            && (!is_tls_server_hello(payload) || tls_session_id_mismatch(&self.client_hello_prefix, payload))
        {
            self.saw_tls_client_hello = false;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_state_tls_flag_cleared_on_non_server_hello() {
        let mut state = SessionState::default();
        state.observe_outbound(ripdpi_packets::DEFAULT_FAKE_TLS);
        assert!(state.saw_tls_client_hello);
        state.observe_inbound(b"not tls at all");
        assert!(!state.saw_tls_client_hello);
    }

    #[test]
    fn session_state_tracks_rounds_and_resets_after_inbound() {
        let mut state = SessionState::default();

        let first = state.observe_outbound(b"hello");
        let second = state.observe_outbound(b"world");
        assert_eq!(state.round_count, 1);
        assert_eq!(state.sent_this_round, 10);
        assert_eq!(first.stream_start, 0);
        assert_eq!(first.stream_end, 4);
        assert_eq!(second.stream_start, 5);
        assert_eq!(second.stream_end, 9);

        state.observe_inbound(b"reply");
        assert_eq!(state.recv_count, 5);
        assert_eq!(state.sent_this_round, 0);

        let third = state.observe_outbound(b"next");
        assert_eq!(state.round_count, 2);
        assert_eq!(third.stream_start, 10);
        assert_eq!(third.stream_end, 13);
    }

    #[test]
    fn session_state_tracks_udp_datagram_progress_separately() {
        let mut state = SessionState::default();

        let first = state.observe_datagram_outbound(b"hello");
        let second = state.observe_datagram_outbound(b"world!");

        assert_eq!(first.round, 1);
        assert_eq!(first.stream_start, 0);
        assert_eq!(first.stream_end, 4);
        assert_eq!(second.round, 2);
        assert_eq!(second.stream_start, 5);
        assert_eq!(second.stream_end, 10);
        assert_eq!(state.outbound_bytes_total, 11);
    }

    #[test]
    fn session_state_default_values() {
        let state = SessionState::default();
        assert_eq!(state.phase, SessionPhase::Handshake);
        assert_eq!(state.round_count, 0);
        assert_eq!(state.recv_count, 0);
        assert_eq!(state.sent_this_round, 0);
        assert_eq!(state.outbound_bytes_total, 0);
        assert!(!state.saw_tls_client_hello);
    }

    #[test]
    fn session_state_observe_outbound_empty_payload() {
        let mut state = SessionState::default();
        let progress = state.observe_outbound(b"");
        assert_eq!(state.round_count, 1);
        assert_eq!(state.sent_this_round, 0);
        assert_eq!(progress.payload_size, 0);
        assert_eq!(progress.stream_start, 0);
        assert_eq!(progress.stream_end, 0);
    }

    #[test]
    fn session_state_client_hello_prefix_capped() {
        let mut state = SessionState::default();
        let tls = ripdpi_packets::DEFAULT_FAKE_TLS;
        assert!(tls.len() > CLIENT_HELLO_PREFIX_CAP);
        state.observe_outbound(tls);
        assert!(state.saw_tls_client_hello);
        assert_eq!(state.client_hello_prefix.len(), CLIENT_HELLO_PREFIX_CAP);
        assert_eq!(&state.client_hello_prefix[..], &tls[..CLIENT_HELLO_PREFIX_CAP]);
    }

    #[test]
    fn session_state_multiple_inbound_accumulates_recv_count() {
        let mut state = SessionState::default();
        state.observe_inbound(b"first");
        state.observe_inbound(b"second");
        assert_eq!(state.recv_count, 11);
    }

    #[test]
    fn session_state_non_tls_outbound_does_not_set_tls_flag() {
        let mut state = SessionState::default();
        state.observe_outbound(b"GET / HTTP/1.1\r\n\r\n");
        assert!(!state.saw_tls_client_hello);
    }

    #[test]
    fn session_state_datagram_increments_round_every_call() {
        let mut state = SessionState::default();
        state.observe_datagram_outbound(b"a");
        state.observe_datagram_outbound(b"b");
        state.observe_datagram_outbound(b"c");
        assert_eq!(state.round_count, 3);
        assert_eq!(state.sent_this_round, 1);
    }

    #[test]
    fn session_phase_equality() {
        assert_eq!(SessionPhase::Handshake, SessionPhase::Handshake);
        assert_ne!(SessionPhase::Handshake, SessionPhase::Connected);
        assert_ne!(SessionPhase::Connected, SessionPhase::Closed);
    }
}
