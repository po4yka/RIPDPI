//! OS-primitive adapter — receive-side TTL operations.
//!
//! Enabling per-recv TTL delivery and reading a chunk together with its
//! observed IP TTL. Thin `#[cfg]`-split wrapper over `ripdpi-privileged-ops`;
//! the non-Linux arm degrades to a plain read with no TTL. Surfaced through
//! the `ttl_ops` facade.

use std::io;
use std::net::TcpStream;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
    ripdpi_privileged_ops::enable_recv_ttl(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn enable_recv_ttl(_stream: &TcpStream) -> io::Result<()> {
    Ok(())
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    ripdpi_privileged_ops::read_chunk_with_ttl(stream, buf)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    use std::io::Read;

    Ok(((&*stream).read(buf)?, None))
}
