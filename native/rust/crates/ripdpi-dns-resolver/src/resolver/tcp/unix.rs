use std::fmt;
use std::net::IpAddr;
use std::path::Path;

use tokio::net::UnixStream;

use super::super::state::ResolverInner;
use super::socks5;
use crate::transport::doh_request_target;
use crate::types::EncryptedDnsError;

#[derive(Clone, Copy)]
struct UnixProxy<'a>(&'a Path);

impl fmt::Display for UnixProxy<'_> {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("the private proxy socket")
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the in-flight proxy stream before any shared bootstrap health outcome is recorded.
pub(super) async fn connect_socks5_unix(
    inner: &ResolverInner,
    bootstrap_ips: Vec<IpAddr>,
    record_success: bool,
    path: &Path,
) -> Result<UnixStream, EncryptedDnsError> {
    socks5::connect_socks5_tcp_with(inner, bootstrap_ips, record_success, UnixProxy(path), |proxy| {
        UnixStream::connect(proxy.0)
    })
    .await
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the partially negotiated proxy stream; no shared resolver state is mutated.
pub(super) async fn connect_socks5_unix_target(
    inner: &ResolverInner,
    path: &Path,
) -> Result<UnixStream, EncryptedDnsError> {
    let target = doh_request_target(&inner.endpoint).ok_or(EncryptedDnsError::MissingHost)?;
    socks5::connect_socks5_target(inner, UnixProxy(path), target, &mut |proxy: UnixProxy<'_>| {
        UnixStream::connect(proxy.0)
    })
    .await
}
