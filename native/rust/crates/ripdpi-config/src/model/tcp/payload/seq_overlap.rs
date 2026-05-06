use super::TcpFlagOverrides;
use crate::{SeqOverlapFakeMode, TcpChainStep, TcpChainStepKind};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpSeqOverlapPayload {
    pub overlap_size: i32,
    pub fake_mode: SeqOverlapFakeMode,
    pub fake_flags: TcpFlagOverrides,
}

impl TcpSeqOverlapPayload {
    pub const fn profile(overlap_size: i32) -> Self {
        Self { overlap_size, fake_mode: SeqOverlapFakeMode::Profile, fake_flags: TcpFlagOverrides::disabled() }
    }
}

impl TcpChainStep {
    pub fn with_seq_overlap_payload(mut self, payload: TcpSeqOverlapPayload) -> Self {
        self.apply_seq_overlap_payload(payload);
        self
    }

    pub fn apply_seq_overlap_payload(&mut self, payload: TcpSeqOverlapPayload) {
        if self.kind() != TcpChainStepKind::SeqOverlap
            && (payload.overlap_size != 0
                || !matches!(payload.fake_mode, SeqOverlapFakeMode::Profile)
                || payload.fake_flags != TcpFlagOverrides::disabled())
        {
            self.record_payload_violation("sequence-overlap");
        }
        self.payload.set_seq_overlap_payload(payload);
    }

    pub fn seq_overlap_payload(&self) -> Option<TcpSeqOverlapPayload> {
        self.payload.seq_overlap_payload()
    }

    pub(crate) const fn seq_overlap_storage_active(&self) -> bool {
        self.payload.seq_overlap_storage_active()
    }
}
