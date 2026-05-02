use std::time::Duration;

pub type TcpStageWait = (bool, Duration);

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TcpFlagOverrides {
    pub set: u16,
    pub unset: u16,
}

impl TcpFlagOverrides {
    pub const fn is_empty(self) -> bool {
        self.set == 0 && self.unset == 0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OrderedTcpSegment<'a> {
    pub payload: &'a [u8],
    pub ttl: u8,
    pub flags: TcpFlagOverrides,
    pub sequence_offset: usize,
    pub use_fake_timestamp: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FakeTcpOptions<'a> {
    pub secondary_fake_prefix: Option<&'a [u8]>,
    pub timestamp_delta_ticks: Option<i32>,
    pub protect_path: Option<&'a str>,
    pub fake_flags: TcpFlagOverrides,
    pub orig_flags: TcpFlagOverrides,
    pub require_raw_path: bool,
    pub force_raw_original: bool,
    pub ipv4_identifications: Vec<u16>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpPayloadSegment {
    pub start: usize,
    pub end: usize,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TcpActivationState {
    pub has_timestamp: Option<bool>,
    pub window_size: Option<i64>,
    pub mss: Option<i64>,
}
