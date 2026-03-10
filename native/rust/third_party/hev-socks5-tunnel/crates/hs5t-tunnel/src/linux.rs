//! Linux TUN device driver using /dev/net/tun.
//!
//! Mirrors the C implementation in src/hev-tunnel-linux.c but uses OwnedFd
//! for automatic close-on-drop instead of explicit hev_tunnel_close().

use std::mem;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::os::unix::io::{AsRawFd, FromRawFd, OwnedFd, RawFd};

use crate::{TunnelDriver, TunnelError};

// TUNSETIFF = _IOW('T', 202, int)
// _IOW(type, nr, size) = (1<<30) | (sizeof(int)<<16) | (type<<8) | nr
//                      = 0x40000000 | 0x00040000 | 0x00005400 | 0x000000ca
//                      = 0x400454ca  (same on all Linux arches with standard _IOC layout)
const TUNSETIFF: libc::c_ulong = 0x4004_54ca;

// Flags from <linux/if_tun.h> — not in the libc crate.
const IFF_TUN: libc::c_short = 0x0001;
const IFF_NO_PI: libc::c_short = 0x1000; // no packet info header
const IFF_MULTI_QUEUE: libc::c_short = 0x0100;

/// Linux in6_ifreq (from <linux/ipv6.h>) — used for SIOCSIFADDR on AF_INET6 sockets.
///
/// Not exposed by the libc crate, so we define the layout manually.
/// The C definition is:
///   struct in6_ifreq { struct in6_addr ifr6_addr; uint32_t ifr6_prefixlen; int ifr6_ifindex; };
#[repr(C)]
struct In6Ifreq {
    ifr6_addr: libc::in6_addr,
    ifr6_prefixlen: libc::c_uint,
    ifr6_ifindex: libc::c_int,
}

/// Linux TUN device opened from /dev/net/tun.
///
/// The file descriptor is owned: it is closed automatically when this value is dropped.
pub struct LinuxTunnel {
    fd: OwnedFd,
    /// Kernel-assigned interface name, null-terminated within IFNAMSIZ bytes.
    name: [u8; libc::IFNAMSIZ],
}

impl LinuxTunnel {
    /// Open a UDP socket of the given address family for ioctl configuration calls.
    ///
    /// A SOCK_DGRAM socket is sufficient for all interface ioctls and does not
    /// require an established connection.
    fn ctrl_socket(af: libc::c_int) -> Result<OwnedFd, TunnelError> {
        // SAFETY: socket(2) is a valid syscall; AF_INET/AF_INET6 + SOCK_DGRAM + 0 is a
        // well-known, valid combination; we check the return value for errors before use.
        let fd = unsafe { libc::socket(af, libc::SOCK_DGRAM, 0) };
        if fd < 0 {
            return Err(TunnelError::Io(std::io::Error::last_os_error()));
        }
        // SAFETY: fd is a valid non-negative file descriptor just returned by socket(2);
        // we take sole ownership here — no other code holds a copy of this fd.
        Ok(unsafe { OwnedFd::from_raw_fd(fd) })
    }

    /// Build a `[c_char; IFNAMSIZ]` suitable for `ifreq.ifr_name`.
    fn make_ifr_name(&self) -> [libc::c_char; libc::IFNAMSIZ] {
        // libc::c_char is u8 on aarch64/arm Linux, i8 on x86/x86_64.
        // Using `0 as libc::c_char` and `src as libc::c_char` works on both.
        #[allow(clippy::cast_possible_wrap)]
        let mut arr = [0 as libc::c_char; libc::IFNAMSIZ];
        #[allow(clippy::cast_possible_wrap)]
        for (dst, &src) in arr.iter_mut().zip(self.name.iter()) {
            *dst = src as libc::c_char;
        }
        arr
    }

