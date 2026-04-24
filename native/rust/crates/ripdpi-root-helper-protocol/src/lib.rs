use std::io;
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::net::UnixStream;

use nix::sys::socket::{self, ControlMessage, MsgFlags};
use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize)]
pub struct HelperRequest {
    pub command: String,
    #[serde(default, skip_serializing_if = "serde_json::Value::is_null")]
    pub params: serde_json::Value,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct HelperResponse {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(default)]
    pub data: serde_json::Value,
}

impl HelperResponse {
    pub fn success(data: serde_json::Value) -> Self {
        Self { ok: true, error: None, data }
    }

    pub fn error(msg: impl Into<String>) -> Self {
        Self { ok: false, error: Some(msg.into()), data: serde_json::Value::Null }
    }
}

// ---------------------------------------------------------------------------
// Command constants
// ---------------------------------------------------------------------------

/// Request the helper to probe what privileged capabilities are available.
///
/// Response `data` shape (all fields are JSON booleans):
/// ```json
/// { "raw_ipv4": true, "raw_ipv6": false, "tcp_repair": true }
/// ```
/// - `raw_ipv4`  - helper can open `AF_INET  / SOCK_RAW` sockets.
/// - `raw_ipv6`  - helper can open `AF_INET6 / SOCK_RAW` sockets.
/// - `tcp_repair` - helper can set `TCP_REPAIR` socket option.
///
/// Runtime-side conversion from this JSON shape to typed runtime capability
/// outcomes lives in `ripdpi-runtime`, where those capability types are defined.
pub const CMD_PROBE_CAPABILITIES: &str = "probe_capabilities";
pub const CMD_SEND_FAKE_RST: &str = "send_fake_rst";
pub const CMD_SEND_FLAGGED_TCP_PAYLOAD: &str = "send_flagged_tcp_payload";
pub const CMD_SEND_SEQOVL_TCP: &str = "send_seqovl_tcp";
pub const CMD_SEND_MULTI_DISORDER_TCP: &str = "send_multi_disorder_tcp";
pub const CMD_SEND_ORDERED_TCP_SEGMENTS: &str = "send_ordered_tcp_segments";
pub const CMD_SEND_IP_FRAGMENTED_TCP: &str = "send_ip_fragmented_tcp";
pub const CMD_SEND_IP_FRAGMENTED_UDP: &str = "send_ip_fragmented_udp";
pub const CMD_SEND_SYN_HIDE_TCP: &str = "send_syn_hide_tcp";
pub const CMD_SEND_ICMP_WRAPPED_UDP: &str = "send_icmp_wrapped_udp";
pub const CMD_RECV_ICMP_WRAPPED_UDP: &str = "recv_icmp_wrapped_udp";
pub const CMD_SHUTDOWN: &str = "shutdown";

// ---------------------------------------------------------------------------
// Command parameter types
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Send / receive helpers (JSON line + optional SCM_RIGHTS fd)
// ---------------------------------------------------------------------------

/// Write a JSON-line message, optionally sending one fd via SCM_RIGHTS.
pub fn send_message(stream: &UnixStream, json: &[u8], fd: Option<RawFd>) -> io::Result<()> {
    use std::io::IoSlice;

    let mut payload = Vec::with_capacity(json.len() + 1);
    payload.extend_from_slice(json);
    payload.push(b'\n');

    let iov = [IoSlice::new(&payload)];

    if let Some(fd) = fd {
        let fds = [fd];
        let cmsg = [ControlMessage::ScmRights(&fds)];
        socket::sendmsg::<()>(stream.as_raw_fd(), &iov, &cmsg, MsgFlags::empty(), None).map_err(io::Error::from)?;
    } else {
        socket::sendmsg::<()>(stream.as_raw_fd(), &iov, &[], MsgFlags::empty(), None).map_err(io::Error::from)?;
    }
    Ok(())
}

/// Read a JSON-line message, optionally receiving one fd via SCM_RIGHTS.
pub fn recv_message(stream: &UnixStream, eof_message: &'static str) -> io::Result<(Vec<u8>, Option<RawFd>)> {
    let mut buf = [0u8; 8192];
    let mut cmsg_buf = [0u8; 64];
    recv_line_with_optional_fd(stream.as_raw_fd(), &mut buf, &mut cmsg_buf, eof_message)
}

fn recv_line_with_optional_fd(
    fd: RawFd,
    buf: &mut [u8],
    cmsg_buf: &mut [u8],
    eof_message: &'static str,
) -> io::Result<(Vec<u8>, Option<RawFd>)> {
    let mut iov = libc::iovec { iov_base: buf.as_mut_ptr().cast(), iov_len: buf.len() };

    // SAFETY: zeroed bytes are a valid initial state for `msghdr` before the
    // pointer fields are explicitly populated below.
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf.as_mut_ptr().cast();
    msg.msg_controllen = cmsg_buf.len() as _;

    // SAFETY: `msg` points at live caller-owned iov and control buffers for the
    // duration of this syscall.
    let n = unsafe { libc::recvmsg(fd, &mut msg, 0) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    if n == 0 {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, eof_message));
    }

    let data = &buf[..n as usize];
    let data = data.strip_suffix(b"\n").unwrap_or(data);
    Ok((data.to_vec(), extract_scm_rights_fd(&msg)))
}

fn extract_scm_rights_fd(msg: &libc::msghdr) -> Option<RawFd> {
    // SAFETY: `msg` must describe a control buffer populated by `recvmsg`; the
    // caller owns the underlying storage for the duration of this traversal.
    let mut cmsg = unsafe { libc::CMSG_FIRSTHDR(msg) };
    while !cmsg.is_null() {
        // SAFETY: `cmsg` is either null or a valid control header pointer from
        // the `CMSG_*` traversal macros.
        let hdr = unsafe { &*cmsg };
        if hdr.cmsg_level == libc::SOL_SOCKET && hdr.cmsg_type == libc::SCM_RIGHTS {
            // SAFETY: `SCM_RIGHTS` stores at least one `RawFd` in the
            // associated control message payload.
            let data_ptr = unsafe { libc::CMSG_DATA(cmsg) };
            // SAFETY: `data_ptr` points into the live ancillary buffer owned by
            // `msg`; SCM_RIGHTS stores at least one file descriptor payload.
            let data = unsafe { std::slice::from_raw_parts(data_ptr.cast::<u8>(), std::mem::size_of::<RawFd>()) };
            return read_unaligned_raw_fd(data);
        }
        // SAFETY: advances within the same ancillary buffer described by `msg`.
        cmsg = unsafe { libc::CMSG_NXTHDR(msg, cmsg) };
    }
    None
}

fn read_unaligned_raw_fd(bytes: &[u8]) -> Option<RawFd> {
    if bytes.len() < std::mem::size_of::<RawFd>() {
        return None;
    }
    // SAFETY: `bytes` is a valid slice; we read exactly `size_of::<RawFd>()`
    // bytes from its base pointer and `read_unaligned` permits any alignment.
    Some(unsafe { std::ptr::read_unaligned(bytes.as_ptr().cast::<RawFd>()) })
}
