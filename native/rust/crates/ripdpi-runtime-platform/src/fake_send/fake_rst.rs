use std::io;
use std::net::TcpStream;

use ripdpi_config::IpIdMode;

#[cfg(any(target_os = "linux", target_os = "android"))]
use super::{raw_path_ids, root_helper_dispatch};
use crate::TcpFlagOverrides;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_rst_reserved(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if let Some(result) = root_helper_dispatch::send_fake_rst(stream, default_ttl, flags, ipv4_identification) {
        return result;
    }
    ripdpi_privileged_ops::send_fake_rst(stream, default_ttl, protect_path, flags, ipv4_identification)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_rst_reserved(
    _stream: &TcpStream,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _flags: TcpFlagOverrides,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_rst(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = raw_path_ids::reserve_one_for_stream(stream, ip_id_mode)?;
    send_fake_rst_reserved(stream, default_ttl, protect_path, flags, ipv4_identification)
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_rst(
    _stream: &TcpStream,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
