use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream as TokioTcpStream;
use tokio::time::timeout;

use super::connection::TcpClientStream;
use super::EncryptedDnsResolver;
use crate::transport::{consume_socks5_bind_address_async, resolve_socket_addr};
use crate::types::{DirectTcpConnector, EncryptedDnsError, EncryptedDnsTransport};

impl EncryptedDnsResolver {
    pub(super) async fn connect_plain_tcp(&self) -> Result<TokioTcpStream, EncryptedDnsError> {
        match &self.inner.transport {
            EncryptedDnsTransport::Direct => self.connect_direct_tcp().await,
            EncryptedDnsTransport::Socks5 { host, port } => self.connect_socks5_tcp(host, *port).await,
        }
    }

    async fn connect_direct_tcp(&self) -> Result<TokioTcpStream, EncryptedDnsError> {
        if let Some(connector) = &self.inner.connect_hooks.direct_tcp_connector {
            return self.connect_direct_tcp_with_hook(connector.clone()).await;
        }
        self.connect_direct_tcp_with(TokioTcpStream::connect).await
    }

    async fn connect_direct_tcp_with_hook(
        &self,
        connector: Arc<DirectTcpConnector>,
    ) -> Result<TokioTcpStream, EncryptedDnsError> {
        let endpoint = &self.inner.endpoint;
        let ips = if let Some(health) = &self.inner.health {
            health.rank_bootstrap_ips(&endpoint.bootstrap_ips)
        } else {
            endpoint.bootstrap_ips.clone()
        };
        let mut last_error = None;

        for ip in ips {
            let address = SocketAddr::new(ip, endpoint.port);
            let started = Instant::now();
            let connector = connector.clone();
            let timeout = self.inner.timeout;
            match tokio::task::spawn_blocking(move || connector(address, timeout)).await {
                Ok(Ok(stream)) => {
                    let _ = stream.set_nodelay(true);
                    stream.set_nonblocking(true).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
                    let stream =
                        TokioTcpStream::from_std(stream).map_err(|err| EncryptedDnsError::Request(err.to_string()))?;
                    if let Some(health) = &self.inner.health {
                        let latency_ms = started.elapsed().as_millis().try_into().unwrap_or(u64::MAX);
                        health.record_bootstrap_outcome(ip, true, latency_ms);
                    }
                    return Ok(stream);
                }
                Ok(Err(err)) => {
                    if let Some(health) = &self.inner.health {
                        let latency_ms = started.elapsed().as_millis().try_into().unwrap_or(u64::MAX);
                        health.record_bootstrap_outcome(ip, false, latency_ms);
                    }
                    last_error = Some(err.to_string());
                }
                Err(err) => {
                    return Err(EncryptedDnsError::TaskJoin(err.to_string()));
                }
            }
        }

        Err(EncryptedDnsError::Request(last_error.unwrap_or_else(|| "no bootstrap addresses".to_string())))
    }

    async fn connect_direct_tcp_with<S, C, F>(&self, mut connect: C) -> Result<S, EncryptedDnsError>
    where
        S: TcpClientStream,
        C: FnMut(SocketAddr) -> F,
        F: Future<Output = io::Result<S>>,
    {
        let endpoint = &self.inner.endpoint;
        let ips = if let Some(health) = &self.inner.health {
            health.rank_bootstrap_ips(&endpoint.bootstrap_ips)
        } else {
            endpoint.bootstrap_ips.clone()
        };
        let mut last_error = None;
        for ip in ips {
            let address = std::net::SocketAddr::new(ip, endpoint.port);
            let started = std::time::Instant::now();
            match timeout(self.inner.timeout, connect(address)).await {
                Ok(Ok(stream)) => {
                    let _ = stream.set_nodelay_if_supported(true);
                    if let Some(health) = &self.inner.health {
                        let latency_ms = started.elapsed().as_millis().try_into().unwrap_or(u64::MAX);
                        health.record_bootstrap_outcome(ip, true, latency_ms);
                    }
                    return Ok(stream);
                }
                Ok(Err(err)) => {
                    if let Some(health) = &self.inner.health {
                        let latency_ms = self.inner.timeout.as_millis().try_into().unwrap_or(u64::MAX);
                        health.record_bootstrap_outcome(ip, false, latency_ms);
                    }
                    last_error = Some(err.to_string());
                }
                Err(_) => {
                    if let Some(health) = &self.inner.health {
                        let latency_ms = self.inner.timeout.as_millis().try_into().unwrap_or(u64::MAX);
                        health.record_bootstrap_outcome(ip, false, latency_ms);
                    }
                    last_error = Some(format!("connect to {address} timed out"));
                }
            }
        }
        Err(EncryptedDnsError::Request(last_error.unwrap_or_else(|| "no bootstrap addresses".to_string())))
    }