    /// Set or clear the IFF_UP flag on the interface.
    fn set_flags_bit(&self, up: bool) -> Result<(), TunnelError> {
        let sock = Self::ctrl_socket(libc::AF_INET)?;

        // SAFETY: mem::zeroed() produces a valid ifreq — it is a plain C struct
        // with no Rust-level invariants; all-zero bytes are a valid representation.
        let mut ifr: libc::ifreq = unsafe { mem::zeroed() };
        ifr.ifr_name = self.make_ifr_name();

        // Read current flags.
        // SAFETY: sock is a valid AF_INET/SOCK_DGRAM fd; &mut ifr points to a valid zeroed
        // ifreq with ifr_name set; SIOCGIFFLAGS reads the interface flags into ifru_flags.
        let res = unsafe { libc::ioctl(sock.as_raw_fd(), libc::SIOCGIFFLAGS, &mut ifr as *mut _) };
        if res < 0 {
            return Err(TunnelError::Ioctl(format!(
                "SIOCGIFFLAGS: {}",
                std::io::Error::last_os_error()
            )));
        }

        // Modify the flags in-place.
        // SAFETY: SIOCGIFFLAGS just wrote the current flags into ifru_flags; we read and
        // modify that same field, which is the standard Linux pattern for toggling IFF_UP.
        unsafe {
            if up {
                ifr.ifr_ifru.ifru_flags |= libc::IFF_UP as libc::c_short;
            } else {
                ifr.ifr_ifru.ifru_flags &= !(libc::IFF_UP as libc::c_short);
            }
        }

        // Write modified flags back.
        // SAFETY: sock is a valid AF_INET/SOCK_DGRAM fd; ifru_flags holds the new valid
        // flag value; SIOCSIFFLAGS applies the flags to the named interface.
        let res = unsafe { libc::ioctl(sock.as_raw_fd(), libc::SIOCSIFFLAGS, &ifr as *const _) };
        if res < 0 {
            return Err(TunnelError::Ioctl(format!(
                "SIOCSIFFLAGS: {}",
                std::io::Error::last_os_error()
            )));
        }
        Ok(())
    }
}

impl TunnelDriver for LinuxTunnel {
    fn open(name: Option<&str>, multi_queue: bool) -> Result<Self, TunnelError> {
        // Open the TUN clone device.  std::fs::File handles O_CLOEXEC implicitly on Linux.
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open("/dev/net/tun")
            .map_err(TunnelError::Io)?;
        let fd: OwnedFd = file.into();

        // Build ifreq for TUNSETIFF.
        // SAFETY: mem::zeroed() is valid for ifreq — plain C struct, no Rust invariants.
        let mut ifr: libc::ifreq = unsafe { mem::zeroed() };

        // SAFETY: ifru_flags is a c_short union variant; the union is zeroed above so we
        // are not reading uninitialised memory; we only write a valid flags combination.
        unsafe {
            ifr.ifr_ifru.ifru_flags = IFF_TUN | IFF_NO_PI;
            if multi_queue {
                ifr.ifr_ifru.ifru_flags |= IFF_MULTI_QUEUE;
            }
        }

        if let Some(n) = name {
            let bytes = n.as_bytes();
            let len = bytes.len().min(libc::IFNAMSIZ - 1);
            for (dst, &src) in ifr.ifr_name.iter_mut().zip(bytes[..len].iter()) {
                *dst = src as libc::c_char;
            }
        }

        // Attach to the TUN interface.
        // SAFETY: fd is a valid /dev/net/tun file descriptor; &mut ifr points to a valid
        // zeroed ifreq with ifru_flags set; TUNSETIFF (0x400454ca) is the correct Linux
        // ioctl number for TUN/TAP interface creation; the kernel reads ifru_flags and
        // ifr_name, then writes the kernel-assigned interface name back into ifr_name.
        let res = unsafe { libc::ioctl(fd.as_raw_fd(), TUNSETIFF, &mut ifr as *mut _) };
        if res < 0 {
            return Err(TunnelError::Ioctl(format!(
                "TUNSETIFF: {}",
                std::io::Error::last_os_error()
            )));
        }

        // Copy the kernel-assigned interface name from ifreq.
        // c_char is u8 on aarch64/arm Linux and i8 on x86; cast is harmless on both.
        let mut iface_name = [0u8; libc::IFNAMSIZ];
        #[allow(clippy::unnecessary_cast)]
        for (dst, &src) in iface_name.iter_mut().zip(ifr.ifr_name.iter()) {
            *dst = src as u8;
        }

        Ok(LinuxTunnel {
            fd,
            name: iface_name,
        })
    }

