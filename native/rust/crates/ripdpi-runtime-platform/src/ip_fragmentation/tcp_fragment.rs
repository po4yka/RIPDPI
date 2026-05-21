//! Runtime-adaptation — IP-fragmented TCP emission.
//!
//! `send_ip_fragmented_tcp` and its `_reserved` variant (which takes
//! pre-reserved IPv4 identifications). The entry point tries the privileged
//! root helper first via `root_helper::with_root_helper` and otherwise falls
//! back to the local `ripdpi-privileged-ops` path; non-Linux targets return
//! `Unsupported`.
//!
//! ## Unsafe surface
//!
//! On the root-helper branch the helper may hand back a replacement
//! descriptor; it is installed over the caller's stream fd through the
//! `unsafe` `replacement_fd::swap_replacement_fd` wrapper. The single call
//! site carries a `// SAFETY:` note: the replacement fd is freshly created and
//! retained by no other path, and `stream` keeps owning its own descriptor
//! across the swap.

use std::io;
use std::net::TcpStream;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::os::fd::AsRawFd;

use ripdpi_config::IpIdMode;

#[cfg(any(target_os = "linux", target_os = "android"))]
use super::super::ipv4_ids::reserve_stream_ipv4_identifications;
#[cfg(any(target_os = "linux", target_os = "android"))]
use super::super::root_helper;
use super::super::TcpFlagOverrides;
#[cfg(any(target_os = "linux", target_os = "android"))]
use super::replacement_fd::swap_replacement_fd;

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_tcp_reserved(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_ip_fragmented_tcp(
            stream.as_raw_fd(),
            payload,
            split_offset,
            default_ttl,
            disorder,
            flags,
            ipv4_identification,
        )?;
        if let Some(replacement_fd) = res {
            // SAFETY: `replacement_fd` was just created by
            // `send_ip_fragmented_tcp` and no other code path retains it;
            // `stream` continues to own its descriptor across the call.
            unsafe { swap_replacement_fd(stream.as_raw_fd(), replacement_fd) }?;
        }
        Ok(())
    }) {
        return result;
    }

    ripdpi_privileged_ops::send_ip_fragmented_tcp(
        stream,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        flags,
        ipv4_identification,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ip_fragmented_tcp_reserved(
    _stream: &TcpStream,
    _payload: &[u8],
    _split_offset: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _disorder: bool,
    _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    _flags: TcpFlagOverrides,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_tcp(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = reserve_stream_ipv4_identifications(stream, ip_id_mode, 1)?.into_iter().next();
    send_ip_fragmented_tcp_reserved(
        stream,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        flags,
        ipv4_identification,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ip_fragmented_tcp(
    _stream: &TcpStream,
    _payload: &[u8],
    _split_offset: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _disorder: bool,
    _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
