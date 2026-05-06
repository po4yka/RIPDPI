use crate::{TcpChainStep, TcpChainStepKind};

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

    pub fn apply_tls_randrec_payload(&mut self, payload: TcpTlsRandRecPayload) {
        self.fragment_count = payload.fragment_count;
        self.min_fragment_size = payload.min_fragment_size;
        self.max_fragment_size = payload.max_fragment_size;
    }

    pub fn tls_randrec_payload(&self) -> Option<TcpTlsRandRecPayload> {
        if self.kind == TcpChainStepKind::TlsRandRec {
            Some(TcpTlsRandRecPayload {
                fragment_count: self.fragment_count,
                min_fragment_size: self.min_fragment_size,
                max_fragment_size: self.max_fragment_size,
            })
        } else {
            None
        }
    }
}
