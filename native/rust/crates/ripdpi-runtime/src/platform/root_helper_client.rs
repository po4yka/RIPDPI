//! IPC client for the privileged root helper process.
//!
//! Communicates over a Unix domain socket using JSON-line framing with
//! SCM_RIGHTS for file descriptor passing. Mirrors the protocol defined
//! in `ripdpi-root-helper/src/protocol.rs`.

use std::io;
use std::net::SocketAddr;
use std::os::fd::RawFd;
use std::os::unix::net::UnixStream;
use std::time::Duration;

use ripdpi_root_helper_protocol::{
    recv_message, send_message, FakeRstParams, FlaggedTcpPayloadParams, HelperRequest, HelperResponse, IpFragTcpParams,
    IpFragUdpParams, MultiDisorderParams, OrderedTcpSegmentParams, OrderedTcpSegmentsParams, SegmentSpec, SeqOvlParams,
    CMD_PROBE_CAPABILITIES, CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_FAKE_RST, CMD_SEND_FLAGGED_TCP_PAYLOAD,
    CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP, CMD_SEND_MULTI_DISORDER_TCP,
    CMD_SEND_ORDERED_TCP_SEGMENTS, CMD_SEND_SEQOVL_TCP, CMD_SEND_SYN_HIDE_TCP,
};

use super::{
    CapabilityOutcome, CapabilityUnavailable, IcmpWrappedUdpRecvFilter, IcmpWrappedUdpSpec,
    IpFragmentationCapabilities, ReceivedIcmpWrappedUdp, RuntimeCapability, SynHideTcpSpec, TcpFlagOverrides,
};

