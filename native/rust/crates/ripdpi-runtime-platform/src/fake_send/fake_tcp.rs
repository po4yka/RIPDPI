use std::io;
use std::net::TcpStream;

use ripdpi_config::IpIdMode;

#[cfg(any(target_os = "linux", target_os = "android"))]
use super::raw_path_ids;
use crate::{FakeTcpOptions, TcpStageWait};

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: FakeTcpOptions<'_>,
    ip_id_mode: Option<IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    let options = raw_path_ids::prepare_fake_tcp_options(stream, fake_prefix, options, ip_id_mode)?;
    ripdpi_privileged_ops::send_fake_tcp(stream, original_prefix, fake_prefix, ttl, md5sig, default_ttl, options, wait)
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_tcp(
    _stream: &TcpStream,
    _original_prefix: &[u8],
    _fake_prefix: &[u8],
    _ttl: u8,
    _md5sig: bool,
    _default_ttl: u8,
    _options: FakeTcpOptions<'_>,
    _ip_id_mode: Option<IpIdMode>,
    _wait: TcpStageWait,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