    async fn connect_socks5_tcp(&self, proxy_host: &str, proxy_port: u16) -> Result<TokioTcpStream, EncryptedDnsError> {
        let proxy_target = resolve_socket_addr(proxy_host, proxy_port)?;
        self.connect_socks5_tcp_with(proxy_target, TokioTcpStream::connect).await
    }

    async fn connect_socks5_tcp_with<S, C, F>(
        &self,
        proxy_target: SocketAddr,
        mut connect: C,
    ) -> Result<S, EncryptedDnsError>
    where
        S: TcpClientStream,
        C: FnMut(SocketAddr) -> F,
        F: Future<Output = io::Result<S>>,
    {
        let mut proxy_stream = match timeout(self.inner.timeout, connect(proxy_target)).await {
            Ok(Ok(stream)) => stream,
            Ok(Err(err)) => return Err(EncryptedDnsError::Socks5(format!("connect to proxy {proxy_target}: {err}"))),
            Err(_) => {
                return Err(EncryptedDnsError::Socks5(format!("connect to proxy {proxy_target} timed out")));
            }
        };
        let _ = proxy_stream.set_nodelay_if_supported(true);

        let host_bytes = self.inner.endpoint.host.as_bytes();
        if host_bytes.len() > u8::MAX as usize {
            return Err(EncryptedDnsError::Socks5("resolver host is too long".to_string()));
        }

        match timeout(self.inner.timeout, async {
            proxy_stream
                .write_all(&[0x05, 0x01, 0x00])
                .await
                .map_err(|err| EncryptedDnsError::Socks5(format!("write auth greeting to {proxy_target}: {err}")))?;
            let mut auth_reply = [0u8; 2];
            proxy_stream
                .read_exact(&mut auth_reply)
                .await
                .map_err(|err| EncryptedDnsError::Socks5(format!("read auth reply from {proxy_target}: {err}")))?;
            if auth_reply != [0x05, 0x00] {
                return Err(EncryptedDnsError::Socks5(format!("unexpected auth reply: {auth_reply:?}")));
            }

            let mut request = Vec::with_capacity(host_bytes.len() + 7);
            request.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8]);
            request.extend_from_slice(host_bytes);
            request.extend_from_slice(&self.inner.endpoint.port.to_be_bytes());
            proxy_stream
                .write_all(&request)
                .await
                .map_err(|err| EncryptedDnsError::Socks5(format!("write connect request to {proxy_target}: {err}")))?;

            let mut header = [0u8; 4];
            proxy_stream
                .read_exact(&mut header)
                .await
                .map_err(|err| EncryptedDnsError::Socks5(format!("read connect reply from {proxy_target}: {err}")))?;
            if header[1] != 0x00 {
                return Err(EncryptedDnsError::Socks5(format!("connect reply {:x}", header[1])));
            }
            consume_socks5_bind_address_async(&mut proxy_stream, header[3]).await?;
            Ok::<(), EncryptedDnsError>(())
        })
        .await
        {
            Ok(result) => result?,
            Err(_) => {
                return Err(EncryptedDnsError::Socks5("SOCKS5 handshake timed out".to_string()));
            }
        }

        Ok(proxy_stream)
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
