//! Root helper IPC protocol facade.

// `scm_rights` carries the SCM_RIGHTS `sendmsg`/`recvmsg` fd-passing path,
// the crate's only `unsafe` surface (`libc::close` on a kernel-installed fd
// and `OwnedFd::from_raw_fd` in tests). Re-enabling the workspace-deferred
// `undocumented_unsafe_blocks` and `multiple_unsafe_ops_per_block` lints
// locally turns the per-block SAFETY documentation requirement into a
// build-time error, matching the convention in `ripdpi-vless`,
// `ripdpi-io-uring`, `ripdpi-privileged-ops`, and `ripdpi-proxy-runtime`.
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

mod command_descriptor;
mod commands;
mod params;
mod scm_rights;
mod wire;

pub use command_descriptor::{
    COMMAND_DESCRIPTORS, CommandDescriptor, DescriptorValidationError, command_descriptor, validate_request,
};
pub use commands::{
    CMD_PROBE_CAPABILITIES, CMD_PROTOCOL_PREFLIGHT, CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_FAKE_RST, CMD_SEND_FAKE_TCP,
    CMD_SEND_FLAGGED_TCP_PAYLOAD, CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP,
    CMD_SEND_MULTI_DISORDER_TCP, CMD_SEND_ORDERED_TCP_SEGMENTS, CMD_SEND_RAW_IP_PACKET, CMD_SEND_SEQOVL_TCP,
    CMD_SEND_SYN_HIDE_TCP, CMD_SHUTDOWN,
};
pub use params::{
    FakeRstParams, FakeTcpParams, FlaggedTcpPayloadParams, IpFragTcpParams, IpFragUdpParams, MultiDisorderParams,
    OrderedTcpSegmentParams, OrderedTcpSegmentsParams, RawIpPacketParams, SegmentSpec, SeqOvlParams,
};
pub use scm_rights::{recv_message, send_message};
pub use wire::{
    CAPABILITY_VERSION, HelperRequest, HelperResponse, MAX_SESSION_NONCE_BYTES, MIN_SESSION_NONCE_BYTES,
    PROTOCOL_VERSION, ProtocolVersionError, valid_session_nonce,
};
