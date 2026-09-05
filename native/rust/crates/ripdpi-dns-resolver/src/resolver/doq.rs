use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant;

use tokio::time::timeout;

use super::EncryptedDnsResolver;
use crate::types::{EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsTransport};

impl EncryptedDnsResolver {
    /// # Cancel safety:
    /// Dropping the query releases its dedicated QUIC streams; other queries use separate streams.
    pub(super) async fn exchange_doq(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
        let len_prefix = doq_length_prefix(query_bytes.len())?;
        if matches!(self.inner.transport, EncryptedDnsTransport::Socks5 { .. }) {
            return Err(EncryptedDnsError::Request(
                "DoQ is not supported over SOCKS5 transport (SOCKS5 is TCP-only)".to_string(),
            ));
        }
        let endpoint = self
            .inner
            .doq_endpoint
            .as_ref()
            .ok_or_else(|| EncryptedDnsError::Request("DoQ endpoint not initialized".to_string()))?;

        let conn = self.get_or_connect_doq(endpoint).await?;
        match timeout(self.inner.timeout, exchange_doq_query(conn.clone(), query_bytes, len_prefix)).await {
            Ok(result) => result,
            Err(_) => {
                conn.close(0u32.into(), b"DoQ query timeout");
                *self.inner.doq_connection.lock().await = None;
                Err(EncryptedDnsError::Request("DoQ query timeout".to_string()))
            }
        }
    }

    /// # Cancel safety:
    /// A connection is published only after its handshake completes; cancellation releases the pending handle.
    async fn get_or_connect_doq(&self, endpoint: &quinn::Endpoint) -> Result<quinn::Connection, EncryptedDnsError> {
        // Try cached connection.
        {
            let guard = self.inner.doq_connection.lock().await;
            if let Some(ref conn) = *guard
                && conn.close_reason().is_none()
            {
                return Ok(conn.clone());
            }
        }
        let server_name = self.inner.endpoint.tls_server_name.as_deref().unwrap_or(&self.inner.endpoint.host);
        let bootstrap_ips = self.inner.health.as_ref().map_or_else(
            || self.inner.endpoint.bootstrap_ips.clone(),
            |health| health.rank_bootstrap_ips_in_scope(&self.inner.network_scope, &self.inner.endpoint.bootstrap_ips),
        );
        if bootstrap_ips.is_empty() {
            return Err(EncryptedDnsError::MissingBootstrapIps);
        }

        let mut last_error = None;
        for ip in bootstrap_ips {
            let started = Instant::now();
            let addr = SocketAddr::new(ip, self.inner.endpoint.port);
            let result = timeout(self.inner.timeout, async {
                endpoint
                    .connect(addr, server_name)
                    .map_err(|error| EncryptedDnsError::Tls(format!("DoQ connect: {error}")))?
                    .await
                    .map_err(|error| EncryptedDnsError::Tls(format!("DoQ handshake: {error}")))
            })
            .await
            .map_err(|_| EncryptedDnsError::Request("DoQ connect timeout".to_string()))
            .and_then(std::convert::identity);
            let success = result.is_ok();
            if let Some(health) = &self.inner.health {
                let latency_ms = started.elapsed().as_millis().try_into().unwrap_or(u64::MAX);
                health.record_bootstrap_outcome_in_scope(&self.inner.network_scope, ip, success, latency_ms);
            }
            match result {
                Ok(conn) => {
                    *self.inner.doq_connection.lock().await = Some(conn.clone());
                    return Ok(conn);
                }
                Err(error) => last_error = Some(error),
            }
        }

        Err(last_error.unwrap_or(EncryptedDnsError::MissingBootstrapIps))
    }
}

fn doq_length_prefix(message_len: usize) -> Result<[u8; 2], EncryptedDnsError> {
    if message_len < 12 {
        return Err(EncryptedDnsError::Request("DoQ query is shorter than a DNS header".to_string()));
    }
    let length = u16::try_from(message_len).map_err(|_| EncryptedDnsError::DnsMessageTooLarge {
        transport: "DoQ",
        size: message_len,
        max: usize::from(u16::MAX),
    })?;
    Ok(length.to_be_bytes())
}

