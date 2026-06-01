use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use tokio::time::timeout;

use super::EncryptedDnsResolver;
use crate::types::{EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsTransport};

impl EncryptedDnsResolver {
    pub(super) async fn exchange_doq(&self, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
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
        match timeout(self.inner.timeout, exchange_doq_query(conn.clone(), query_bytes)).await {
            Ok(result) => result,
            Err(_) => {
                conn.close(0u32.into(), b"DoQ query timeout");
                *self.inner.doq_connection.lock().await = None;
                Err(EncryptedDnsError::Request("DoQ query timeout".to_string()))
            }
        }
    }

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
        // New connection.
        let addr = self.resolve_doq_addr()?;
        let server_name = self.inner.endpoint.tls_server_name.as_deref().unwrap_or(&self.inner.endpoint.host);
        let conn = timeout(self.inner.timeout, async {
            endpoint
                .connect(addr, server_name)
                .map_err(|e| EncryptedDnsError::Tls(format!("DoQ connect: {e}")))?
                .await
                .map_err(|e| EncryptedDnsError::Tls(format!("DoQ handshake: {e}")))
        })
        .await
        .map_err(|_| EncryptedDnsError::Request("DoQ connect timeout".to_string()))??;

        *self.inner.doq_connection.lock().await = Some(conn.clone());
        Ok(conn)
    }

    fn resolve_doq_addr(&self) -> Result<SocketAddr, EncryptedDnsError> {
        let ip = self.inner.endpoint.bootstrap_ips.first().ok_or(EncryptedDnsError::MissingBootstrapIps)?;
        Ok(SocketAddr::new(*ip, self.inner.endpoint.port))
    }
}

async fn exchange_doq_query(conn: quinn::Connection, query_bytes: &[u8]) -> Result<Vec<u8>, EncryptedDnsError> {
    let (mut send, mut recv) =
        conn.open_bi().await.map_err(|e| EncryptedDnsError::Request(format!("DoQ open_bi: {e}")))?;

    // RFC 9250: DNS wire format with 2-byte length prefix (same as DNS-over-TCP).
    let len_prefix = (query_bytes.len() as u16).to_be_bytes();
    send.write_all(&len_prefix).await.map_err(|e| EncryptedDnsError::Request(format!("DoQ write: {e}")))?;
    send.write_all(query_bytes).await.map_err(|e| EncryptedDnsError::Request(format!("DoQ write: {e}")))?;
    send.finish().map_err(|e| EncryptedDnsError::Request(format!("DoQ finish: {e}")))?;

    let mut len_buf = [0u8; 2];
    recv.read_exact(&mut len_buf).await.map_err(|e| EncryptedDnsError::DnsParse(format!("DoQ read len: {e}")))?;
    let resp_len = u16::from_be_bytes(len_buf) as usize;
    if resp_len == 0 || resp_len > 65535 {
        return Err(EncryptedDnsError::DnsParse(format!("invalid DoQ response length: {resp_len}")));
    }
    let mut response = vec![0u8; resp_len];
    recv.read_exact(&mut response).await.map_err(|e| EncryptedDnsError::DnsParse(format!("DoQ read body: {e}")))?;

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

    use super::build_doq_endpoint;
    use crate::types::{EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsProtocol};

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
}
