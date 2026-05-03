use std::io;
use std::net::TcpStream;

use ripdpi_capabilities::CapabilityOutcome;
#[cfg(not(any(target_os = "linux", target_os = "android")))]
use ripdpi_capabilities::{CapabilityUnavailable, RuntimeCapability};

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
    crate::linux::enable_recv_ttl(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn enable_recv_ttl(_stream: &TcpStream) -> io::Result<()> {
    crate::unsupported()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    crate::linux::read_chunk_with_ttl(stream, buf)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    use std::io::Read;
    Ok(((&*stream).read(buf)?, None))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn try_set_stream_ttl_with_outcome(stream: &TcpStream, ttl: u8) -> CapabilityOutcome<()> {
    crate::linux::try_set_stream_ttl_with_outcome(stream, ttl)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn try_set_stream_ttl_with_outcome(_stream: &TcpStream, _ttl: u8) -> CapabilityOutcome<()> {
    CapabilityOutcome::Unavailable {
        capability: RuntimeCapability::TtlWrite,
        reason: CapabilityUnavailable::Unsupported,
    }
}
