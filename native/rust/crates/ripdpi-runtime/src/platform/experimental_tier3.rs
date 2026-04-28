//! Tier-3 evasion platform primitives (SYN hide, ICMP-wrapped UDP).
//!
//! **Status:** Internal platform primitives. NOT wired through `DesyncMode`
//! and NOT exposed via UI. See
//! `docs/architecture/adr-006-tier3-desync-platform-primitives.md` for the
//! rationale and the conditions required for future activation.
//!
//! API stability: these functions are re-exported from `platform/mod.rs` with
//! `pub(crate)` visibility only. External callers MUST NOT depend on these
//! signatures — they may change without notice as part of the wire-up epic.

use std::io;
use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

use serde::{Deserialize, Serialize};

#[cfg(any(target_os = "linux", target_os = "android"))]
use super::{linux, root_helper};

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
const ICMP_UDP_MAGIC: [u8; 4] = *b"RDP1";
#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
const ICMP_UDP_VERSION: u8 = 1;
const ICMP_UDP_FLAG_REPLY: u8 = 0x01;
#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
const ICMP_UDP_FLAG_XOR: u8 = 0x02;

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SynHideMarkerKind {
    ReservedX2,
    UrgentPtr,
    TimestampEcho,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum IcmpWrappedUdpRole {
    ClientRequest,
    ServerReply,
}

impl IcmpWrappedUdpRole {
    pub const fn as_flag(self) -> u8 {
        match self {
            Self::ClientRequest => 0,
            Self::ServerReply => ICMP_UDP_FLAG_REPLY,
        }
    }

    pub const fn from_flags(flags: u8) -> Self {
        if flags & ICMP_UDP_FLAG_REPLY != 0 {
            Self::ServerReply
        } else {
            Self::ClientRequest
        }
    }
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SynHideTcpSpec {
    pub source: SocketAddr,
    pub target: SocketAddr,
    pub ttl: u8,
    pub sequence_number: u32,
    pub window_size: u16,
    pub marker_kind: SynHideMarkerKind,
    pub marker_value: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ipv4_identification: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct IcmpWrappedUdpSpec {
    pub peer: SocketAddr,
    pub service_port: u16,
    pub payload: Vec<u8>,
    pub session_id: u32,
    pub icmp_code: u8,
    pub ttl: u8,
    pub role: IcmpWrappedUdpRole,
    #[serde(default)]
    pub xor_payload: bool,
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct IcmpWrappedUdpRecvFilter {
    pub bind_ip: IpAddr,
    pub session_id: Option<u32>,
    pub expected_code: Option<u8>,
    pub expected_role: Option<IcmpWrappedUdpRole>,
    pub timeout_ms: u64,
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
impl IcmpWrappedUdpRecvFilter {
    pub fn timeout(self) -> Duration {
        Duration::from_millis(self.timeout_ms.max(1))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ReceivedIcmpWrappedUdp {
    pub peer: SocketAddr,
    pub service_port: u16,
    pub payload: Vec<u8>,
    pub session_id: u32,
    pub icmp_code: u8,
    pub role: IcmpWrappedUdpRole,
    #[serde(default)]
    pub xor_payload: bool,
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub fn send_syn_hide_tcp(spec: SynHideTcpSpec, protect_path: Option<&str>) -> io::Result<()> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        if let Some(result) = root_helper::with_root_helper(|helper| helper.send_syn_hide_tcp(spec)) {
            return result;
        }
        linux::send_syn_hide_tcp(&spec, protect_path)
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        let _ = (spec, protect_path);
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub fn send_icmp_wrapped_udp(spec: &IcmpWrappedUdpSpec, protect_path: Option<&str>) -> io::Result<()> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        if let Some(result) = root_helper::with_root_helper(|helper| helper.send_icmp_wrapped_udp(spec)) {
            return result;
        }
        linux::send_icmp_wrapped_udp(spec, protect_path)
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        let _ = (spec, protect_path);
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub fn recv_icmp_wrapped_udp(
    filter: IcmpWrappedUdpRecvFilter,
    protect_path: Option<&str>,
) -> io::Result<ReceivedIcmpWrappedUdp> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        if let Some(result) = root_helper::with_root_helper(|helper| helper.recv_icmp_wrapped_udp(filter)) {
            return result;
        }
        linux::recv_icmp_wrapped_udp(filter, protect_path)
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        let _ = (filter, protect_path);
        Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
    }
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub(crate) fn encode_icmp_wrapped_udp_envelope(spec: &IcmpWrappedUdpSpec) -> io::Result<Vec<u8>> {
    let payload_len = u16::try_from(spec.payload.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "ICMP-wrapped UDP payload exceeds 65535 bytes"))?;
    let mut encoded = spec.payload.clone();
    if spec.xor_payload {
        xor_payload(&mut encoded, spec.session_id);
    }

    let mut out = Vec::with_capacity(16 + encoded.len());
    out.extend_from_slice(&ICMP_UDP_MAGIC);
    out.push(ICMP_UDP_VERSION);
    out.push(spec.role.as_flag() | if spec.xor_payload { ICMP_UDP_FLAG_XOR } else { 0 });
    out.extend_from_slice(&spec.service_port.to_be_bytes());
    out.extend_from_slice(&spec.session_id.to_be_bytes());
    out.extend_from_slice(&payload_len.to_be_bytes());
    out.extend_from_slice(&encoded);
    Ok(out)
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
pub(crate) fn decode_icmp_wrapped_udp_envelope(
    peer_ip: IpAddr,
    icmp_code: u8,
    payload: &[u8],
) -> io::Result<ReceivedIcmpWrappedUdp> {
    if payload.len() < 14 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ICMP-wrapped UDP envelope too short"));
    }
    if payload[..4] != ICMP_UDP_MAGIC {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ICMP-wrapped UDP magic mismatch"));
    }
    if payload[4] != ICMP_UDP_VERSION {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ICMP-wrapped UDP version mismatch"));
    }
    let flags = payload[5];
    let service_port = u16::from_be_bytes([payload[6], payload[7]]);
    let session_id = u32::from_be_bytes([payload[8], payload[9], payload[10], payload[11]]);
    let payload_len = usize::from(u16::from_be_bytes([payload[12], payload[13]]));
    if payload.len() != 14 + payload_len {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ICMP-wrapped UDP payload length mismatch"));
    }
    let mut decoded = payload[14..].to_vec();
    let payload_is_xored = flags & ICMP_UDP_FLAG_XOR != 0;
    if payload_is_xored {
        xor_payload(&mut decoded, session_id);
    }

    Ok(ReceivedIcmpWrappedUdp {
        peer: SocketAddr::new(peer_ip, service_port),
        service_port,
        payload: decoded,
        session_id,
        icmp_code,
        role: IcmpWrappedUdpRole::from_flags(flags),
        xor_payload: payload_is_xored,
    })
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
fn xor_payload(payload: &mut [u8], session_id: u32) {
    let key = session_id.to_be_bytes();
    for (index, byte) in payload.iter_mut().enumerate() {
        *byte ^= key[index % key.len()];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn icmp_wrapped_udp_envelope_round_trips_plain_payload() {
        let spec = IcmpWrappedUdpSpec {
            peer: "203.0.113.10:0".parse().expect("peer"),
            service_port: 443,
            payload: b"hello over icmp".to_vec(),
            session_id: 0x0102_0304,
            icmp_code: 199,
            ttl: 64,
            role: IcmpWrappedUdpRole::ClientRequest,
            xor_payload: false,
        };

        let envelope = encode_icmp_wrapped_udp_envelope(&spec).expect("encode");
        let decoded = decode_icmp_wrapped_udp_envelope(spec.peer.ip(), spec.icmp_code, &envelope).expect("decode");

        assert_eq!(decoded.peer, SocketAddr::new(spec.peer.ip(), spec.service_port));
        assert_eq!(decoded.service_port, spec.service_port);
        assert_eq!(decoded.payload, spec.payload);
        assert_eq!(decoded.session_id, spec.session_id);
        assert_eq!(decoded.role, spec.role);
        assert_eq!(decoded.icmp_code, spec.icmp_code);
        assert!(!decoded.xor_payload);
    }

    #[test]
    fn icmp_wrapped_udp_envelope_round_trips_xored_payload() {
        let spec = IcmpWrappedUdpSpec {
            peer: "[2001:db8::10]:0".parse().expect("peer"),
            service_port: 8443,
            payload: b"secret tunnel payload".to_vec(),
            session_id: 0x0bad_cafe,
            icmp_code: 17,
            ttl: 41,
            role: IcmpWrappedUdpRole::ServerReply,
            xor_payload: true,
        };

        let envelope = encode_icmp_wrapped_udp_envelope(&spec).expect("encode");
        assert_ne!(&envelope[14..], spec.payload.as_slice(), "encoded payload should be obfuscated");

        let decoded = decode_icmp_wrapped_udp_envelope(spec.peer.ip(), spec.icmp_code, &envelope).expect("decode");
        assert_eq!(decoded.payload, spec.payload);
        assert_eq!(decoded.role, spec.role);
        assert!(decoded.xor_payload);
    }

    #[test]
    fn icmp_wrapped_udp_decode_rejects_bad_magic() {
        let err = decode_icmp_wrapped_udp_envelope(
            "198.51.100.25".parse().expect("ip"),
            199,
            b"NOPE\x01\x00\x01\xbb\x00\x00\x00\x01\x00\x00",
        )
        .expect_err("bad magic must fail");

        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    }
}
