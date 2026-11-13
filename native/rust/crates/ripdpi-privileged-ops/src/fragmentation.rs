#[cfg(any(target_os = "linux", target_os = "android", test))]
mod identification;
#[cfg(any(target_os = "linux", target_os = "android", test))]
pub(crate) mod preflight;

use std::io;
use std::net::{SocketAddr, TcpStream, UdpSocket};

use crate::types::TcpFlagOverrides;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub(crate) use identification::reserve_fragment_identification;

/// Error from an IP-fragment send, with its payload delivery boundary.
#[derive(Debug)]
pub struct IpFragmentSendError {
    pub source: io::Error,
    /// True if a packet may have left or TCP repair state could not be restored.
    pub payload_commit_uncertain: bool,
}

impl IpFragmentSendError {
    /// Returns an OS error retained inside a normalized or contextual error.
    pub fn raw_os_error(&self) -> Option<i32> {
        let mut current: &(dyn std::error::Error + 'static) = &self.source;
        // A custom error can form a source cycle.
        for _ in 0..8 {
            if let Some(error) = current.downcast_ref::<io::Error>() {
                if let Some(errno) = error.raw_os_error() {
                    return Some(errno);
                }
                if let Some(inner) = error.get_ref() {
                    current = inner;
                    continue;
                }
            }
            current = current.source()?;
        }
        None
    }
}

impl std::fmt::Display for IpFragmentSendError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.source.fmt(formatter)
    }
}

impl std::error::Error for IpFragmentSendError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(&self.source)
    }
}

pub(crate) fn fragment_send_error(mut source: io::Error, payload_commit_uncertain: bool) -> io::Error {
    if let Some(phase) = source.get_mut().and_then(|inner| inner.downcast_mut::<IpFragmentSendError>()) {
        phase.payload_commit_uncertain |= payload_commit_uncertain;
        return source;
    }
    io::Error::new(source.kind(), IpFragmentSendError { source, payload_commit_uncertain })
}

#[cfg(any(target_os = "linux", target_os = "android", test))]
pub(crate) fn finish_fragment_send(operation: io::Result<()>, restoration: io::Result<()>) -> io::Result<()> {
    match restoration {
        Ok(()) => operation,
        Err(error) => Err(fragment_send_error(error, true)),
    }
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_tcp(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    crate::linux::send_ip_fragmented_tcp(
        stream,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        flags,
        ipv4_identification,
    )
    .map_err(|error| fragment_send_error(error, false))
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ip_fragmented_tcp(
    _stream: &TcpStream,
    _payload: &[u8],
    _split_offset: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _disorder: bool,
    _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    _flags: TcpFlagOverrides,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    crate::unsupported().map_err(|error| fragment_send_error(error, false))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_udp(
    upstream: &UdpSocket,
    target: SocketAddr,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    crate::linux::send_ip_fragmented_udp(
        upstream,
        target,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        ipv4_identification,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ip_fragmented_udp(
    _upstream: &UdpSocket,
    _target: SocketAddr,
    _payload: &[u8],
    _split_offset: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _disorder: bool,
    _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    crate::unsupported()
}

#[cfg(test)]
mod tests {
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    use super::send_ip_fragmented_tcp;
    use super::{IpFragmentSendError, finish_fragment_send, fragment_send_error};
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    use std::io::Read;
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    use std::net::{Shutdown, TcpListener, TcpStream};

    #[test]
    fn repair_restoration_failure_prevents_safe_fallback() {
        let operation = Err(fragment_send_error(std::io::Error::from(std::io::ErrorKind::WouldBlock), false));
        let error = finish_fragment_send(operation, Err(std::io::Error::from_raw_os_error(libc::EIO)))
            .expect_err("restoration failed");
        let phase = error.get_ref().unwrap().downcast_ref::<IpFragmentSendError>().unwrap();
        assert!(phase.payload_commit_uncertain);
        assert_eq!(phase.source.raw_os_error(), Some(libc::EIO));
    }

    #[test]
    fn fragment_error_finds_errno_inside_a_normalized_capability_failure() {
        let phase = IpFragmentSendError {
            source: std::io::Error::new(
                std::io::ErrorKind::Unsupported,
                std::io::Error::from_raw_os_error(libc::EPERM),
            ),
            payload_commit_uncertain: false,
        };
        assert_eq!(phase.source.kind(), std::io::ErrorKind::Unsupported);
        assert_eq!(phase.raw_os_error(), Some(libc::EPERM));
    }

    #[test]
    fn fragment_error_never_loses_errno_or_downgrades_uncertainty() {
        let error = fragment_send_error(std::io::Error::from_raw_os_error(libc::EIO), false);
        let error = fragment_send_error(error, true);
        let error = fragment_send_error(error, false);
        let phase = error.get_ref().unwrap().downcast_ref::<IpFragmentSendError>().unwrap();
        assert!(phase.payload_commit_uncertain);
        assert_eq!(phase.source.raw_os_error(), Some(libc::EIO));
    }

    #[test]
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    fn unsupported_fragment_send_reports_pre_send_failure() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (mut server, _) = listener.accept().unwrap();
        let error = send_ip_fragmented_tcp(
            &client,
            b"original payload",
            5,
            64,
            None,
            false,
            ripdpi_ipfrag::Ipv6ExtHeaders::default(),
            crate::TcpFlagOverrides::default(),
            None,
        )
        .expect_err("raw fragmentation unsupported on this host");
        let phase = error
            .get_ref()
            .and_then(|source| source.downcast_ref::<IpFragmentSendError>())
            .expect("explicit fragment send phase");
        assert!(!phase.payload_commit_uncertain);
        assert_eq!(phase.source.kind(), std::io::ErrorKind::Unsupported);
        client.shutdown(Shutdown::Write).unwrap();
        let mut received = Vec::new();
        server.read_to_end(&mut received).unwrap();
        assert!(received.is_empty());
    }
}
