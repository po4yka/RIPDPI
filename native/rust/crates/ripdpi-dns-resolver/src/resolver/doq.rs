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
            if let Some(ref conn) = *guard {
                if conn.close_reason().is_none() {
                    return Ok(conn.clone());
                }
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
