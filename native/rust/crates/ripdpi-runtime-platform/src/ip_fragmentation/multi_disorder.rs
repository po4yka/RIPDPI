//! Runtime-adaptation — multi-disorder TCP emission.
//!
//! `send_multi_disorder_tcp` and its `_reserved` variant (which takes
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
use super::super::{TcpFlagOverrides, TcpPayloadSegment};
#[cfg(any(target_os = "linux", target_os = "android"))]
use super::replacement_fd::swap_replacement_fd;

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_multi_disorder_tcp_reserved(
    stream: &TcpStream,
    payload: &[u8],
    segments: &[TcpPayloadSegment],
    default_ttl: u8,
    protect_path: Option<&str>,
    inter_segment_delay_ms: u32,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identifications: &[u16],
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_multi_disorder_tcp(
            stream.as_raw_fd(),
            payload,
            segments,
            default_ttl,
            inter_segment_delay_ms,
            md5sig,
            flags,
            ipv4_identifications,
        )?;
        if let Some(replacement_fd) = res {
            // SAFETY: `replacement_fd` was just created by
            // `send_multi_disorder_tcp` and no other code path retains it;
            // `stream` continues to own its descriptor across the call.
            unsafe { swap_replacement_fd(stream.as_raw_fd(), replacement_fd) }?;
        }
        Ok(())
    }) {
        return result;
    }

    ripdpi_privileged_ops::send_multi_disorder_tcp(
        stream,
        payload,
        segments,
        default_ttl,
        protect_path,
        inter_segment_delay_ms,
        md5sig,
        flags,
        ipv4_identifications,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_multi_disorder_tcp_reserved(
    _stream: &TcpStream,
    _payload: &[u8],
    _segments: &[TcpPayloadSegment],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _inter_segment_delay_ms: u32,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ipv4_identifications: &[u16],
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_multi_disorder_tcp(
    stream: &TcpStream,
    payload: &[u8],
    segments: &[TcpPayloadSegment],
    default_ttl: u8,
    protect_path: Option<&str>,
    inter_segment_delay_ms: u32,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identifications = reserve_stream_ipv4_identifications(stream, ip_id_mode, segments.len())?;
    send_multi_disorder_tcp_reserved(
        stream,
        payload,
        segments,
        default_ttl,
        protect_path,
        inter_segment_delay_ms,
        md5sig,
        flags,
        &ipv4_identifications,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_multi_disorder_tcp(
    _stream: &TcpStream,
    _payload: &[u8],
    _segments: &[TcpPayloadSegment],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _inter_segment_delay_ms: u32,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
