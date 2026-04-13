use std::ops::Range;

use ripdpi_config::{ActivationFilter, EntropyMode, NumericRange, OffsetBase, QuicFakeProfile, TcpChainStepKind};
use ripdpi_ipfrag::Ipv6ExtHeaders;
use ripdpi_packets::{HttpMarkerInfo, TlsMarkerInfo};

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct TlsProtoInfo {
    pub markers: TlsMarkerInfo,
    pub host_bytes: Box<[u8]>,
    pub host_spans: Box<[Range<usize>]>,
}

impl TlsProtoInfo {
    pub fn host_start(&self) -> usize {
        self.host_spans.first().map_or(self.markers.host_start, |span| span.start)
    }

    pub fn host_end(&self) -> usize {
        self.host_spans.last().map_or(self.markers.host_end, |span| span.end)
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ProtoInfo {
    pub kind: u32,
    pub(crate) http: Option<HttpMarkerInfo>,
    pub(crate) tls: Option<TlsProtoInfo>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ActivationTransport {
    Tcp,
    Udp,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpSegmentHint {
    pub snd_mss: Option<i64>,
    pub advmss: Option<i64>,
    pub pmtu: Option<i64>,
    pub ip_header_overhead: i64,
}

impl TcpSegmentHint {
    pub fn adaptive_budget(self) -> i64 {
        if self.snd_mss.is_some_and(|value| value >= 64) {
            return self.snd_mss.unwrap_or(1448);
        }
        if self.advmss.is_some_and(|value| value >= 64) {
            return self.advmss.unwrap_or(1448);
        }
        if let Some(value) = self.pmtu {
            let adjusted = value.saturating_sub(self.ip_header_overhead);
            if adjusted > 0 {
                return adjusted;
            }
        }
        1448
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ActivationContext {
    pub round: i64,
    pub payload_size: i64,
    pub stream_start: i64,
    pub stream_end: i64,
    pub seqovl_supported: bool,
    pub transport: ActivationTransport,
    pub tcp_segment_hint: Option<TcpSegmentHint>,
    pub tcp_state: ActivationTcpState,
    pub resolved_fake_ttl: Option<u8>,
    pub adaptive: AdaptivePlannerHints,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ActivationTcpState {
    pub has_timestamp: Option<bool>,
    pub has_ech: Option<bool>,
    pub window_size: Option<i64>,
    pub mss: Option<i64>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct AdaptivePlannerHints {
    pub split_offset_base: Option<OffsetBase>,
    pub tls_record_offset_base: Option<OffsetBase>,
    pub tlsrandrec_profile: Option<AdaptiveTlsRandRecProfile>,
    pub udp_burst_profile: Option<AdaptiveUdpBurstProfile>,
    pub quic_fake_profile: Option<QuicFakeProfile>,
    pub entropy_mode: Option<EntropyMode>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum AdaptiveTlsRandRecProfile {
    #[default]
    Balanced,
    Tight,
    Wide,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum AdaptiveUdpBurstProfile {
    #[default]
    Balanced,
    Conservative,
    Aggressive,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlannedStep {
    pub kind: TcpChainStepKind,
    pub start: i64,
    pub end: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HostFakeSpan {
    pub host_start: usize,
    pub host_end: usize,
    pub midhost: Option<usize>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesyncAction {
    Write(Vec<u8>),
    WriteIpFragmentedTcp {
        bytes: Vec<u8>,
        split_offset: usize,
        disorder: bool,
        ipv6_ext: Ipv6ExtHeaders,
    },
    WriteIpFragmentedUdp {
        bytes: Vec<u8>,
        split_offset: usize,
        disorder: bool,
        ipv6_ext: Ipv6ExtHeaders,
    },
    WriteSeqOverlap {
        real_chunk: Vec<u8>,
        fake_prefix: Vec<u8>,
        remainder: Vec<u8>,
    },
    WriteUrgent {
        prefix: Vec<u8>,
        urgent_byte: u8,
    },
    SetTtl(u8),
    RestoreDefaultTtl,
    SetMd5Sig {
        key_len: u16,
    },
    AttachDropSack,
    DetachDropSack,
    AwaitWritable,
    SetWindowClamp(u32),
    RestoreWindowClamp,
    SetWsize {
        window: u32,
    },
    RestoreWsize,
    SendFakeRst,
    /// Sleep for the given number of milliseconds between steps.
    Delay(u16),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TamperResult {
    pub bytes: Vec<u8>,
    pub proto: ProtoInfo,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FakePacketPlan {
    pub bytes: Vec<u8>,
    pub fake_offset: usize,
    pub proto: ProtoInfo,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncPlan {
    pub tampered: Vec<u8>,
    pub steps: Vec<PlannedStep>,
    pub proto: ProtoInfo,
    pub actions: Vec<DesyncAction>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesyncError;

fn range_contains(range: NumericRange<i64>, value: i64) -> bool {
    value >= range.start && value <= range.end
}

fn range_overlaps(range: NumericRange<i64>, start: i64, end: i64) -> bool {
    range.end >= start && range.start <= end
}

pub fn activation_filter_matches(filter: Option<ActivationFilter>, context: ActivationContext) -> bool {
    let Some(filter) = filter else {
        return true;
    };
    filter.round.is_none_or(|range| range_contains(range, context.round))
        && filter.payload_size.is_none_or(|range| range_contains(range, context.payload_size))
        && filter.stream_bytes.is_none_or(|range| range_overlaps(range, context.stream_start, context.stream_end))
        && filter.tcp_has_timestamp.is_none_or(|expected| {
            context.transport == ActivationTransport::Tcp && context.tcp_state.has_timestamp == Some(expected)
        })
        && filter.tcp_has_ech.is_none_or(|expected| {
            context.transport == ActivationTransport::Tcp && context.tcp_state.has_ech == Some(expected)
        })
        && filter.tcp_window_below.is_none_or(|threshold| {
            context.transport == ActivationTransport::Tcp
                && context.tcp_state.window_size.is_some_and(|value| value < i64::from(threshold))
        })
        && filter.tcp_mss_below.is_none_or(|threshold| {
            context.transport == ActivationTransport::Tcp
                && context.tcp_state.mss.is_some_and(|value| value < i64::from(threshold))
        })
}
