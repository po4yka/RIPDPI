//! OS-primitive adapter — BPF socket-filter timestamp stripping.
//!
//! Thin `#[cfg]`-split wrapper over `ripdpi-privileged-ops`; non-Linux targets
//! return `Unsupported`. Surfaced through the `socket` facade.

use std::io;
use std::net::TcpStream;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn attach_strip_timestamps(stream: &TcpStream) -> io::Result<()> {
    ripdpi_privileged_ops::attach_strip_timestamps(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn attach_strip_timestamps(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
