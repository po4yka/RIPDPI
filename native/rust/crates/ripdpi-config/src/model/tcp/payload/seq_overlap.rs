use super::TcpFlagOverrides;
use crate::SeqOverlapFakeMode;

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
