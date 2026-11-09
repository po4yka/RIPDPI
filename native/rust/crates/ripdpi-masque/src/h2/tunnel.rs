use std::io;
use std::pin::Pin;
use std::task::{Context, Poll, ready};
use std::time::Duration;

use bytes::{Buf, Bytes};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::oneshot;

use crate::client::AsyncIo;

// One CONNECT owns one carrier. Allow a bounded reset flush before driver shutdown.
struct Driver(Option<oneshot::Sender<()>>);

impl Drop for Driver {
    fn drop(&mut self) {
        if let Some(stop) = self.0.take() {
            let _ = stop.send(());
        }
    }
}

pub(super) struct H2Tunnel {
    send: h2::SendStream<Bytes>,
    recv: h2::RecvStream,
    buffered: Bytes,
    send_closed: bool,
    _driver: Driver,
}

/// # Cancel safety:
/// cancel-safe: dropping the unpublished stream and driver closes the carrier.
pub(super) async fn open<S: AsyncIo + 'static>(
    transport: S,
    request: http::Request<http_body_util::Empty<Bytes>>,
) -> io::Result<(http::Response<()>, H2Tunnel)> {
    let (sender, connection) = h2::client::Builder::new()
        .initial_window_size(2 * 1024 * 1024)
        .initial_connection_window_size(5 * 1024 * 1024)
        .max_header_list_size(16 * 1024)
        .initial_max_send_streams(0)
        .enable_push(false)
        .handshake(transport)
        .await
        .map_err(h2_error)?;
    let (stop, stopped) = oneshot::channel();
    let driver = Driver(Some(stop));
    tokio::spawn(async move {
        tokio::pin!(connection);
        tokio::select! {
            result = &mut connection => {
                if let Err(error) = result { tracing::debug!(%error, "MASQUE H2 driver closed"); }
            }
            _ = stopped => {
                // Stream references drop immediately after this signal.
                let _ = tokio::time::timeout(Duration::from_millis(100), &mut connection).await;
            }
        }
    });
    let mut sender = sender.ready().await.map_err(h2_error)?;
    let (response, send) = sender.send_request(request.map(|_| ()), false).map_err(h2_error)?;
    let response = response.await.map_err(h2_error)?;
    let (parts, recv) = response.into_parts();
    Ok((
        http::Response::from_parts(parts, ()),
        H2Tunnel { send, recv, buffered: Bytes::new(), send_closed: false, _driver: driver },
    ))
}

impl AsyncRead for H2Tunnel {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, output: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if output.remaining() == 0 {
            return Poll::Ready(Ok(()));
        }
        while this.buffered.is_empty() {
            match ready!(this.recv.poll_data(cx)) {
                Some(Ok(bytes)) => this.buffered = bytes,
                Some(Err(error)) => return Poll::Ready(Err(h2_error(error))),
                None => return Poll::Ready(Ok(())),
            }
        }
        let count = output.remaining().min(this.buffered.len());
        output.put_slice(&this.buffered[..count]);
        this.buffered.advance(count);
        this.recv.flow_control().release_capacity(count).map_err(h2_error)?;
        Poll::Ready(Ok(()))
    }
}

impl AsyncWrite for H2Tunnel {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, input: &[u8]) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if input.is_empty() {
            return Poll::Ready(Ok(0));
        }
        if this.send_closed {
            return Poll::Ready(Err(io::ErrorKind::BrokenPipe.into()));
        }
        if let Poll::Ready(reset) = this.send.poll_reset(cx) {
            let error = match reset {
                Ok(reason) => io::Error::new(io::ErrorKind::ConnectionReset, format!("H2 tunnel reset: {reason}")),
                Err(error) => h2_error(error),
            };
            return Poll::Ready(Err(error));
        }
        this.send.reserve_capacity(input.len().min(16 * 1024));
        let capacity = match ready!(this.send.poll_capacity(cx)) {
            Some(Ok(capacity)) => capacity,
            Some(Err(error)) => return Poll::Ready(Err(h2_error(error))),
            None => return Poll::Ready(Err(io::ErrorKind::BrokenPipe.into())),
        };
        if capacity == 0 {
            return Poll::Pending;
        }
        let count = capacity.min(input.len()).min(16 * 1024);
        this.send.send_data(Bytes::copy_from_slice(&input[..count]), false).map_err(h2_error)?;
        Poll::Ready(Ok(count))
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if !this.send_closed {
            this.send.send_data(Bytes::new(), true).map_err(h2_error)?;
            this.send_closed = true;
        }
        Poll::Ready(Ok(()))
    }
}

impl Drop for H2Tunnel {
    fn drop(&mut self) {
        if !self.send_closed || !self.recv.is_end_stream() {
            self.send.send_reset(h2::Reason::CANCEL);
        }
    }
}

