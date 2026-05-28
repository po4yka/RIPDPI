mod bootstrap;
mod health;
mod hooks;
mod socks5;
mod timeouts;
mod tokio_connect;

use tokio::net::TcpStream as TokioTcpStream;

use super::EncryptedDnsResolver;
use crate::transport::resolve_socket_addr;
use crate::types::{BoxedDnsTcpStream, EncryptedDnsError, EncryptedDnsTransport};

impl EncryptedDnsResolver {
    pub(super) async fn connect_plain_tcp(&self) -> Result<TokioTcpStream, EncryptedDnsError> {
        match &self.inner.transport {
            EncryptedDnsTransport::Direct => self.connect_direct_tcp().await,
            EncryptedDnsTransport::Socks5 { host, port } => self.connect_socks5_tcp(host, *port).await,
        }
    }

    async fn connect_direct_tcp(&self) -> Result<TokioTcpStream, EncryptedDnsError> {
        if let Some(connector) = &self.inner.connect_hooks.direct_tcp_connector {
            return hooks::connect_tokio_tcp_with_hook(&self.inner, connector.clone()).await;
        }
        self.connect_direct_tcp_with(tokio_connect::connect).await
    }

    pub(super) async fn connect_doh_tcp(&self) -> Result<BoxedDnsTcpStream, EncryptedDnsError> {
        match &self.inner.transport {
            EncryptedDnsTransport::Direct => {
                if let Some(connector) = &self.inner.connect_hooks.direct_tcp_connector {
                    hooks::connect_boxed_tcp_with_hook(&self.inner, connector.clone()).await
                } else {
                    self.connect_direct_tcp().await.map(|stream| Box::new(stream) as BoxedDnsTcpStream)
                }
            }
            EncryptedDnsTransport::Socks5 { host, port } => {
                self.connect_socks5_tcp(host, *port).await.map(|stream| Box::new(stream) as BoxedDnsTcpStream)
            }
        }
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
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;
    use std::time::Duration;

    use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    use rustls::ServerConfig;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio_rustls::TlsAcceptor;

    use super::*;
    use crate::types::{BoxedDnsTcpStream, EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsProtocol};

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

    fn turmoil_doh_endpoint(port: u16, bootstrap_ips: Vec<IpAddr>) -> EncryptedDnsEndpoint {
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("turmoil".to_string()),
            host: "fixture.test".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips,
            doh_url: Some(format!("https://fixture.test:{port}/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        }
    }

    #[test]
    fn turmoil_doh_uses_async_direct_tcp_connector_for_relay_stream() -> turmoil::Result {
        let certificate = rcgen::generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
        let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
        let server_config = Arc::new(
            ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .expect("ring provider supports default TLS versions")
                .with_no_client_auth()
                .with_single_cert(vec![certificate_der.clone()], key_der)
                .expect("server config"),
        );
        let relay_connects = Arc::new(AtomicUsize::new(0));
        let mut sim = turmoil::Builder::new().build();

        let doh_config = server_config.clone();
        sim.host("doh", move || {
            let doh_config = doh_config.clone();
            async move {
                let listener = turmoil::net::TcpListener::bind((IpAddr::V4(Ipv4Addr::UNSPECIFIED), 443)).await?;
                let (stream, _) = listener.accept().await?;
                let acceptor = TlsAcceptor::from(doh_config);
                let mut stream = acceptor.accept(stream).await?;
                let mut request = Vec::new();
                loop {
                    let mut byte = [0u8; 1];
                    stream.read_exact(&mut byte).await?;
                    request.push(byte[0]);
                    if request.ends_with(b"\r\n\r\nrelay-query") {
                        break;
                    }
                }
                stream
                    .write_all(
                        b"HTTP/1.1 200 OK\r\ncontent-type: application/dns-message\r\ncontent-length: 14\r\n\r\nrelay-response",
                    )
                    .await?;
                stream.shutdown().await?;
                Ok(())
            }
        });

        let client_connects = relay_connects.clone();
        sim.client("client", async move {
            let doh_ip = turmoil::lookup("doh");
            let assert_connects = client_connects.clone();
            let resolver = EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
                turmoil_doh_endpoint(443, vec![doh_ip]),
                EncryptedDnsTransport::Direct,
                Duration::from_millis(500),
                vec![certificate_der],
                EncryptedDnsConnectHooks::new().with_direct_tcp_connector(move |target, _timeout| {
                    let client_connects = client_connects.clone();
                    async move {
                        client_connects.fetch_add(1, Ordering::Relaxed);
                        let stream = turmoil::net::TcpStream::connect(target).await?;
                        Ok(Box::new(stream) as BoxedDnsTcpStream)
                    }
                }),
            )
            .expect("resolver builds");

            let response = resolver.exchange(b"relay-query").await.expect("DoH response over relay connector");
            assert_eq!(response, b"relay-response");
            assert_eq!(assert_connects.load(Ordering::Relaxed), 1, "DoH must dial once through the relay connector");
            Ok(())
        });

        sim.run()
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
