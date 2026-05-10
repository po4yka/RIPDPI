use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FakeRstParams {
    pub default_ttl: u8,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FlaggedTcpPayloadParams {
    pub payload: Vec<u8>,
    pub default_ttl: u8,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SeqOvlParams {
    pub real_chunk: Vec<u8>,
    pub fake_prefix: Vec<u8>,
    pub default_ttl: u8,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MultiDisorderParams {
    pub payload: Vec<u8>,
    pub segments: Vec<SegmentSpec>,
    pub default_ttl: u8,
    #[serde(default)]
    pub inter_segment_delay_ms: u32,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identifications: Vec<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SegmentSpec {
    pub start: usize,
    pub end: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OrderedTcpSegmentParams {
    pub payload: Vec<u8>,
    pub ttl: u8,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    pub sequence_offset: usize,
    #[serde(default)]
    pub use_fake_timestamp: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OrderedTcpSegmentsParams {
    pub segments: Vec<OrderedTcpSegmentParams>,
    pub original_payload_len: usize,
    pub default_ttl: u8,
    #[serde(default)]
    pub md5sig: bool,
    #[serde(default)]
    pub timestamp_delta_ticks: Option<i32>,
    #[serde(default)]
    pub ipv4_identifications: Vec<u16>,
    #[serde(default)]
    pub wait_enabled: bool,
    #[serde(default)]
    pub wait_poll_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IpFragTcpParams {
    pub payload: Vec<u8>,
    pub split_offset: usize,
    pub default_ttl: u8,
    #[serde(default)]
    pub disorder: bool,
    #[serde(default)]
    pub tcp_flags_set: u16,
    #[serde(default)]
    pub tcp_flags_unset: u16,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IpFragUdpParams {
    pub target_addr: String,
    pub payload: Vec<u8>,
    pub split_offset: usize,
    pub default_ttl: u8,
    #[serde(default)]
    pub disorder: bool,
    #[serde(default)]
    pub ipv4_identification: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RawIpPacketParams {
    pub target_addr: String,
    pub packet: Vec<u8>,
}