fn h2_error(error: h2::Error) -> io::Error {
    io::Error::new(io::ErrorKind::ConnectionReset, error)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::future::poll_fn;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    /// # Cancel safety:
    /// NOT cancel-safe: the fixture owns the connection until the client has read its echo.
    #[tokio::test]
    async fn tunnel_releases_windows_and_preserves_half_close() {
        let (client_io, server_io) = tokio::io::duplex(4096);
        let (done, finished) = oneshot::channel();
        let server = tokio::spawn(async move {
            let mut connection = h2::server::handshake(server_io).await.unwrap();
            let (request, mut response) = connection.accept().await.unwrap().unwrap();
            let mut send =
                response.send_response(http::Response::builder().status(201).body(()).unwrap(), false).unwrap();
            let work = async move {
                let mut recv = request.into_body();
                while let Some(data) = recv.data().await {
                    let mut data = data.unwrap();
                    recv.flow_control().release_capacity(data.len()).unwrap();
                    while !data.is_empty() {
                        send.reserve_capacity(data.len());
                        let capacity = poll_fn(|cx| send.poll_capacity(cx)).await.unwrap().unwrap();
                        let count = capacity.min(data.len());
                        if count != 0 {
                            send.send_data(data.split_to(count), false).unwrap();
                        }
                    }
                }
                send.send_data(Bytes::new(), true).unwrap();
                let _ = finished.await;
            };
            tokio::pin!(work);
            tokio::select! {
                () = &mut work => {},
                _ = connection.accept() => panic!("carrier ended before the echo"),
            }
        });
        let request = http::Request::builder()
            .method("CONNECT")
            .uri("example.test:443")
            .body(http_body_util::Empty::new())
            .unwrap();
        let (_, mut tunnel) = open(client_io, request).await.unwrap();
        let expected = (0..3 * 1024 * 1024).map(|i| (i % 251) as u8).collect::<Vec<_>>();
        let (mut read, mut write) = tokio::io::split(&mut tunnel);
        let write_all = async {
            write.write_all(&expected).await.unwrap();
            write.shutdown().await.unwrap();
        };
        let read_all = async {
            let mut actual = Vec::new();
            read.read_to_end(&mut actual).await.unwrap();
            actual
        };
        let (_, actual) =
            tokio::time::timeout(Duration::from_secs(5), async { tokio::join!(write_all, read_all) }).await.unwrap();
        assert_eq!(actual, expected);
        let _ = done.send(());
        server.await.unwrap();
    }

    /// # Cancel safety:
    /// NOT cancel-safe: both bounded fixture tasks must be joined.
    #[tokio::test]
    async fn reset_unblocks_a_flow_control_blocked_write() {
        let (client_io, server_io) = tokio::io::duplex(4096);
        let (reset, do_reset) = oneshot::channel();
        let server = tokio::spawn(async move {
            let mut connection =
                h2::server::Builder::new().initial_window_size(0).handshake::<_, Bytes>(server_io).await.unwrap();
            let (_request, mut response) = connection.accept().await.unwrap().unwrap();
            let mut send =
                response.send_response(http::Response::builder().status(201).body(()).unwrap(), false).unwrap();
            let work = async move {
                do_reset.await.unwrap();
                send.send_reset(h2::Reason::CANCEL);
            };
            tokio::pin!(work);
            tokio::select! { () = &mut work => {}, _ = connection.accept() => panic!("early close"), }
            let _ = tokio::time::timeout(Duration::from_secs(1), connection.accept()).await;
        });
        let request = http::Request::builder()
            .method("CONNECT")
            .uri("example.test:443")
            .body(http_body_util::Empty::new())
            .unwrap();
        let (_, mut tunnel) = open(client_io, request).await.unwrap();
        let pending = tokio::time::timeout(Duration::from_millis(20), tunnel.write_all(b"blocked")).await;
        assert!(pending.is_err(), "zero peer window must block the write");
        reset.send(()).unwrap();
        let error =
            tokio::time::timeout(Duration::from_secs(2), tunnel.write_all(b"blocked")).await.unwrap().unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::ConnectionReset);
        drop(tunnel);
        server.await.unwrap();
    }

    /// # Cancel safety:
    /// NOT cancel-safe: the peer owns the bounded observation until its EOF.
    #[tokio::test]
    async fn cancelled_open_stops_its_carrier_driver() {
        let (client_io, server_io) = tokio::io::duplex(4096);
        let (opened, got_request) = oneshot::channel();
        let peer = tokio::spawn(async move {
            let mut connection = h2::server::handshake(server_io).await.unwrap();
            let (_request, _response) = connection.accept().await.unwrap().unwrap();
            opened.send(()).unwrap();
            tokio::time::timeout(Duration::from_secs(1), async {
                while let Some(request) = connection.accept().await {
                    if request.is_err() {
                        break;
                    }
                }
            })
            .await
            .expect("cancelled carrier closes within its bounded driver cleanup");
        });
        let request = http::Request::builder()
            .method("CONNECT")
            .uri("example.test:443")
            .body(http_body_util::Empty::new())
            .unwrap();
        let pending = tokio::spawn(open(client_io, request));
        got_request.await.unwrap();
        pending.abort();
        assert!(matches!(pending.await, Err(error) if error.is_cancelled()));
        peer.await.unwrap();
    }
}
