//! High-level blocking tunnel API consumed by the Rust CLI and JNI layer.
//!
//! Callers supply a raw TUN file descriptor (already opened and owned by the
//! platform — e.g. Android VPN service) and a parsed `Config`.  This module
//! sets up the smoltcp networking stack and delegates to `io_loop_task`.

use std::io;
use std::net::IpAddr;
use std::os::unix::io::FromRawFd;
use std::sync::Arc;

use smoltcp::iface::{Config as IfaceConfig, Interface, SocketSet};
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr};
use tokio_util::sync::CancellationToken;

use hs5t_config::Config;

use crate::{io_loop_task, ActiveSessions, Stats, TunDevice};

fn parse_tunnel_address(value: &str) -> Option<(IpAddress, u8)> {
    let (ip_part, prefix_part) = value.split_once('/').map_or((value, None), |(ip, prefix)| (ip, Some(prefix)));
    let prefix = prefix_part.and_then(|raw| raw.parse::<u8>().ok());

    match ip_part.parse::<IpAddr>().ok()? {
        IpAddr::V4(ip) => {
            let octets = ip.octets();
            Some((IpAddress::v4(octets[0], octets[1], octets[2], octets[3]), prefix.unwrap_or(24)))
        }
        IpAddr::V6(ip) => {
            let segments = ip.segments();
            Some((
                IpAddress::v6(
                    segments[0],
                    segments[1],
                    segments[2],
                    segments[3],
                    segments[4],
                    segments[5],
                    segments[6],
                    segments[7],
                ),
                prefix.unwrap_or(128),
            ))
        }
    }
}

/// Start the tunnel with a parsed config and a raw TUN file descriptor.
///
/// This async function runs until `cancel` is triggered or an IO error occurs.
/// On success it returns `Ok(())`.
///
/// # Safety preconditions (upheld by caller)
///
/// - `tun_fd` is a valid, open file descriptor.
/// - Ownership of `tun_fd` transfers to this function; the caller MUST NOT
///   close or read/write it after calling `run_tunnel`.
/// - The function must be called from within a Tokio runtime context.
pub async fn run_tunnel(
    config: Arc<Config>,
    tun_fd: i32,
    cancel: CancellationToken,
    stats: Arc<Stats>,
) -> io::Result<()> {
    // Set the fd to non-blocking so AsyncFd can register it with the reactor.
    //
    // SAFETY: `tun_fd` is a valid, open fd; F_GETFL / F_SETFL are safe
    // to call on any fd and do not transfer ownership.
    let flags = unsafe { libc::fcntl(tun_fd, libc::F_GETFL, 0) };
    if flags == -1 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: same fd, same safety rationale as F_GETFL above.
    let rc = unsafe { libc::fcntl(tun_fd, libc::F_SETFL, flags | libc::O_NONBLOCK) };
    if rc == -1 {
        return Err(io::Error::last_os_error());
    }

    // SAFETY: `tun_fd` is valid and its ownership transfers to `file`.
    let file = unsafe { std::fs::File::from_raw_fd(tun_fd) };
    let tun_async = tokio::io::unix::AsyncFd::new(file)?;

    let mtu = config.tunnel.mtu as usize;
    let mut device = TunDevice::new(mtu);

    // Initialise the smoltcp interface.  `set_any_ip(true)` makes smoltcp
    // accept packets addressed to any IP — matching the TUN catch-all design.
    let iface_cfg = IfaceConfig::new(HardwareAddress::Ip);
    let mut iface = Interface::new(iface_cfg, &mut device, Instant::now());
    iface.set_any_ip(true);

    if let Some(ref ipv4_str) = config.tunnel.ipv4 {
        if let Some((ip, prefix)) = parse_tunnel_address(ipv4_str) {
            iface.update_ip_addrs(|addrs| {
                let _ = addrs.push(IpCidr::new(ip, prefix));
            });
        }
    }

    if let Some(ref ipv6_str) = config.tunnel.ipv6 {
        if let Some((ip, prefix)) = parse_tunnel_address(ipv6_str) {
            iface.update_ip_addrs(|addrs| {
                let _ = addrs.push(IpCidr::new(ip, prefix));
            });
        }
    }

    let socket_set = SocketSet::new(vec![]);
    let sessions = ActiveSessions::new(config.misc.max_session_count as usize);

    io_loop_task(&tun_async, device, iface, socket_set, sessions, config, cancel, stats, None).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_tunnel_address_supports_ipv4_and_ipv6() {
        let (ipv4, ipv4_prefix) = parse_tunnel_address("10.10.10.10/32").expect("ipv4 address");
        let (ipv6, ipv6_prefix) = parse_tunnel_address("fd00::1/128").expect("ipv6 address");

        assert_eq!(ipv4.to_string(), "10.10.10.10");
        assert_eq!(ipv4_prefix, 32);
        assert_eq!(ipv6.to_string(), "fd00::1");
        assert_eq!(ipv6_prefix, 128);
    }

    #[test]
    fn parse_tunnel_address_defaults_ipv6_prefix_to_host_route() {
        let (ipv6, prefix) = parse_tunnel_address("2001:db8::10").expect("ipv6 address");

        assert_eq!(ipv6.to_string(), "2001:db8::10");
        assert_eq!(prefix, 128);
    }
}
