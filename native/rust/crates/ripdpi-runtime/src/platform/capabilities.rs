use std::net::TcpStream;

pub use ripdpi_privileged_ops::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};

pub use ripdpi_privileged_ops::detect_default_ttl;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn try_set_stream_ttl_with_outcome(stream: &TcpStream, ttl: u8) -> CapabilityOutcome<()> {
    ripdpi_privileged_ops::try_set_stream_ttl_with_outcome(stream, ttl)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn try_set_stream_ttl_with_outcome(_stream: &TcpStream, _ttl: u8) -> CapabilityOutcome<()> {
    CapabilityOutcome::Unavailable {
        capability: RuntimeCapability::TtlWrite,
        reason: CapabilityUnavailable::Unsupported,
    }
}
