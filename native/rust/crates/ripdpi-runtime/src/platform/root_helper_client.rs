//! IPC client for the privileged root helper process.
//!
//! Communicates over a Unix domain socket using JSON-line framing with
//! SCM_RIGHTS for file descriptor passing. Mirrors the protocol defined
//! in `ripdpi-root-helper/src/protocol.rs`.

use std::io;
use std::net::SocketAddr;
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::time::Duration;

use nix::sys::socket::{self, ControlMessage, MsgFlags};
use serde::{Deserialize, Serialize};

use super::IpFragmentationCapabilities;

/// Client for communicating with the root helper process.
pub struct RootHelperClient {
    socket_path: String,
}

// ---------------------------------------------------------------------------
// Wire types (must match ripdpi-root-helper/src/protocol.rs)
// ---------------------------------------------------------------------------

#[derive(Serialize)]
struct HelperRequest {
    command: String,
    #[serde(skip_serializing_if = "serde_json::Value::is_null")]
    params: serde_json::Value,
}

#[derive(Deserialize)]
struct HelperResponse {
    ok: bool,
    error: Option<String>,
    #[serde(default)]
    data: serde_json::Value,
}

impl RootHelperClient {
    pub fn new(socket_path: String) -> Self {
        Self { socket_path }
    }

    /// Returns the socket path this client connects to.
    pub fn socket_path(&self) -> String {
        self.socket_path.clone()
    }

    /// Probe what privileged capabilities the helper process has.
    pub fn probe_capabilities(&self) -> io::Result<IpFragmentationCapabilities> {
        let (resp, _fd) = self.send_command("probe_capabilities", serde_json::Value::Null, None)?;
        Ok(IpFragmentationCapabilities {
            raw_ipv4: resp.data.get("raw_ipv4").and_then(serde_json::Value::as_bool).unwrap_or(false),
            raw_ipv6: resp.data.get("raw_ipv6").and_then(serde_json::Value::as_bool).unwrap_or(false),
            tcp_repair: resp.data.get("tcp_repair").and_then(serde_json::Value::as_bool).unwrap_or(false),
        })
    }

    /// Send a fake RST packet via the helper.
    pub fn send_fake_rst(&self, stream_fd: RawFd, default_ttl: u8) -> io::Result<()> {
        let params = serde_json::json!({ "default_ttl": default_ttl });
        let (_resp, _fd) = self.send_command("send_fake_rst", params, Some(stream_fd))?;
        Ok(())
    }

    /// Perform TCP sequence overlap via the helper. Returns replacement fd.
    pub fn send_seqovl_tcp(
        &self,
        stream_fd: RawFd,
        real_chunk: &[u8],
        fake_prefix: &[u8],
        default_ttl: u8,
        md5sig: bool,
    ) -> io::Result<Option<RawFd>> {
        let params = serde_json::json!({
            "real_chunk": real_chunk,
            "fake_prefix": fake_prefix,
            "default_ttl": default_ttl,
            "md5sig": md5sig,
        });
        let (_resp, fd) = self.send_command("send_seqovl_tcp", params, Some(stream_fd))?;
        Ok(fd)
    }

    /// Send multi-disorder TCP segments via the helper. Returns replacement fd.
    pub fn send_multi_disorder_tcp(
        &self,
        stream_fd: RawFd,
        payload: &[u8],
        segments: &[super::TcpPayloadSegment],
        default_ttl: u8,
        inter_segment_delay_ms: u32,
        md5sig: bool,
    ) -> io::Result<Option<RawFd>> {
        let seg_specs: Vec<serde_json::Value> =
            segments.iter().map(|s| serde_json::json!({ "start": s.start, "end": s.end })).collect();
        let params = serde_json::json!({
            "payload": payload,
            "segments": seg_specs,
            "default_ttl": default_ttl,
            "inter_segment_delay_ms": inter_segment_delay_ms,
            "md5sig": md5sig,
        });
        let (_resp, fd) = self.send_command("send_multi_disorder_tcp", params, Some(stream_fd))?;
        Ok(fd)
    }

