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
        self.overlap_size = payload.overlap_size;
        self.seqovl_fake_mode = payload.fake_mode;
        self.tcp_flags_set = payload.fake_flags.set;
        self.tcp_flags_unset = payload.fake_flags.unset;
    }

    pub fn seq_overlap_payload(&self) -> Option<TcpSeqOverlapPayload> {
        if self.kind == TcpChainStepKind::SeqOverlap {
            Some(TcpSeqOverlapPayload {
                overlap_size: self.overlap_size,
                fake_mode: self.seqovl_fake_mode,
                fake_flags: self.fake_flag_overrides(),
            })
        } else {
            None
        }
    }

    pub(crate) const fn seq_overlap_storage_active(&self) -> bool {
        self.overlap_size != 0 || !matches!(self.seqovl_fake_mode, SeqOverlapFakeMode::Profile)
    }
}
