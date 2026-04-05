use std::io;
use std::net::ToSocketAddrs;
use std::sync::Arc;

use bytes::{Buf, Bytes};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use url::Url;

use crate::cloudflare;
use crate::config::MasqueConfig;

/// Trait alias for an async bidirectional stream.
pub trait AsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

/// Open an HTTP/3 Extended CONNECT tunnel (Connect-TCP, RFC 9484) to the target.
///
/// Returns a bidirectional stream that carries raw TCP data to/from the target
/// through the MASQUE proxy.
pub async fn h3_connect_tcp(config: &MasqueConfig, target: &str) -> io::Result<impl AsyncIo> {
    let parsed = Url::parse(&config.url)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid MASQUE URL: {e}")))?;

    let host =
        parsed.host_str().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL has no host"))?;
    let port = parsed.port().unwrap_or(443);

    let addr = format!("{host}:{port}")
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "failed to resolve MASQUE server"))?;

    // Build rustls config for QUIC
    let mut root_store = rustls::RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let tls_config = rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions")
        .with_root_certificates(root_store)
        .with_no_client_auth();

    let quinn_config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config)
            .map_err(|e| io::Error::new(io::ErrorKind::Other, format!("QUIC TLS config: {e}")))?,
    ));

    let mut endpoint = quinn::Endpoint::client("0.0.0.0:0".parse().unwrap())
        .map_err(|e| io::Error::new(io::ErrorKind::Other, format!("QUIC endpoint: {e}")))?;
    endpoint.set_default_client_config(quinn_config);

    let quic_conn = endpoint
        .connect(addr, host)
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("QUIC connect: {e}")))?
        .await
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("QUIC handshake: {e}")))?;

    tracing::debug!(%addr, "QUIC connection established to MASQUE server");

    let h3_conn = h3_quinn::Connection::new(quic_conn);
    let (mut driver, send_request) = h3::client::new(h3_conn)
        .await
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("H3 setup: {e}")))?;

    // Drive the H3 connection in the background
    tokio::spawn(async move {
        let e = std::future::poll_fn(|cx| driver.poll_close(cx)).await;
        tracing::warn!("H3 connection driver closed: {e}");
    });

    extended_connect(send_request, config, target).await
}

/// Send an HTTP/3 Extended CONNECT request for Connect-TCP.
async fn extended_connect(
    mut send_request: h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>,
    config: &MasqueConfig,
    target: &str,
) -> io::Result<impl AsyncIo> {
    let mut builder = http::Request::builder()
        .method("CONNECT")
        .uri(format!("/{target}"))
        .header(":protocol", "connect-tcp")
        .header(":authority", target);

    // Add auth headers
    if config.cloudflare_mode {
        for (name, value) in cloudflare::cloudflare_auth_headers(config) {
            builder = builder.header(name, value);
        }
    } else if let Some(ref token) = config.auth_token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }

    let request =
        builder.body(()).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, format!("H3 request build: {e}")))?;

    let mut stream = send_request
        .send_request(request)
        .await
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("H3 CONNECT send: {e}")))?;

    let response = stream
        .recv_response()
        .await
        .map_err(|e| io::Error::new(io::ErrorKind::ConnectionRefused, format!("H3 CONNECT response: {e}")))?;

    if response.status() != http::StatusCode::OK {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("MASQUE server rejected CONNECT: {}", response.status()),
        ));
    }

    tracing::debug!(target, "H3 Extended CONNECT tunnel established");

    Ok(spawn_h3_bridge(stream))
}

/// Drives an H3 bidirectional stream, bridging it to a duplex tokio I/O pipe.
///
/// Spawns a background task that owns the `RequestStream` and shuttles data in
/// both directions between the H3 stream and the returned `DuplexStream`.
fn spawn_h3_bridge(mut stream: h3::client::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>) -> impl AsyncIo {
    let (app_io, bridge_io) = tokio::io::duplex(64 * 1024);
    let (mut bridge_reader, mut bridge_writer) = tokio::io::split(bridge_io);

    tokio::spawn(async move {
        let mut send_buf = vec![0u8; 16 * 1024];
        loop {
            tokio::select! {
                // Server → client: receive H3 data, write into the duplex pipe.
                recv_result = stream.recv_data() => {
                    match recv_result {
                        Ok(Some(mut data)) => {
                            let bytes = data.copy_to_bytes(data.remaining());
                            if let Err(e) = bridge_writer.write_all(&bytes).await {
                                tracing::debug!("H3 bridge write to duplex failed: {e}");
                                break;
                            }
                        }
                        Ok(None) => {
                            tracing::debug!("H3 stream recv_data returned None (stream ended)");
                            break;
                        }
                        Err(e) => {
                            tracing::debug!("H3 stream recv_data error: {e}");
                            break;
                        }
                    }
                }
                // Client → server: read from the duplex pipe, send as H3 data.
                read_result = bridge_reader.read(&mut send_buf) => {
                    match read_result {
                        Ok(0) => {
                            tracing::debug!("H3 bridge duplex reader closed");
                            break;
                        }
                        Ok(n) => {
                            let data = Bytes::copy_from_slice(&send_buf[..n]);
                            if let Err(e) = stream.send_data(data).await {
                                tracing::debug!("H3 stream send_data error: {e}");
                                break;
                            }
                        }
                        Err(e) => {
                            tracing::debug!("H3 bridge read from duplex failed: {e}");
                            break;
                        }
                    }
                }
            }
        }
        // Gracefully finish the H3 send side.
        let _ = stream.finish().await;
    });

    app_io
}