    fn fd(&self) -> RawFd {
        self.fd.as_raw_fd()
    }

    fn name(&self) -> &str {
        // Interface names are ASCII; the array is null-terminated within IFNAMSIZ bytes.
        let nul = self
            .name
            .iter()
            .position(|&b| b == 0)
            .unwrap_or(libc::IFNAMSIZ);
        std::str::from_utf8(&self.name[..nul]).unwrap_or("?")
    }

    fn index(&self) -> u32 {
        // SAFETY: self.name is null-terminated — it was zeroed at construction and the kernel
        // writes the interface name within IFNAMSIZ-1 bytes, guaranteeing a null terminator
        // at or before position IFNAMSIZ-1; the pointer is valid for the lifetime of self.
        unsafe { libc::if_nametoindex(self.name.as_ptr() as *const libc::c_char) }
    }

    fn set_mtu(&self, mtu: u32) -> Result<(), TunnelError> {
        let sock = Self::ctrl_socket(libc::AF_INET)?;

        // SAFETY: mem::zeroed() is valid for ifreq.
        let mut ifr: libc::ifreq = unsafe { mem::zeroed() };
        ifr.ifr_name = self.make_ifr_name();

        // SAFETY: ifru_mtu is a c_int union variant; zeroed union + single-field write is safe.
        // SAFETY: Writing to ifru_mtu in a freshly zeroed ifreq union — no aliasing.
        #[allow(unused_unsafe)]
        unsafe {
            ifr.ifr_ifru.ifru_mtu = mtu as libc::c_int;
        }

        // SAFETY: sock is a valid AF_INET/SOCK_DGRAM fd; &ifr has ifru_mtu set and ifr_name
        // set; SIOCSIFMTU (0x8922) sets the interface MTU and requires CAP_NET_ADMIN.
        let res = unsafe { libc::ioctl(sock.as_raw_fd(), libc::SIOCSIFMTU, &ifr as *const _) };
        if res < 0 {
            return Err(TunnelError::Ioctl(format!(
                "SIOCSIFMTU: {}",
                std::io::Error::last_os_error()
            )));
        }
        Ok(())
    }