/// # Cancel safety:
/// Each call owns one bidirectional stream. Dropping it aborts that exchange without reusing partial framing.
async fn exchange_doq_query(
    conn: quinn::Connection,
    query_bytes: &[u8],
    len_prefix: [u8; 2],
) -> Result<Vec<u8>, EncryptedDnsError> {
    let (mut send, mut recv) =
        conn.open_bi().await.map_err(|e| EncryptedDnsError::Request(format!("DoQ open_bi: {e}")))?;

    // RFC 9250: DNS wire format with 2-byte length prefix (same as DNS-over-TCP).
    send.write_all(&len_prefix).await.map_err(|e| EncryptedDnsError::Request(format!("DoQ write: {e}")))?;
    // RFC 9250 section 4.2.1 uses the stream ID to correlate queries, so the DNS ID is zero on the wire.
    send.write_all(&[0, 0]).await.map_err(|e| EncryptedDnsError::Request(format!("DoQ write ID: {e}")))?;
    send.write_all(&query_bytes[2..]).await.map_err(|e| EncryptedDnsError::Request(format!("DoQ write: {e}")))?;
    send.finish().map_err(|e| EncryptedDnsError::Request(format!("DoQ finish: {e}")))?;

    let mut len_buf = [0u8; 2];
    recv.read_exact(&mut len_buf).await.map_err(|e| EncryptedDnsError::DnsParse(format!("DoQ read len: {e}")))?;
    let resp_len = u16::from_be_bytes(len_buf) as usize;
    if resp_len < 12 {
        conn.close(2u32.into(), b"DoQ short DNS header");
        return Err(EncryptedDnsError::DnsParse(format!("invalid DoQ response length: {resp_len}")));
    }
    let mut response = vec![0u8; resp_len];
    recv.read_exact(&mut response).await.map_err(|e| EncryptedDnsError::DnsParse(format!("DoQ read body: {e}")))?;

    if response[..2] != [0, 0] {
        conn.close(2u32.into(), b"DoQ nonzero DNS ID");
        return Err(EncryptedDnsError::DnsParse("DoQ response has a nonzero DNS ID".to_string()));
    }
    if recv.read_to_end(0).await.is_err() {
        conn.close(2u32.into(), b"DoQ trailing response data");
        return Err(EncryptedDnsError::DnsParse("DoQ response stream has trailing data or was reset".to_string()));
    }
    response[..2].copy_from_slice(&query_bytes[..2]);
    Ok(response)
}

pub(super) fn build_doq_endpoint(
    endpoint: &EncryptedDnsEndpoint,
    connect_hooks: &EncryptedDnsConnectHooks,
) -> io::Result<quinn::Endpoint> {
    let bind_addr = doq_bind_addr(endpoint)?;
    if let Some(binder) = &connect_hooks.direct_udp_binder {
        let socket = binder(bind_addr)?;
        return quinn::Endpoint::new(quinn::EndpointConfig::default(), None, socket, Arc::new(quinn::TokioRuntime));
    }
    if connect_hooks.requires_direct_udp_binder() {
        return Err(io::Error::new(io::ErrorKind::NotConnected, "direct UDP binder is required"));
    }
    // allow: hook-gated fallback — binds an unprotected UDP socket only when no
    // protected binder is supplied AND none is required (non-VPN / no active TUN
    // context). On Android the integrator supplies a protect()-backed binder and
    // sets require_direct_udp_binder(); see vpnservice-protect-invariant.md.
    quinn::Endpoint::client(bind_addr)
}

fn doq_bind_addr(endpoint: &EncryptedDnsEndpoint) -> io::Result<SocketAddr> {
    let Some(ip) = endpoint.bootstrap_ips.first() else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "DoQ bootstrap requires at least one bootstrap IP"));
    };

    let bind_ip = match ip {
        std::net::IpAddr::V4(_) => std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED),
        std::net::IpAddr::V6(_) => std::net::IpAddr::V6(std::net::Ipv6Addr::UNSPECIFIED),
    };
    Ok(SocketAddr::new(bind_ip, 0))
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr, UdpSocket};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use super::{build_doq_endpoint, doq_length_prefix};
    use crate::types::{EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol};

    fn doq_endpoint() -> EncryptedDnsEndpoint {
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doq,
            resolver_id: None,
            host: "fixture.test".to_string(),
            port: 853,
            tls_server_name: None,
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1))],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        }
    }

    #[test]
    fn build_doq_endpoint_fails_closed_without_binder_when_required() {
        let hooks = EncryptedDnsConnectHooks::new().require_direct_udp_binder();
        let err = build_doq_endpoint(&doq_endpoint(), &hooks)
            .expect_err("a required UDP binder with none supplied must fail closed, not bind unprotected");
        assert_eq!(err.kind(), std::io::ErrorKind::NotConnected);
        assert!(err.to_string().contains("direct UDP binder is required"), "unexpected error: {err}");
    }

    #[tokio::test]
    async fn build_doq_endpoint_uses_provided_binder_when_required() {
        let calls = Arc::new(AtomicUsize::new(0));
        let observed = calls.clone();
        let hooks =
            EncryptedDnsConnectHooks::new().require_direct_udp_binder().with_direct_udp_binder(move |bind_addr| {
                observed.fetch_add(1, Ordering::Relaxed);
                UdpSocket::bind(bind_addr)
            });
        build_doq_endpoint(&doq_endpoint(), &hooks).expect("the protected binder path must build the endpoint");
        assert_eq!(calls.load(Ordering::Relaxed), 1, "the protected binder must be invoked exactly once");
    }

    #[test]
    fn doq_length_prefix_rejects_short_dns_headers() {
        for size in [0, 1, 11] {
            assert!(doq_length_prefix(size).is_err());
        }
    }

    #[test]
    fn doq_length_prefix_rejects_oversized_messages_without_truncation() {
        let size = usize::from(u16::MAX) + 1;
        let error = doq_length_prefix(size).expect_err("oversized DoQ message must fail");
        assert!(matches!(
            error,
            EncryptedDnsError::DnsMessageTooLarge {
                transport: "DoQ",
                size: actual,
                max
            } if actual == size && max == usize::from(u16::MAX)
        ));
    }
}

#[cfg(test)]
#[path = "doq_tests.rs"]
mod wire_tests;
