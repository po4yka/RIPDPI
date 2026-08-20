//! Runtime-adaptation — IP-fragmented UDP emission.
//!
//! `send_ip_fragmented_udp` (plus its `_reserved` variant, which takes
//! pre-reserved IPv4 identifications). Like the sibling `tcp` module it tries
//! the privileged root helper first and otherwise falls back to the local
//! `ripdpi-privileged-ops` path; non-Linux targets return `Unsupported`. This
//! module issues no `unsafe` of its own — the UDP path needs no descriptor
//! swap.

use std::io;
use std::net::{SocketAddr, UdpSocket};
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::os::fd::AsFd;

use ripdpi_config::IpIdMode;

#[cfg(any(target_os = "linux", target_os = "android"))]
use super::super::ipv4_ids::reserve_udp_ipv4_identifications;
#[cfg(any(target_os = "linux", target_os = "android"))]
use super::super::root_helper;

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_udp_reserved(
    upstream: &UdpSocket,
    target: SocketAddr,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        h.send_ip_fragmented_udp_with_ipv6_ext(
            upstream.as_fd(),
            target,
            payload,
            split_offset,
            default_ttl,
            disorder,
            ipv6_ext,
            ipv4_identification,
        )
    }) {
        return result;
    }

    ripdpi_privileged_ops::send_ip_fragmented_udp(
        upstream,
        target,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        ipv4_identification,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ip_fragmented_udp_reserved(
    _upstream: &UdpSocket,
    _target: SocketAddr,
    _payload: &[u8],
    _split_offset: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _disorder: bool,
    _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_udp(
    upstream: &UdpSocket,
    target: SocketAddr,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = reserve_udp_ipv4_identifications(upstream, target, ip_id_mode, 1)?.into_iter().next();
    send_ip_fragmented_udp_reserved(
        upstream,
        target,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        ipv4_identification,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ip_fragmented_udp(
    _upstream: &UdpSocket,
    _target: SocketAddr,
    _payload: &[u8],
    _split_offset: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _disorder: bool,
    _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