    fn set_ipv4(&self, addr: Ipv4Addr, prefix: u8) -> Result<(), TunnelError> {
        let sock = Self::ctrl_socket(libc::AF_INET)?;

        // --- SIOCSIFADDR: assign the IPv4 address ---
        // SAFETY: mem::zeroed() is valid for ifreq.
        let mut ifr: libc::ifreq = unsafe { mem::zeroed() };
        ifr.ifr_name = self.make_ifr_name();

        // SAFETY: ifru_addr is a sockaddr union variant; we cast to *mut sockaddr_in because
        // SIOCSIFADDR on AF_INET sockets interprets this field as sockaddr_in; we set only
        // sin_family and sin_addr which are the fields the kernel reads; the cast is valid
        // because sockaddr_in is layout-compatible with sockaddr (both start with sa_family_t).
        unsafe {
            let sin = &mut ifr.ifr_ifru.ifru_addr as *mut _ as *mut libc::sockaddr_in;
            (*sin).sin_family = libc::AF_INET as libc::sa_family_t;
            // u32::from(Ipv4Addr) returns the address as a big-endian u32 (host math value).
            // libc::htonl converts host byte order to network byte order for s_addr.
            (*sin).sin_addr.s_addr = libc::htonl(u32::from(addr));
        }

        // SAFETY: sock is a valid AF_INET/SOCK_DGRAM fd; &ifr contains a valid sockaddr_in
        // in ifru_addr; SIOCSIFADDR (0x8916) assigns the IPv4 address to the interface.
        let res = unsafe { libc::ioctl(sock.as_raw_fd(), libc::SIOCSIFADDR, &ifr as *const _) };
        if res < 0 {
            return Err(TunnelError::Ioctl(format!(
                "SIOCSIFADDR: {}",
                std::io::Error::last_os_error()
            )));
        }

        // --- SIOCSIFNETMASK: assign the prefix length as a netmask ---
        // SAFETY: mem::zeroed() is valid for ifreq.
        let mut ifr_mask: libc::ifreq = unsafe { mem::zeroed() };
        ifr_mask.ifr_name = self.make_ifr_name();

        // Compute the netmask from prefix length.  Guard against the prefix=0 edge case
        // where shifting a 32-bit value by 32 would be undefined behaviour.
        let mask: u32 = if prefix == 0 {
            0
        } else {
            !0u32 << (32 - prefix)
        };

        // SAFETY: ifru_netmask is a separate sockaddr union variant from ifru_addr; casting
        // to *mut sockaddr_in is valid for the same reasons as above; EEXIST from
        // SIOCSIFNETMASK is documented as meaning "already set" and is treated as success.
        unsafe {
            let sin = &mut ifr_mask.ifr_ifru.ifru_netmask as *mut _ as *mut libc::sockaddr_in;
            (*sin).sin_family = libc::AF_INET as libc::sa_family_t;
            (*sin).sin_addr.s_addr = libc::htonl(mask);
        }

        // SAFETY: sock is a valid AF_INET/SOCK_DGRAM fd; &ifr_mask contains a valid netmask
        // sockaddr_in in ifru_netmask; SIOCSIFNETMASK (0x891c) sets the IPv4 netmask.
        let res = unsafe {
            libc::ioctl(
                sock.as_raw_fd(),
                libc::SIOCSIFNETMASK,
                &ifr_mask as *const _,
            )
        };
        if res < 0 {
            let err = std::io::Error::last_os_error();
            if err.raw_os_error() != Some(libc::EEXIST) {
                return Err(TunnelError::Ioctl(format!("SIOCSIFNETMASK: {err}")));
            }
        }
        Ok(())
    }

    fn set_ipv6(&self, addr: Ipv6Addr, prefix: u8) -> Result<(), TunnelError> {
        let sock = Self::ctrl_socket(libc::AF_INET6)?;

        // Build in6_ifreq with interface index, prefix length, and address.
        // SAFETY: mem::zeroed() is valid for In6Ifreq — plain C struct.
        let mut ifr6: In6Ifreq = unsafe { mem::zeroed() };
        ifr6.ifr6_prefixlen = u32::from(prefix);
        // index() calls if_nametoindex which is always safe (see that method's SAFETY comment).
        ifr6.ifr6_ifindex = self.index() as libc::c_int;
        // addr.octets() returns the IPv6 address bytes in network order — correct for s6_addr.
        ifr6.ifr6_addr = libc::in6_addr {
            s6_addr: addr.octets(),
        };

        // SAFETY: sock is a valid AF_INET6/SOCK_DGRAM fd; &ifr6 is a valid In6Ifreq with
        // correct fields; SIOCSIFADDR on an AF_INET6 socket interprets the third argument as
        // *in6_ifreq and assigns the IPv6 address; EEXIST means already assigned — not an error.
        let res = unsafe { libc::ioctl(sock.as_raw_fd(), libc::SIOCSIFADDR, &ifr6 as *const _) };
        if res < 0 {
            let err = std::io::Error::last_os_error();
            if err.raw_os_error() != Some(libc::EEXIST) {
                return Err(TunnelError::Ioctl(format!("SIOCSIFADDR(v6): {err}")));
            }
        }
        Ok(())
    }

    fn set_up(&self) -> Result<(), TunnelError> {
        self.set_flags_bit(true)
    }

