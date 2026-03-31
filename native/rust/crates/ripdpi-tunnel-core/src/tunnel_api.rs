//! High-level blocking tunnel API consumed by the Rust CLI and JNI layer.
//!
//! Callers supply a raw TUN file descriptor (already opened and owned by the
//! platform — e.g. Android VPN service) and a parsed `Config`.  This module
//! sets up the smoltcp networking stack and delegates to `io_loop_task`.

use std::io;
use std::net::IpAddr;
use std::sync::Arc;

use smoltcp::iface::{Config as IfaceConfig, Interface, SocketSet};
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr};
use tokio_util::sync::CancellationToken;
use tun_rs::AsyncDevice;

use ripdpi_tunnel_config::Config;

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
    // SAFETY: `tun_fd` is a valid, open file descriptor and ownership transfers
    // to `AsyncDevice`.  The caller (JNI layer) dup'd the fd so it is safe for
    // tun-rs to take ownership and close it on drop.  `AsyncDevice::from_fd` sets
    // non-blocking mode and registers the fd with the tokio reactor.
    let tun_async = unsafe { AsyncDevice::from_fd(tun_fd) }
        .map_err(|e| io::Error::other(format!("create async TUN device from fd: {e}")))?;

    // The fd comes from Android VpnService (IFF_NO_PI) or a socketpair in tests —
    // neither includes a packet information header.  Disable tun-rs's PI handling
    // which would otherwise strip/prepend 4 bytes on macOS.
    tun_async.set_ignore_packet_info(false);

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
            if let IpAddress::Ipv4(v4) = ip {
                iface
                    .routes_mut()
                    .add_default_ipv4_route(v4)
                    .map_err(|e| io::Error::other(format!("install default IPv4 route for {ipv4_str}: {e}")))?;
            }
        }
    }

    if let Some(ref ipv6_str) = config.tunnel.ipv6 {
        if let Some((ip, prefix)) = parse_tunnel_address(ipv6_str) {
            iface.update_ip_addrs(|addrs| {
                let _ = addrs.push(IpCidr::new(ip, prefix));
            });
            if let IpAddress::Ipv6(v6) = ip {
                iface
                    .routes_mut()
                    .add_default_ipv6_route(v6)
                    .map_err(|e| io::Error::other(format!("install default IPv6 route for {ipv6_str}: {e}")))?;
            }
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

    #[test]
    fn parse_tunnel_address_defaults_ipv4_prefix_to_lan_route() {
        let (ipv4, prefix) = parse_tunnel_address("10.0.0.7").expect("ipv4 address");

        assert_eq!(ipv4.to_string(), "10.0.0.7");
        assert_eq!(prefix, 24);
    }

    #[test]
    fn parse_tunnel_address_returns_none_for_invalid_ip() {
        assert!(parse_tunnel_address("not-an-ip").is_none());
    }

    #[test]
    fn any_ip_routes_are_installed_for_configured_tunnel_addresses() {
        let iface_cfg = IfaceConfig::new(HardwareAddress::Ip);
        let mut device = TunDevice::new(1500);
        let mut iface = Interface::new(iface_cfg, &mut device, Instant::now());
        iface.set_any_ip(true);

        let (ipv4, ipv4_prefix) = parse_tunnel_address("10.0.0.2/24").expect("ipv4");
        let (ipv6, ipv6_prefix) = parse_tunnel_address("fd00::1/128").expect("ipv6");

        iface.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::new(ipv4, ipv4_prefix));
            let _ = addrs.push(IpCidr::new(ipv6, ipv6_prefix));
        });
        iface.routes_mut().add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2)).expect("ipv4 route");
        iface
            .routes_mut()
            .add_default_ipv6_route(smoltcp::wire::Ipv6Address::new(0xfd00, 0, 0, 0, 0, 0, 0, 1))
            .expect("ipv6 route");

        assert!(iface.any_ip());
        assert_eq!(iface.ipv4_addr(), Some(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2)));
        assert_eq!(iface.ipv6_addr(), Some(smoltcp::wire::Ipv6Address::new(0xfd00, 0, 0, 0, 0, 0, 0, 1)));
    }
}
