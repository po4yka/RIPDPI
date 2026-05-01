use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::Bytes;
use http_body_util::BodyExt;
use hyper::StatusCode;
use rand::RngExt;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream, ReadBuf};
use tokio::sync::{mpsc, OwnedSemaphorePermit};

use crate::config::{normalize_path, XhttpMode};
use crate::h2_body::{build_get_request, build_post_request, ChannelBody};
use crate::pool::PooledConnection;

const STREAM_BUFFER_SIZE: usize = 64 * 1024;
const BODY_CHUNK_SIZE: usize = 16 * 1024;
pub(crate) const HEADER_PADDING_MIN: usize = 100;
pub(crate) const HEADER_PADDING_MAX: usize = 1000;

pub struct XhttpStream {
    reader: DuplexStream,
    writer: DuplexStream,
    _permit: OwnedSemaphorePermit,
}

impl AsyncRead for XhttpStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.reader).poll_read(cx, buf)
    }
}

impl AsyncWrite for XhttpStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<Result<usize, io::Error>> {
        Pin::new(&mut self.writer).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), io::Error>> {
        Pin::new(&mut self.writer).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), io::Error>> {
        Pin::new(&mut self.writer).poll_shutdown(cx)
    }
}

trait StreamMetadata {
    fn session_path(&self) -> String;
    fn host_header(&self) -> String;
    fn uuid(&self) -> &[u8; 16];
}

impl StreamMetadata for XhttpMode {
    fn session_path(&self) -> String {
        match self {
            Self::Reality(config) => stream_up_path(&config.path, &random_session_id()),
            Self::Tls(config) => stream_up_path(&config.path, &random_session_id()),
        }
    }

    fn host_header(&self) -> String {
        match self {
            Self::Reality(config) => config.host.clone().unwrap_or_else(|| config.vless.server_name.clone()),
            Self::Tls(config) => config.host.clone().unwrap_or_else(|| config.server_name.clone()),
        }
    }

    fn uuid(&self) -> &[u8; 16] {
        match self {
            Self::Reality(config) => &config.vless.uuid,
            Self::Tls(config) => &config.uuid,
        }
    }
}

impl PooledConnection {
    pub(crate) async fn open_stream_from_mode(
        &self,
        mode: &XhttpMode,
        target: &str,
        permit: OwnedSemaphorePermit,
    ) -> io::Result<XhttpStream> {
        let stream_path = mode.session_path();
        let host_header = mode.host_header();
        let referer = referer_padding(&host_header, &stream_path);
        let header_padding = random_padding_value();

        let mut sender = self.sender.lock().await;
        let get_request = build_get_request(&stream_path, &host_header, &referer, &header_padding)?;
        let get_response = sender.send_request(get_request).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP GET request failed: {error}"))
        })?;
        if get_response.status() != StatusCode::OK {
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("xHTTP GET rejected: {}", get_response.status()),
            ));
        }

        let (outgoing_tx, outgoing_rx) = mpsc::channel::<io::Result<Bytes>>(64);
        let post_request =
            build_post_request(&stream_path, &host_header, &referer, &header_padding, ChannelBody::new(outgoing_rx))?;
        let post_response = sender.send_request(post_request).await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("xHTTP POST request failed: {error}"))
        })?;
        drop(sender);
        if !post_response.status().is_success() {
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!("xHTTP POST rejected: {}", post_response.status()),
            ));
        }

        let (mut user_upload, mut transport_upload) = tokio::io::duplex(STREAM_BUFFER_SIZE);
        let (mut transport_download, user_download) = tokio::io::duplex(STREAM_BUFFER_SIZE);

        tokio::spawn(async move {
            let mut buffer = vec![0u8; BODY_CHUNK_SIZE];
            loop {
                match transport_upload.read(&mut buffer).await {
                    Ok(0) => break,
                    Ok(read) => {
                        if outgoing_tx.send(Ok(Bytes::copy_from_slice(&buffer[..read]))).await.is_err() {
                            break;
                        }
                    }
                    Err(error) => {
                        let _ = outgoing_tx.send(Err(error)).await;
                        break;
                    }
                }
            }
        });

        tokio::spawn(async move {
            let mut body = get_response.into_body();
            while let Some(frame) = body.frame().await {
                let frame = match frame {
                    Ok(frame) => frame,
                    Err(error) => {
                        tracing::debug!(error = %error, "xHTTP GET stream failed");
                        break;
                    }
                };
                if let Ok(data) = frame.into_data() {
                    if transport_download.write_all(&data).await.is_err() {
                        break;
                    }
                }
            }
            let _ = transport_download.shutdown().await;
        });

        let request = ripdpi_vless::wire::encode_request(mode.uuid(), ripdpi_vless::addons::VISION_ADDONS, target);
        user_upload.write_all(&request).await?;

        let mut stream = XhttpStream { reader: user_download, writer: user_upload, _permit: permit };
        ripdpi_vless::wire::read_response(&mut stream).await?;
        Ok(stream)
    }
}

pub(crate) fn random_padding_value() -> String {
    let padding_len = rand::rng().random_range(HEADER_PADDING_MIN..=HEADER_PADDING_MAX);
    "X".repeat(padding_len)
}

pub(crate) fn stream_up_path(path: &str, session_id: &str) -> String {
    let normalized = normalize_path(path);
    if normalized == "/" {
        format!("/{session_id}")
    } else {
        format!("{normalized}/{session_id}")
    }
}

pub(crate) fn referer_padding(host: &str, path: &str) -> String {
    let normalized = normalize_path(path);
    let padding = random_padding_value();
    format!("https://{host}{normalized}?x_padding={padding}")
}

fn random_session_id() -> String {
    let mut rng = rand::rng();
    format!("{:016x}{:016x}", rng.random::<u64>(), rng.random::<u64>())
}