    fn set_down(&self) -> Result<(), TunnelError> {
        self.set_flags_bit(false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::TunnelDriver;

    // Compile-time check: LinuxTunnel must be Send + Sync per TunnelDriver's bounds.
    fn _assert_send_sync<T: Send + Sync>() {}
    fn _check() {
        _assert_send_sync::<LinuxTunnel>();
    }

    /// Open a TUN, verify the fd is valid and the name/index are populated.
    #[test]
    #[ignore = "requires CAP_NET_ADMIN; run: sudo cargo test -p hs5t-tunnel -- --include-ignored"]
    fn open_tun_fd_is_valid() {
        let tun = LinuxTunnel::open(Some("tun-hs5t-t0"), false)
            .expect("open should succeed with CAP_NET_ADMIN");
        assert!(tun.fd() >= 0, "fd must be non-negative");
        assert!(tun.index() > 0, "interface index must be positive");
        let name = tun.name();
        assert!(!name.is_empty(), "interface name must not be empty");
        assert!(
            name.starts_with("tun"),
            "TUN interface name should start with 'tun', got: {name}"
        );
    }

    /// set_mtu(1500) should succeed without error.
    #[test]
    #[ignore = "requires CAP_NET_ADMIN; run: sudo cargo test -p hs5t-tunnel -- --include-ignored"]
    fn set_mtu_succeeds() {
        let tun = LinuxTunnel::open(Some("tun-hs5t-t1"), false)
            .expect("open should succeed with CAP_NET_ADMIN");
        tun.set_mtu(1500).expect("set_mtu(1500) must succeed");
    }

    /// set_ipv4 should assign the address and make it visible via `ip addr show`.
    #[test]
    #[ignore = "requires CAP_NET_ADMIN; run: sudo cargo test -p hs5t-tunnel -- --include-ignored"]
    fn set_ipv4_address_visible() {
        let tun = LinuxTunnel::open(Some("tun-hs5t-t2"), false)
            .expect("open should succeed with CAP_NET_ADMIN");
        let addr: Ipv4Addr = "198.18.0.1".parse().unwrap();
        tun.set_ipv4(addr, 32).expect("set_ipv4 must succeed");

        let out = std::process::Command::new("ip")
            .args(["addr", "show", tun.name()])
            .output()
            .expect("ip(8) must be available");
        let stdout = String::from_utf8_lossy(&out.stdout);
        assert!(
            stdout.contains("198.18.0.1"),
            "expected 198.18.0.1 in `ip addr show {}`:\n{stdout}",
            tun.name()
        );
    }

    /// set_up then set_down should toggle the link state visible via `ip link show`.
    #[test]
    #[ignore = "requires CAP_NET_ADMIN; run: sudo cargo test -p hs5t-tunnel -- --include-ignored"]
    fn set_up_down_toggles_link_state() {
        let tun = LinuxTunnel::open(Some("tun-hs5t-t3"), false)
            .expect("open should succeed with CAP_NET_ADMIN");

        tun.set_up().expect("set_up must succeed");
        let out = std::process::Command::new("ip")
            .args(["link", "show", tun.name()])
            .output()
            .expect("ip(8) must be available");
        let stdout = String::from_utf8_lossy(&out.stdout);
        assert!(
            !stdout.contains("state DOWN"),
            "expected interface to be UP after set_up, got:\n{stdout}"
        );

        tun.set_down().expect("set_down must succeed");
        let out = std::process::Command::new("ip")
            .args(["link", "show", tun.name()])
            .output()
            .expect("ip(8) must be available");
        let stdout = String::from_utf8_lossy(&out.stdout);
        assert!(
            stdout.contains("state DOWN"),
            "expected interface to be DOWN after set_down, got:\n{stdout}"
        );
    }

    /// After drop the fd must be closed: fcntl(F_GETFD) should return EBADF.
    #[test]
    #[ignore = "requires CAP_NET_ADMIN; run: sudo cargo test -p hs5t-tunnel -- --include-ignored"]
    fn drop_closes_fd() {
        let tun = LinuxTunnel::open(Some("tun-hs5t-t4"), false)
            .expect("open should succeed with CAP_NET_ADMIN");
        let raw: RawFd = tun.fd();
        assert!(raw >= 0);

        drop(tun); // OwnedFd::drop calls close(raw)

        // SAFETY: raw is a file descriptor that has already been closed by OwnedFd::drop;
        // we call F_GETFD only to observe the EBADF error — no resource is allocated or
        // freed by this call on an invalid fd.
        let res = unsafe { libc::fcntl(raw, libc::F_GETFD) };
        assert_eq!(
            res, -1,
            "fd {raw} should be closed (expected -1/EBADF after drop)"
        );
    }
}
