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

use super::{
    recv_line_with_optional_fd, CapabilityOutcome, CapabilityUnavailable, IpFragmentationCapabilities,
    RuntimeCapability, TcpFlagOverrides,
};

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
    pub fn send_fake_rst(
        &self,
        stream_fd: RawFd,
        default_ttl: u8,
        flags: TcpFlagOverrides,
        ipv4_identification: Option<u16>,
    ) -> io::Result<()> {
        let params = serde_json::json!({
            "default_ttl": default_ttl,
            "tcp_flags_set": flags.set,
            "tcp_flags_unset": flags.unset,
            "ipv4_identification": ipv4_identification,
        });
        let (_resp, _fd) = self.send_command("send_fake_rst", params, Some(stream_fd))?;
        Ok(())
    }

    pub fn send_flagged_tcp_payload(
        &self,
        stream_fd: RawFd,
        payload: &[u8],
        default_ttl: u8,
        md5sig: bool,
        flags: TcpFlagOverrides,
        ipv4_identification: Option<u16>,
    ) -> io::Result<Option<RawFd>> {
        let params = serde_json::json!({
            "payload": payload,
            "default_ttl": default_ttl,
            "md5sig": md5sig,
            "tcp_flags_set": flags.set,
            "tcp_flags_unset": flags.unset,
            "ipv4_identification": ipv4_identification,
        });
        let (_resp, fd) = self.send_command("send_flagged_tcp_payload", params, Some(stream_fd))?;
        Ok(fd)
    }

    /// Perform TCP sequence overlap via the helper. Returns replacement fd.
    pub fn send_seqovl_tcp(
        &self,
        stream_fd: RawFd,
        real_chunk: &[u8],
        fake_prefix: &[u8],
        default_ttl: u8,
        md5sig: bool,
        flags: TcpFlagOverrides,
        ipv4_identification: Option<u16>,
    ) -> io::Result<Option<RawFd>> {
        let params = serde_json::json!({
            "real_chunk": real_chunk,
            "fake_prefix": fake_prefix,
            "default_ttl": default_ttl,
            "md5sig": md5sig,
            "tcp_flags_set": flags.set,
            "tcp_flags_unset": flags.unset,
            "ipv4_identification": ipv4_identification,
        });
        let (_resp, fd) = self.send_command("send_seqovl_tcp", params, Some(stream_fd))?;
        Ok(fd)
    }

    /// Send multi-disorder TCP segments via the helper. Returns replacement fd.
    #[allow(clippy::too_many_arguments)]
    pub fn send_multi_disorder_tcp(
        &self,
        stream_fd: RawFd,
        payload: &[u8],
        segments: &[super::TcpPayloadSegment],
        default_ttl: u8,
        inter_segment_delay_ms: u32,
        md5sig: bool,
        flags: TcpFlagOverrides,
        ipv4_identifications: &[u16],
    ) -> io::Result<Option<RawFd>> {
        let seg_specs: Vec<serde_json::Value> =
            segments.iter().map(|s| serde_json::json!({ "start": s.start, "end": s.end })).collect();
        let params = serde_json::json!({
            "payload": payload,
            "segments": seg_specs,
            "default_ttl": default_ttl,
            "inter_segment_delay_ms": inter_segment_delay_ms,
            "md5sig": md5sig,
            "tcp_flags_set": flags.set,
            "tcp_flags_unset": flags.unset,
            "ipv4_identifications": ipv4_identifications,
        });
        let (_resp, fd) = self.send_command("send_multi_disorder_tcp", params, Some(stream_fd))?;
        Ok(fd)
    }

    /// Send an ordered raw TCP batch via the helper. Returns replacement fd.
    #[allow(clippy::too_many_arguments)]
    pub fn send_ordered_tcp_segments(
        &self,
        stream_fd: RawFd,
        segments: &[super::OrderedTcpSegment<'_>],
        original_payload_len: usize,
        default_ttl: u8,
        md5sig: bool,
        timestamp_delta_ticks: Option<i32>,
        ipv4_identifications: &[u16],
        wait: super::TcpStageWait,
    ) -> io::Result<Option<RawFd>> {
        let segment_specs: Vec<serde_json::Value> = segments
            .iter()
            .map(|segment| {
                serde_json::json!({
                    "payload": segment.payload,
                    "ttl": segment.ttl,
                    "tcp_flags_set": segment.flags.set,
                    "tcp_flags_unset": segment.flags.unset,
                    "sequence_offset": segment.sequence_offset,
                    "use_fake_timestamp": segment.use_fake_timestamp,
                })
            })
            .collect();
        let params = serde_json::json!({
            "segments": segment_specs,
            "original_payload_len": original_payload_len,
            "default_ttl": default_ttl,
            "md5sig": md5sig,
            "timestamp_delta_ticks": timestamp_delta_ticks,
            "ipv4_identifications": ipv4_identifications,
            "wait_enabled": wait.0,
            "wait_poll_ms": wait.1.as_millis() as u64,
        });
        let (_resp, fd) = self.send_command("send_ordered_tcp_segments", params, Some(stream_fd))?;
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
        flags: TcpFlagOverrides,
        ipv4_identification: Option<u16>,
    ) -> io::Result<Option<RawFd>> {
        let params = serde_json::json!({
            "payload": payload,
            "split_offset": split_offset,
            "default_ttl": default_ttl,
            "disorder": disorder,
            "tcp_flags_set": flags.set,
            "tcp_flags_unset": flags.unset,
            "ipv4_identification": ipv4_identification,
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
        ipv4_identification: Option<u16>,
    ) -> io::Result<()> {
        let params = serde_json::json!({
            "target_addr": target.to_string(),
            "payload": payload,
            "split_offset": split_offset,
            "default_ttl": default_ttl,
            "disorder": disorder,
            "ipv4_identification": ipv4_identification,
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
    recv_line_with_optional_fd(fd, &mut buf, &mut cmsg_buf, "helper closed connection")
}

// ---------------------------------------------------------------------------
// Typed capability conversion (Phase 2 slice 2.1)
// ---------------------------------------------------------------------------

/// Parse the JSON `data` blob returned by `CMD_PROBE_CAPABILITIES` into a
/// list of `(RuntimeCapability, CapabilityOutcome<bool>)` pairs.
///
/// The expected JSON shape is:
/// ```json
/// { "raw_ipv4": true, "raw_ipv6": false, "tcp_repair": true }
/// ```
/// Unknown keys are ignored. Missing keys produce `Unavailable { reason: NotProbed }`.
///
/// This function lives in `ripdpi-runtime` rather than `ripdpi-root-helper`
/// because `RuntimeCapability` and `CapabilityOutcome` are defined here, and
/// the dependency direction is ripdpi-root-helper → ripdpi-runtime (not the
/// reverse).
pub fn capability_outcome_from_probe_json(json: &str) -> Vec<(RuntimeCapability, CapabilityOutcome<bool>)> {
    let value: serde_json::Value = match serde_json::from_str(json) {
        Ok(v) => v,
        Err(e) => {
            let msg = e.to_string();
            return vec![
                (
                    RuntimeCapability::RawTcpFakeSend,
                    CapabilityOutcome::ProbeFailed {
                        capability: RuntimeCapability::RawTcpFakeSend,
                        error: msg.clone(),
                    },
                ),
                (
                    RuntimeCapability::RawUdpFragmentation,
                    CapabilityOutcome::ProbeFailed {
                        capability: RuntimeCapability::RawUdpFragmentation,
                        error: msg.clone(),
                    },
                ),
                (
                    RuntimeCapability::TtlWrite,
                    CapabilityOutcome::ProbeFailed { capability: RuntimeCapability::TtlWrite, error: msg },
                ),
            ];
        }
    };

    let extract = |key: &str, cap: RuntimeCapability| -> (RuntimeCapability, CapabilityOutcome<bool>) {
        match value.get(key).and_then(serde_json::Value::as_bool) {
            Some(b) => (cap, CapabilityOutcome::Available(b)),
            None => (cap, CapabilityOutcome::Unavailable { capability: cap, reason: CapabilityUnavailable::NotProbed }),
        }
    };

    vec![
        extract("raw_ipv4", RuntimeCapability::RawTcpFakeSend),
        extract("raw_ipv6", RuntimeCapability::RawUdpFragmentation),
        extract("tcp_repair", RuntimeCapability::TtlWrite),
    ]
}
