use crate::TcpChainStep;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpTlsRandRecPayload {
    pub fragment_count: i32,
    pub min_fragment_size: i32,
    pub max_fragment_size: i32,
}

impl TcpChainStep {
    pub fn with_tls_randrec_payload(mut self, payload: TcpTlsRandRecPayload) -> Self {
        self.apply_tls_randrec_payload(payload);
        self
    }

    pub(crate) fn apply_tls_randrec_payload(&mut self, payload: TcpTlsRandRecPayload) {
        self.payload.set_tls_randrec_payload(payload);
    }

    pub fn tls_randrec_payload(&self) -> Option<TcpTlsRandRecPayload> {
        self.payload.tls_randrec_payload()
    }
}
