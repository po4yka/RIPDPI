use std::io;
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::net::UnixStream;

use nix::sys::socket::{self, ControlMessage, MsgFlags};
use ripdpi_runtime::platform::recv_line_with_optional_fd;
use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize)]
pub struct HelperRequest {
    pub command: String,
    #[serde(default)]
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

pub const CMD_PROBE_CAPABILITIES: &str = "probe_capabilities";
pub const CMD_SEND_FAKE_RST: &str = "send_fake_rst";
pub const CMD_SEND_FLAGGED_TCP_PAYLOAD: &str = "send_flagged_tcp_payload";
pub const CMD_SEND_SEQOVL_TCP: &str = "send_seqovl_tcp";
pub const CMD_SEND_MULTI_DISORDER_TCP: &str = "send_multi_disorder_tcp";
pub const CMD_SEND_ORDERED_TCP_SEGMENTS: &str = "send_ordered_tcp_segments";
pub const CMD_SEND_IP_FRAGMENTED_TCP: &str = "send_ip_fragmented_tcp";
pub const CMD_SEND_IP_FRAGMENTED_UDP: &str = "send_ip_fragmented_udp";
pub const CMD_SHUTDOWN: &str = "shutdown";

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
pub fn recv_message(stream: &UnixStream) -> io::Result<(Vec<u8>, Option<RawFd>)> {
    let fd = stream.as_raw_fd();
    let mut buf = [0u8; 8192];
    let mut cmsg_buf = [0u8; 64]; // enough for one SCM_RIGHTS fd
    recv_line_with_optional_fd(fd, &mut buf, &mut cmsg_buf, "peer closed connection")
}
