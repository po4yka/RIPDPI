mod bootstrap;
mod health;
mod hooks;
mod socks5;
mod timeouts;
mod tokio_connect;

use tokio::net::TcpStream as TokioTcpStream;

use super::EncryptedDnsResolver;
use crate::transport::resolve_socket_addr;
use crate::types::{EncryptedDnsError, EncryptedDnsTransport};

impl EncryptedDnsResolver {
    pub(super) async fn connect_plain_tcp(&self) -> Result<TokioTcpStream, EncryptedDnsError> {
        match &self.inner.transport {
            EncryptedDnsTransport::Direct => self.connect_direct_tcp().await,
            EncryptedDnsTransport::Socks5 { host, port } => self.connect_socks5_tcp(host, *port).await,
        }
    }

    async fn connect_direct_tcp(&self) -> Result<TokioTcpStream, EncryptedDnsError> {
        if let Some(connector) = &self.inner.connect_hooks.direct_tcp_connector {
            return hooks::connect_direct_tcp_with_hook(&self.inner, connector.clone()).await;
        }
        self.connect_direct_tcp_with(tokio_connect::connect).await
    }

    async fn connect_direct_tcp_with<S, C, F>(&self, connect: C) -> Result<S, EncryptedDnsError>
    where
        S: super::connection::TcpClientStream,
        C: FnMut(std::net::SocketAddr) -> F,
        F: std::future::Future<Output = std::io::Result<S>>,
    {
        bootstrap::connect_direct_tcp_with(&self.inner, connect).await
    }

    async fn connect_socks5_tcp(&self, proxy_host: &str, proxy_port: u16) -> Result<TokioTcpStream, EncryptedDnsError> {
        let proxy_target = resolve_socket_addr(proxy_host, proxy_port)?;
        self.connect_socks5_tcp_with(proxy_target, tokio_connect::connect).await
    }

    async fn connect_socks5_tcp_with<S, C, F>(
        &self,
        proxy_target: std::net::SocketAddr,
        connect: C,
    ) -> Result<S, EncryptedDnsError>
    where
        S: super::connection::TcpClientStream,
        C: FnMut(std::net::SocketAddr) -> F,
        F: std::future::Future<Output = std::io::Result<S>>,
    {
        socks5::connect_socks5_tcp_with(&self.inner, proxy_target, connect).await
    }
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};
    use std::time::Duration;

    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use super::*;
    use crate::types::{EncryptedDnsEndpoint, EncryptedDnsProtocol};

    fn turmoil_test_endpoint(host: &str, port: u16, bootstrap_ips: Vec<IpAddr>) -> EncryptedDnsEndpoint {
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: Some("turmoil".to_string()),
            host: host.to_string(),
            port,
            tls_server_name: Some(host.to_string()),
            bootstrap_ips,
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }
    }

    #[test]
    fn turmoil_direct_tcp_falls_back_after_partitioned_bootstrap() -> turmoil::Result {
        let mut sim = turmoil::Builder::new().build();

        sim.host("primary", || async move {
            let _listener = turmoil::net::TcpListener::bind((IpAddr::V4(Ipv4Addr::UNSPECIFIED), 853)).await?;
            tokio::time::sleep(Duration::from_secs(1)).await;
            Ok(())
        });

        sim.host("secondary", || async move {
            let listener = turmoil::net::TcpListener::bind((IpAddr::V4(Ipv4Addr::UNSPECIFIED), 853)).await?;
            let (mut stream, _) = listener.accept().await?;
            stream.write_all(b"ok").await?;
            Ok(())
        });

        sim.client("client", async move {
            turmoil::partition("client", "primary");

            let resolver = EncryptedDnsResolver::with_timeout(
                turmoil_test_endpoint(
                    "fixture.test",
                    853,
                    vec![turmoil::lookup("primary"), turmoil::lookup("secondary")],
                ),
                EncryptedDnsTransport::Direct,
                Duration::from_millis(100),
            )
            .expect("resolver builds");

            let mut stream = resolver
                .connect_direct_tcp_with(turmoil::net::TcpStream::connect)
                .await
                .expect("resolver should fall back to the second bootstrap address");

            let mut buf = [0u8; 2];
            stream.read_exact(&mut buf).await.expect("secondary server reply");
            assert_eq!(&buf, b"ok");
            Ok(())
        });

        sim.run()
    }

    #[test]
    fn turmoil_socks5_handshake_timeout_is_deterministic() -> turmoil::Result {
        let mut sim = turmoil::Builder::new().build();

        sim.host("proxy", || async move {
            let listener = turmoil::net::TcpListener::bind((IpAddr::V4(Ipv4Addr::UNSPECIFIED), 1080)).await?;
            let (_stream, _) = listener.accept().await?;
            tokio::time::sleep(Duration::from_secs(1)).await;
            Ok(())
        });

        sim.client("client", async move {
            let proxy_ip = turmoil::lookup("proxy");
            let resolver = EncryptedDnsResolver::with_timeout(
                turmoil_test_endpoint("fixture.test", 853, vec![IpAddr::V4(Ipv4Addr::new(198, 18, 0, 30))]),
                EncryptedDnsTransport::Socks5 { host: proxy_ip.to_string(), port: 1080 },
                Duration::from_millis(50),
            )
            .expect("resolver builds");

            let err = resolver
                .connect_socks5_tcp_with(SocketAddr::new(proxy_ip, 1080), turmoil::net::TcpStream::connect)
                .await
                .expect_err("stalled SOCKS5 proxy should time out");

            match err {
                EncryptedDnsError::Socks5(message) => {
                    assert!(message.contains("timed out"), "expected a timeout error, got: {message}");
                }
                other => panic!("expected SOCKS5 timeout, got {other:?}"),
            }
            Ok(())
        });

        sim.run()
    }
}