    /// Send IP-fragmented TCP via the helper. Returns replacement fd.
    pub fn send_ip_fragmented_tcp(
        &self,
        stream_fd: RawFd,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        disorder: bool,
    ) -> io::Result<Option<RawFd>> {
        let params = serde_json::json!({
            "payload": payload,
            "split_offset": split_offset,
            "default_ttl": default_ttl,
            "disorder": disorder,
        });
        let (_resp, fd) = self.send_command("send_ip_fragmented_tcp", params, Some(stream_fd))?;
        Ok(fd)
    }

    /// Send IP-fragmented UDP via the helper.
    pub fn send_ip_fragmented_udp(
        &self,
        socket_fd: RawFd,
        target: SocketAddr,
        payload: &[u8],
        split_offset: usize,
        default_ttl: u8,
        disorder: bool,
    ) -> io::Result<()> {
        let params = serde_json::json!({
            "target_addr": target.to_string(),
            "payload": payload,
            "split_offset": split_offset,
            "default_ttl": default_ttl,
            "disorder": disorder,
        });
        let (_resp, _fd) = self.send_command("send_ip_fragmented_udp", params, Some(socket_fd))?;
        Ok(())
    }

    // -----------------------------------------------------------------------
    // Internal transport
    // -----------------------------------------------------------------------

    fn send_command(
        &self,
        command: &str,
        params: serde_json::Value,
        fd: Option<RawFd>,
    ) -> io::Result<(HelperResponse, Option<RawFd>)> {
        let stream = UnixStream::connect(&self.socket_path)?;
        stream.set_read_timeout(Some(Duration::from_secs(30)))?;
        stream.set_write_timeout(Some(Duration::from_secs(10)))?;

        let request = HelperRequest { command: command.to_owned(), params };
        let json = serde_json::to_vec(&request).map_err(|e| io::Error::other(format!("serialize request: {e}")))?;

        // Send request + optional fd via SCM_RIGHTS.
        send_with_fd(&stream, &json, fd)?;

        // Receive response + optional replacement fd.
        let (resp_bytes, reply_fd) = recv_with_fd(&stream)?;

        let response: HelperResponse = serde_json::from_slice(&resp_bytes)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("invalid response: {e}")))?;

        if !response.ok {
            let msg = response.error.unwrap_or_else(|| "unknown helper error".into());
            return Err(io::Error::other(msg));
        }

        Ok((response, reply_fd))
    }
}

fn send_with_fd(stream: &UnixStream, json: &[u8], fd: Option<RawFd>) -> io::Result<()> {
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

fn recv_with_fd(stream: &UnixStream) -> io::Result<(Vec<u8>, Option<RawFd>)> {
    let fd = stream.as_raw_fd();
    let mut buf = [0u8; 8192];
    let mut cmsg_buf = [0u8; 64];

    let mut iov = libc::iovec { iov_base: buf.as_mut_ptr().cast(), iov_len: buf.len() };

    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf.as_mut_ptr().cast();
    msg.msg_controllen = cmsg_buf.len() as _;

    // Safety: `msg` references live stack storage for iov and control buffers.
    let n = unsafe { libc::recvmsg(fd, &mut msg, 0) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    if n == 0 {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "helper closed connection"));
    }
    let bytes_read = n as usize;

    let mut received_fd: Option<RawFd> = None;
    let mut cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
    while !cmsg.is_null() {
        let hdr = unsafe { &*cmsg };
        if hdr.cmsg_level == libc::SOL_SOCKET && hdr.cmsg_type == libc::SCM_RIGHTS {
            let data_ptr = unsafe { libc::CMSG_DATA(cmsg) };
            let recv_fd: RawFd = unsafe { std::ptr::read_unaligned(data_ptr.cast()) };
            received_fd = Some(recv_fd);
        }
        cmsg = unsafe { libc::CMSG_NXTHDR(&msg, cmsg) };
    }

    let data = &buf[..bytes_read];
    let data = data.strip_suffix(b"\n").unwrap_or(data);
    Ok((data.to_vec(), received_fd))
}
