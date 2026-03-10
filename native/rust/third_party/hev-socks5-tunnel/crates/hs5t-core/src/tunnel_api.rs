//! High-level blocking tunnel API consumed by the Rust CLI and JNI layer.
//!
//! Callers supply a raw TUN file descriptor (already opened and owned by the
//! platform — e.g. Android VPN service) and a parsed `Config`.  This module
//! sets up the smoltcp networking stack and delegates to `io_loop_task`.

use std::io;
use std::net::Ipv4Addr;
use std::os::unix::io::FromRawFd;
use std::sync::Arc;

use smoltcp::iface::{Config as IfaceConfig, Interface, SocketSet};
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr};
use tokio_util::sync::CancellationToken;

use hs5t_config::Config;

use crate::{io_loop_task, ActiveSessions, Stats, TunDevice};

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

    // Configure the interface IPv4 address from tunnel config (optional).
    // Accepted formats: "a.b.c.d" (assumes /24) or "a.b.c.d/prefix".
    if let Some(ref ipv4_str) = config.tunnel.ipv4 {
        let ip_part = ipv4_str.split('/').next().unwrap_or(ipv4_str.as_str());
        let prefix: u8 = ipv4_str
            .split('/')
            .nth(1)
            .and_then(|s| s.parse().ok())
            .unwrap_or(24);
        if let Ok(ip) = ip_part.parse::<Ipv4Addr>() {
            let o = ip.octets();
            iface.update_ip_addrs(|addrs| {
                let _ = addrs.push(IpCidr::new(IpAddress::v4(o[0], o[1], o[2], o[3]), prefix));
            });
        }
    }

    let socket_set = SocketSet::new(vec![]);
    let sessions = ActiveSessions::new(config.misc.max_session_count as usize);

    io_loop_task(
        &tun_async, device, iface, socket_set, sessions, config, cancel, stats, None,
    )
    .await
}
