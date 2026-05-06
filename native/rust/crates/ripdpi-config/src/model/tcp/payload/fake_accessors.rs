use super::{TcpFakeOrdering, TcpFakePayload};
use crate::TcpChainStep;

impl TcpChainStep {
    pub fn with_fake_payload(mut self, payload: TcpFakePayload) -> Self {
        self.apply_fake_payload(payload);
        self
    }

    pub fn apply_fake_payload(&mut self, payload: TcpFakePayload) {
        self.fake_order = payload.ordering.order;
        self.fake_seq_mode = payload.ordering.seq_mode;
        self.tcp_flags_set = payload.fake_flags.set;
        self.tcp_flags_unset = payload.fake_flags.unset;
        self.tcp_flags_orig_set = payload.original_flags.set;
        self.tcp_flags_orig_unset = payload.original_flags.unset;
    }

    pub fn fake_payload(&self) -> Option<TcpFakePayload> {
        if self.kind.supports_fake_ordering() {
            Some(TcpFakePayload {
                ordering: self.fake_ordering(),
                fake_flags: self.fake_flag_overrides(),
                original_flags: self.original_flag_overrides(),
            })
        } else {
            None
        }
    }

    pub const fn fake_ordering(&self) -> TcpFakeOrdering {
        TcpFakeOrdering { order: self.fake_order, seq_mode: self.fake_seq_mode }
    }
}
