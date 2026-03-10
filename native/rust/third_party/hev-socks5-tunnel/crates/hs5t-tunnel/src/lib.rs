use std::net::{Ipv4Addr, Ipv6Addr};

#[cfg(unix)]
use std::os::unix::io::RawFd;

#[cfg(target_os = "linux")]
mod linux;

#[cfg(target_os = "linux")]
pub use linux::LinuxTunnel;

#[derive(Debug, thiserror::Error)]
pub enum TunnelError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("Ioctl error: {0}")]
    Ioctl(String),
    #[error("Not supported on this platform")]
    NotSupported,
}

/// Shared placeholder for Unix tunnel backends that are not implemented yet.
#[cfg(unix)]
#[derive(Debug)]
pub struct UnsupportedUnixTunnel;

#[cfg(unix)]
impl TunnelDriver for UnsupportedUnixTunnel {
    fn open(_name: Option<&str>, _multi_queue: bool) -> Result<Self, TunnelError> {
        Err(TunnelError::NotSupported)
    }

    fn fd(&self) -> RawFd {
        -1
    }

    fn name(&self) -> &str {
        ""
    }

    fn index(&self) -> u32 {
        0
    }

    fn set_mtu(&self, _mtu: u32) -> Result<(), TunnelError> {
        Err(TunnelError::NotSupported)
    }

    fn set_ipv4(&self, _addr: Ipv4Addr, _prefix: u8) -> Result<(), TunnelError> {
        Err(TunnelError::NotSupported)
    }

    fn set_ipv6(&self, _addr: Ipv6Addr, _prefix: u8) -> Result<(), TunnelError> {
        Err(TunnelError::NotSupported)
    }

    fn set_up(&self) -> Result<(), TunnelError> {
        Err(TunnelError::NotSupported)
    }

    fn set_down(&self) -> Result<(), TunnelError> {
        Err(TunnelError::NotSupported)
    }
}

/// FreeBSD TUN device stub.
#[cfg(target_os = "freebsd")]
pub type FreeBsdTunnel = UnsupportedUnixTunnel;

/// macOS UTUN device stub.
///
/// Provides the correct compile-time shape for cross-compilation targets.
/// All runtime operations return `TunnelError::NotSupported`.
#[cfg(target_os = "macos")]
pub type MacosTunnel = UnsupportedUnixTunnel;

/// NetBSD TUN device stub.
#[cfg(target_os = "netbsd")]
pub type NetBsdTunnel = UnsupportedUnixTunnel;

/// Windows TUN device stub (via wintun crate, not yet implemented).
#[cfg(target_os = "windows")]
pub struct WindowsTunnel;

#[cfg(target_os = "windows")]
impl WindowsTunnel {
    /// Always returns [`TunnelError::NotSupported`].
    pub fn open(_name: Option<&str>, _multi_queue: bool) -> Result<Self, TunnelError> {
        Err(TunnelError::NotSupported)
    }
}

/// Platform-independent TUN device driver.
///
/// Implementations are responsible for opening a TUN interface,
/// configuring its address/MTU/state, and exposing the raw fd for I/O.
#[cfg(unix)]
pub trait TunnelDriver: Send + Sync {
    /// Open (or create) a TUN interface.
    ///
    /// `name` – optional interface name (e.g. `"tun0"`); kernel picks one when `None`.
    /// `multi_queue` – enable IFF_MULTI_QUEUE for parallel I/O threads.
    fn open(name: Option<&str>, multi_queue: bool) -> Result<Self, TunnelError>
    where
        Self: Sized;

    /// Return the raw file descriptor for the TUN device.
    fn fd(&self) -> RawFd;

    /// Return the kernel-assigned interface name (e.g. `"tun0"`).
    fn name(&self) -> &str;

    /// Return the kernel interface index (matches `if_nametoindex`).
    fn index(&self) -> u32;

    /// Set the MTU for the interface.
    fn set_mtu(&self, mtu: u32) -> Result<(), TunnelError>;

    /// Assign an IPv4 address with the given prefix length.
    fn set_ipv4(&self, addr: Ipv4Addr, prefix: u8) -> Result<(), TunnelError>;

    /// Assign an IPv6 address with the given prefix length.
    fn set_ipv6(&self, addr: Ipv6Addr, prefix: u8) -> Result<(), TunnelError>;

    /// Bring the interface up (IFF_UP).
    fn set_up(&self) -> Result<(), TunnelError>;

    /// Bring the interface down (clear IFF_UP).
    fn set_down(&self) -> Result<(), TunnelError>;
}

#[cfg(test)]
mod tests {
    use super::*;

    // Verify TunnelError implements std::error::Error (compile-time check).
    fn _assert_error<E: std::error::Error>() {}
    fn _check_tunnel_error() {
        _assert_error::<TunnelError>();
    }

    // Verify TunnelError variants are constructible.
    #[test]
    fn tunnel_error_io_from() {
        let io_err = std::io::Error::new(std::io::ErrorKind::NotFound, "no dev");
        let err: TunnelError = io_err.into();
        assert!(matches!(err, TunnelError::Io(_)));
    }

    #[test]
    fn tunnel_error_ioctl_display() {
        let err = TunnelError::Ioctl("TUNSETIFF failed".to_string());
        assert_eq!(err.to_string(), "Ioctl error: TUNSETIFF failed");
    }

    #[test]
    fn tunnel_error_not_supported_display() {
        let err = TunnelError::NotSupported;
        assert_eq!(err.to_string(), "Not supported on this platform");
    }

    // Verify TunnelDriver trait object is Send + Sync (compile-time check).
    #[cfg(unix)]
    fn _assert_send_sync<T: Send + Sync>() {}

    #[cfg(unix)]
    type MockTunnel = UnsupportedUnixTunnel;

    #[cfg(unix)]
    #[test]
    fn mock_tunnel_trait_compiles() {
        let err = MockTunnel::open(None, false).unwrap_err();
        assert!(matches!(err, TunnelError::NotSupported));
        let t = UnsupportedUnixTunnel;
        assert_eq!(t.fd(), -1);
        assert_eq!(t.name(), "");
        assert_eq!(t.index(), 0);
        assert!(matches!(t.set_mtu(1500), Err(TunnelError::NotSupported)));
        assert!(matches!(
            t.set_ipv4("198.18.0.1".parse().unwrap(), 32),
            Err(TunnelError::NotSupported)
        ));
        assert!(matches!(t.set_up(), Err(TunnelError::NotSupported)));
        assert!(matches!(t.set_down(), Err(TunnelError::NotSupported)));
    }

    #[cfg(unix)]
    #[test]
    fn mock_tunnel_is_send_sync() {
        _assert_send_sync::<MockTunnel>();
    }
}
