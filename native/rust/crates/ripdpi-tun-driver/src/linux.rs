//! Linux TUN device driver backed by `tun-rs`.
//!
//! Replaces the former hand-rolled ioctl implementation with the production-grade
//! `tun-rs` crate, eliminating ~250 lines of unsafe FFI code while preserving the
//! same `TunnelDriver` trait API.

use std::net::{Ipv4Addr, Ipv6Addr};
use std::os::unix::io::{AsRawFd, RawFd};

use tun_rs::{DeviceBuilder, Layer, SyncDevice};

use crate::{TunnelDriver, TunnelError};

/// Linux TUN device backed by `tun-rs`.
///
/// The underlying file descriptor is owned by the `SyncDevice` and is closed
/// automatically when this value is dropped.
pub struct LinuxTunnel {
    dev: SyncDevice,
    /// Cached interface name, resolved once at open time.
    cached_name: String,
}

impl TunnelDriver for LinuxTunnel {
    fn open(name: Option<&str>, multi_queue: bool) -> Result<Self, TunnelError> {
        let mut builder = DeviceBuilder::new().layer(Layer::L3).multi_queue(multi_queue);
        if let Some(n) = name {
            builder = builder.name(n);
        }
        let dev = builder.build_sync().map_err(TunnelError::Io)?;
        let cached_name = dev.name().unwrap_or_else(|_| "?".to_owned());
        Ok(LinuxTunnel { dev, cached_name })
    }

    fn fd(&self) -> RawFd {
        self.dev.as_raw_fd()
    }

    fn name(&self) -> &str {
        &self.cached_name
    }

    fn index(&self) -> u32 {
        self.dev.if_index().unwrap_or(0)
    }

    fn set_mtu(&self, mtu: u32) -> Result<(), TunnelError> {
        let mtu_u16 = u16::try_from(mtu)
            .map_err(|_| TunnelError::Ioctl(format!("MTU {mtu} exceeds maximum supported value (65535)")))?;
        self.dev.set_mtu(mtu_u16).map_err(TunnelError::Io)
    }

    fn set_ipv4(&self, addr: Ipv4Addr, prefix: u8) -> Result<(), TunnelError> {
        self.dev.add_address_v4(addr, prefix).map_err(TunnelError::Io)
    }

    fn set_ipv6(&self, addr: Ipv6Addr, prefix: u8) -> Result<(), TunnelError> {
        self.dev.add_address_v6(addr, prefix).map_err(TunnelError::Io)
    }

    fn set_up(&self) -> Result<(), TunnelError> {
        self.dev.enabled(true).map_err(TunnelError::Io)
    }

    fn set_down(&self) -> Result<(), TunnelError> {
        self.dev.enabled(false).map_err(TunnelError::Io)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Compile-time check: LinuxTunnel must be Send + Sync per TunnelDriver's bounds.
    fn _assert_send_sync<T: Send + Sync>() {}
    fn _check() {
        _assert_send_sync::<LinuxTunnel>();
    }
}
