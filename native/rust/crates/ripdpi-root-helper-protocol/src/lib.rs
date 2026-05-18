//! Root helper IPC protocol facade.

mod commands;
mod params;
mod scm_rights;
mod wire;

pub use commands::{
    CMD_PROBE_CAPABILITIES, CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_FAKE_RST, CMD_SEND_FAKE_TCP,
    CMD_SEND_FLAGGED_TCP_PAYLOAD, CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP,
    CMD_SEND_MULTI_DISORDER_TCP, CMD_SEND_ORDERED_TCP_SEGMENTS, CMD_SEND_RAW_IP_PACKET, CMD_SEND_SEQOVL_TCP,
    CMD_SEND_SYN_HIDE_TCP, CMD_SHUTDOWN,
};
pub use params::{
    FakeRstParams, FakeTcpParams, FlaggedTcpPayloadParams, IpFragTcpParams, IpFragUdpParams, MultiDisorderParams,
    OrderedTcpSegmentParams, OrderedTcpSegmentsParams, RawIpPacketParams, SegmentSpec, SeqOvlParams,
};
pub use scm_rights::{recv_message, send_message};
pub use wire::{valid_session_nonce, HelperRequest, HelperResponse, MAX_SESSION_NONCE_BYTES, MIN_SESSION_NONCE_BYTES};
