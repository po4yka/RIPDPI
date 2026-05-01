use std::io;
use std::net::TcpStream;

use ripdpi_config::IpIdMode;

#[cfg(any(target_os = "linux", target_os = "android"))]
use super::{raw_path_ids, root_helper_dispatch};
use crate::TcpFlagOverrides;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_flagged_tcp_payload_reserved(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if let Some(result) =
        root_helper_dispatch::send_flagged_tcp_payload(stream, payload, default_ttl, md5sig, flags, ipv4_identification)
    {
        return result;
    }
    ripdpi_privileged_ops::send_flagged_tcp_payload(
        stream,
        payload,
        default_ttl,
        protect_path,
        md5sig,
        flags,
        ipv4_identification,
    )
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_flagged_tcp_payload_reserved(
    _stream: &TcpStream,
    _payload: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_flagged_tcp_payload(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = raw_path_ids::reserve_one_for_stream(stream, ip_id_mode)?;
    send_flagged_tcp_payload_reserved(stream, payload, default_ttl, protect_path, md5sig, flags, ipv4_identification)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_flagged_tcp_payload(
    _stream: &TcpStream,
    _payload: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