/// Client for communicating with the root helper process.
pub struct RootHelperClient {
    socket_path: String,
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
        let (resp, _fd) = self.send_command(CMD_PROBE_CAPABILITIES, serde_json::Value::Null, None)?;
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
        let params = command_params(FakeRstParams {
            default_ttl,
            tcp_flags_set: flags.set,
            tcp_flags_unset: flags.unset,
            ipv4_identification,
        })?;
        let (_resp, _fd) = self.send_command(CMD_SEND_FAKE_RST, params, Some(stream_fd))?;
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
        let params = command_params(FlaggedTcpPayloadParams {
            payload: payload.to_vec(),
            default_ttl,
            md5sig,
            tcp_flags_set: flags.set,
            tcp_flags_unset: flags.unset,
            ipv4_identification,
        })?;
        let (_resp, fd) = self.send_command(CMD_SEND_FLAGGED_TCP_PAYLOAD, params, Some(stream_fd))?;
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
        let params = command_params(SeqOvlParams {
            real_chunk: real_chunk.to_vec(),
            fake_prefix: fake_prefix.to_vec(),
            default_ttl,
            md5sig,
            tcp_flags_set: flags.set,
            tcp_flags_unset: flags.unset,
            ipv4_identification,
        })?;
        let (_resp, fd) = self.send_command(CMD_SEND_SEQOVL_TCP, params, Some(stream_fd))?;
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
        let segments = segments.iter().map(|s| SegmentSpec { start: s.start, end: s.end }).collect();
        let params = command_params(MultiDisorderParams {
            payload: payload.to_vec(),
            segments,
            default_ttl,
            inter_segment_delay_ms,
            md5sig,
            tcp_flags_set: flags.set,
            tcp_flags_unset: flags.unset,
            ipv4_identifications: ipv4_identifications.to_vec(),
        })?;
        let (_resp, fd) = self.send_command(CMD_SEND_MULTI_DISORDER_TCP, params, Some(stream_fd))?;
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
        let segment_specs: Vec<OrderedTcpSegmentParams> = segments
            .iter()
            .map(|segment| OrderedTcpSegmentParams {
                payload: segment.payload.to_vec(),
                ttl: segment.ttl,
                tcp_flags_set: segment.flags.set,
                tcp_flags_unset: segment.flags.unset,
                sequence_offset: segment.sequence_offset,
                use_fake_timestamp: segment.use_fake_timestamp,
            })
            .collect();
        let params = command_params(OrderedTcpSegmentsParams {
            segments: segment_specs,
            original_payload_len,
            default_ttl,
            md5sig,
            timestamp_delta_ticks,
            ipv4_identifications: ipv4_identifications.to_vec(),
            wait_enabled: wait.0,
            wait_poll_ms: wait.1.as_millis() as u64,
        })?;
        let (_resp, fd) = self.send_command(CMD_SEND_ORDERED_TCP_SEGMENTS, params, Some(stream_fd))?;
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
        let params = command_params(IpFragTcpParams {
            payload: payload.to_vec(),
            split_offset,
            default_ttl,
            disorder,
            tcp_flags_set: flags.set,
            tcp_flags_unset: flags.unset,
            ipv4_identification,
        })?;
        let (_resp, fd) = self.send_command(CMD_SEND_IP_FRAGMENTED_TCP, params, Some(stream_fd))?;
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
        let params = command_params(IpFragUdpParams {
            target_addr: target.to_string(),
            payload: payload.to_vec(),
            split_offset,
            default_ttl,
            disorder,
            ipv4_identification,
        })?;
        let (_resp, _fd) = self.send_command(CMD_SEND_IP_FRAGMENTED_UDP, params, Some(socket_fd))?;
        Ok(())
    }

    pub fn send_syn_hide_tcp(&self, spec: SynHideTcpSpec) -> io::Result<()> {
        let params = serde_json::to_value(spec)
            .map_err(|error| io::Error::other(format!("serialize syn hide spec: {error}")))?;
        let (_resp, _fd) = self.send_command(CMD_SEND_SYN_HIDE_TCP, params, None)?;
        Ok(())
    }

    pub fn send_icmp_wrapped_udp(&self, spec: &IcmpWrappedUdpSpec) -> io::Result<()> {
        let params = serde_json::to_value(spec)
            .map_err(|error| io::Error::other(format!("serialize ICMP-wrapped UDP spec: {error}")))?;
        let (_resp, _fd) = self.send_command(CMD_SEND_ICMP_WRAPPED_UDP, params, None)?;
        Ok(())
    }

    pub fn recv_icmp_wrapped_udp(&self, filter: IcmpWrappedUdpRecvFilter) -> io::Result<ReceivedIcmpWrappedUdp> {
        let params = serde_json::to_value(filter)
            .map_err(|error| io::Error::other(format!("serialize ICMP-wrapped UDP filter: {error}")))?;
        let (resp, _fd) = self.send_command(CMD_RECV_ICMP_WRAPPED_UDP, params, None)?;
        serde_json::from_value(resp.data).map_err(|error| {
            io::Error::new(io::ErrorKind::InvalidData, format!("invalid ICMP-wrapped UDP reply: {error}"))
        })
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
        send_message(&stream, &json, fd)?;

        // Receive response + optional replacement fd.
        let (resp_bytes, reply_fd) = recv_message(&stream, "helper closed connection")?;

        let response: HelperResponse = serde_json::from_slice(&resp_bytes)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("invalid response: {e}")))?;

        if !response.ok {
            let msg = response.error.unwrap_or_else(|| "unknown helper error".into());
            return Err(io::Error::other(msg));
        }

        Ok((response, reply_fd))
    }
}

fn command_params<T: serde::Serialize>(params: T) -> io::Result<serde_json::Value> {
    serde_json::to_value(params).map_err(|error| io::Error::other(format!("serialize root-helper params: {error}")))
}

// ---------------------------------------------------------------------------
// Typed capability conversion
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
/// This function lives in `ripdpi-runtime` because `RuntimeCapability` and
/// `CapabilityOutcome` are runtime/diagnostics concepts rather than helper IPC
/// wire types.
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
