use super::{TcpFakeOrdering, TcpFakePayload};
use crate::TcpChainStep;

impl TcpChainStep {
    pub fn with_fake_payload(mut self, payload: TcpFakePayload) -> Self {
        self.apply_fake_payload(payload);
        self
    }

    pub(crate) fn apply_fake_payload(&mut self, payload: TcpFakePayload) {
        self.payload.set_fake_ordering(payload.ordering);
        self.payload.set_fake_flag_overrides(payload.fake_flags);
        self.payload.set_original_flag_overrides(payload.original_flags);
    }

    pub fn fake_payload(&self) -> Option<TcpFakePayload> {
        self.payload.fake_payload()
    }

    pub const fn fake_ordering(&self) -> TcpFakeOrdering {
        self.payload.fake_ordering()
    }
}
