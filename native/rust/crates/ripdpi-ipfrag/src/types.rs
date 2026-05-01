use std::net::SocketAddr;

use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IpFragmentPair {
    pub first: Vec<u8>,
    pub second: Vec<u8>,
    pub effective_transport_split: usize,
}

/// IPv6 extension headers to inject into fragment packets.
///
/// Headers in the unfragmentable part are placed before the Fragment Header
/// in this order: Hop-by-Hop -> Destination Options -> Routing -> Fragment.
/// The fragmentable Destination Options header is placed after the Fragment
/// Header and before the transport payload.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Ipv6ExtHeaders {
    /// Insert a Hop-by-Hop Options header (next_header=0) with pad bytes.
    pub hop_by_hop: bool,
    /// Insert Destination Options header in unfragmentable part (before Fragment Header).
    pub dest_opt: bool,
    /// Insert Destination Options header in fragmentable part (after Fragment Header).
    pub dest_opt_fragmentable: bool,
    /// Insert Routing header (type 0, segments_left=0) in unfragmentable part.
    pub routing: bool,
    /// Override the second fragment's Fragment Header `next_header` field.
    /// Per RFC 8200, only the first fragment's value is used for reassembly.
    /// Setting this confuses DPI that checks per-fragment protocol types.
    pub second_frag_next_override: Option<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UdpFragmentSpec {
    pub src: SocketAddr,
    pub dst: SocketAddr,
    pub ttl: u8,
    pub identification: u32,
    pub ipv6_ext: Ipv6ExtHeaders,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpFragmentSpec {
    pub src: SocketAddr,
    pub dst: SocketAddr,
    pub ttl: u8,
    pub identification: u32,
    pub sequence_number: u32,
    pub acknowledgment_number: u32,
    pub window_size: u16,
    pub timestamp: Option<TcpTimestampOption>,
    pub tcp_flags_set: u16,
    pub tcp_flags_unset: u16,
    pub ipv6_ext: Ipv6ExtHeaders,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpTimestampOption {
    pub value: u32,
    pub echo_reply: u32,
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum BuildError {
    #[error("source and destination socket addresses must use the same IP family")]
    AddressFamilyMismatch,
    #[error("minimum split {requested} rounds to {effective}, which does not leave two non-empty IP fragments for transport length {transport_len}")]
    InvalidSplit { requested: usize, effective: usize, transport_len: usize },
    #[error("fragment payload exceeds protocol limits")]
    ValueTooLarge,
}
