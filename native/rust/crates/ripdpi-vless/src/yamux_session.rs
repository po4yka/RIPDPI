//! Async SagerNet sing-mux carrier using its yamux inner protocol.

use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use futures::future::poll_fn;
use futures::io::AsyncWriteExt as FuturesAsyncWriteExt;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio_util::compat::{FuturesAsyncReadCompatExt, TokioAsyncReadCompatExt};

use crate::AsyncIo;

type Carrier = tokio_util::compat::Compat<Box<dyn AsyncIo>>;
type Connection = yamux::Connection<Carrier>;

struct AbortOnDrop(tokio::task::JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

/// Shared client-side mux carrier. The yamux implementation owns flow control,
/// stream lifecycle and the carrier I/O state; this wrapper serializes session
/// operations and fails all subsequent opens after carrier failure.
#[derive(Clone)]
pub struct VlessYamuxSession {
    connection: Arc<Mutex<Connection>>,
    closed: Arc<AtomicBool>,
    _driver_guard: Arc<AbortOnDrop>,
}

impl VlessYamuxSession {
    pub(crate) async fn establish(carrier: Box<dyn AsyncIo>, max_streams: usize) -> io::Result<Self> {
        let mut config = yamux::Config::default();
        config.set_max_num_streams(max_streams);
        let mut carrier = carrier.compat();
        carrier.write_all(&crate::mux::encode_session_request()).await?;
        carrier.flush().await?;
        let connection = Arc::new(Mutex::new(yamux::Connection::new(carrier, config, yamux::Mode::Client)));
        let closed = Arc::new(AtomicBool::new(false));
        let driver_connection = Arc::clone(&connection);
        let driver_closed = Arc::clone(&closed);
        let driver = tokio::spawn(async move {
            loop {
                let next = poll_fn(|cx| {
                    driver_connection.lock().unwrap_or_else(std::sync::PoisonError::into_inner).poll_next_inbound(cx)
                })
                .await;
                match next {
                    Some(Ok(_)) => {
                        // Client VLESS mux sessions do not accept peer-opened
                        // streams. Dropping the unexpected stream sends a
                        // reset through yamux rather than exposing it.
                    }
                    Some(Err(_)) | None => {
                        driver_closed.store(true, Ordering::Release);
                        break;
                    }
                }
            }
        });
        Ok(Self { connection, closed, _driver_guard: Arc::new(AbortOnDrop(driver)) })
    }

    /// Opens a TCP stream and completes the sing-mux stream request before
    /// exposing application bytes to the caller.
    ///
    /// # Cancel safety
    ///
    /// conditionally cancel-safe: dropping during the stream request discards
    /// only the newly opened yamux stream; its Drop resets that substream while
    /// the shared carrier remains owned by the driver.
    pub async fn open_stream(&self, destination: &str) -> io::Result<tokio_util::compat::Compat<yamux::Stream>> {
        if self.closed.load(Ordering::Acquire) {
            return Err(io::Error::new(io::ErrorKind::ConnectionAborted, "VLESS mux carrier closed"));
        }
        let stream = poll_fn(|cx| {
            self.connection.lock().unwrap_or_else(std::sync::PoisonError::into_inner).poll_new_outbound(cx)
        })
        .await
        .map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionAborted, format!("VLESS mux carrier closed: {error}"))
        })?;
        let mut stream = stream.compat();
        stream.write_all(&crate::mux::encode_tcp_stream_request(destination)?).await?;
        stream.flush().await?;
        crate::mux::read_stream_response(&mut stream).await?;
        Ok(stream)
    }
}

/// Compile-time contract for the stream handed to relay-core.
fn _assert_tokio_stream<T: AsyncRead + AsyncWrite + Unpin + Send>() {}

#[allow(dead_code)]
fn assert_stream_contract() {
    _assert_tokio_stream::<tokio_util::compat::Compat<yamux::Stream>>();
}

#[cfg(test)]
mod tests {
    use std::pin::Pin;
    use std::task::{Context, Poll};
    use std::time::Duration;

    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::sync::oneshot;
    use tokio_util::compat::TokioAsyncReadCompatExt;

    struct DropTrackedIo {
        inner: tokio::io::DuplexStream,
        dropped: Option<oneshot::Sender<()>>,
    }

    impl Drop for DropTrackedIo {
        fn drop(&mut self) {
            if let Some(sender) = self.dropped.take() {
                let _ = sender.send(());
            }
        }
    }

    impl AsyncRead for DropTrackedIo {
        fn poll_read(
            mut self: Pin<&mut Self>,
            context: &mut Context<'_>,
            buffer: &mut tokio::io::ReadBuf<'_>,
        ) -> Poll<io::Result<()>> {
            Pin::new(&mut self.inner).poll_read(context, buffer)
        }
    }

    impl AsyncWrite for DropTrackedIo {
        fn poll_write(mut self: Pin<&mut Self>, context: &mut Context<'_>, buffer: &[u8]) -> Poll<io::Result<usize>> {
            Pin::new(&mut self.inner).poll_write(context, buffer)
        }

        fn poll_flush(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<io::Result<()>> {
            Pin::new(&mut self.inner).poll_flush(context)
        }

        fn poll_shutdown(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<io::Result<()>> {
            Pin::new(&mut self.inner).poll_shutdown(context)
        }
    }

    #[tokio::test]
    async fn dropping_last_session_aborts_driver_and_releases_carrier() {
        let (client, _server) = tokio::io::duplex(1024);
        let (dropped_tx, dropped_rx) = oneshot::channel();
        let session =
            VlessYamuxSession::establish(Box::new(DropTrackedIo { inner: client, dropped: Some(dropped_tx) }), 1)
                .await
                .expect("establish yamux carrier");

        drop(session);

        tokio::time::timeout(Duration::from_secs(1), dropped_rx)
            .await
            .expect("driver abort must release carrier")
            .expect("carrier drop notification");
    }

    #[tokio::test]
    async fn three_streams_interleave_over_one_sing_mux_yamux_carrier() {
        let (client, mut server) = tokio::io::duplex(64 * 1024);
        let server_task = tokio::spawn(async move {
            let mut preamble = [0u8; 2];
            server.read_exact(&mut preamble).await.unwrap();
            assert_eq!(preamble, crate::mux::encode_session_request());

            let connection = Arc::new(Mutex::new(yamux::Connection::new(
                server.compat(),
                yamux::Config::default(),
                yamux::Mode::Server,
            )));
            let mut workers = Vec::new();
            for _ in 0..3 {
                let stream = poll_fn(|cx| {
                    connection.lock().unwrap_or_else(std::sync::PoisonError::into_inner).poll_next_inbound(cx)
                })
                .await
                .expect("carrier stays open")
                .expect("valid inbound yamux stream");
                workers.push(tokio::spawn(async move {
                    let mut stream = stream.compat();
                    let mut flags = [0u8; 2];
                    stream.read_exact(&mut flags).await.unwrap();
                    assert_eq!(flags, [0, 0]);
                    let family = stream.read_u8().await.unwrap();
                    assert_eq!(family, 3);
                    let host_len = usize::from(stream.read_u8().await.unwrap());
                    let mut host = vec![0u8; host_len];
                    stream.read_exact(&mut host).await.unwrap();
                    assert_eq!(host, b"interleave.test");
                    assert_eq!(stream.read_u16().await.unwrap(), 443);
                    stream.write_all(&[0]).await.unwrap();
                    let mut payload = [0u8; 3];
                    stream.read_exact(&mut payload).await.unwrap();
                    stream.write_all(&payload).await.unwrap();
                    stream.flush().await.unwrap();
                }));
            }
            let driver_connection = Arc::clone(&connection);
            let driver = tokio::spawn(async move {
                loop {
                    let _ = poll_fn(|cx| {
                        driver_connection
                            .lock()
                            .unwrap_or_else(std::sync::PoisonError::into_inner)
                            .poll_next_inbound(cx)
                    })
                    .await;
                }
            });
            for worker in workers {
                worker.await.unwrap();
            }
            driver.abort();
        });

        let client = VlessYamuxSession::establish(Box::new(client), 3).await.unwrap();
        let (first, second, third) = tokio::join!(
            client.open_stream("interleave.test:443"),
            client.open_stream("interleave.test:443"),
            client.open_stream("interleave.test:443"),
        );
        let mut first = first.unwrap();
        let mut second = second.unwrap();
        let mut third = third.unwrap();
        let (first_write, second_write, third_write) =
            tokio::join!(first.write_all(b"one"), second.write_all(b"two"), third.write_all(b"tri"));
        first_write.unwrap();
        second_write.unwrap();
        third_write.unwrap();
        let mut first_reply = [0u8; 3];
        let mut second_reply = [0u8; 3];
        let mut third_reply = [0u8; 3];
        let (first_read, second_read, third_read) = tokio::join!(
            first.read_exact(&mut first_reply),
            second.read_exact(&mut second_reply),
            third.read_exact(&mut third_reply),
        );
        first_read.unwrap();
        second_read.unwrap();
        third_read.unwrap();
        assert_eq!(first_reply, *b"one");
        assert_eq!(second_reply, *b"two");
        assert_eq!(third_reply, *b"tri");
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn cancelled_stream_request_resets_only_substream_and_carrier_remains_reusable() {
        let (client, mut server) = tokio::io::duplex(64 * 1024);
        let (first_request_tx, first_request_rx) = oneshot::channel();
        let (cancelled_tx, cancelled_rx) = oneshot::channel();
        let (second_open_tx, second_open_rx) = oneshot::channel();
        let server_task = tokio::spawn(async move {
            let mut preamble = [0_u8; 2];
            server.read_exact(&mut preamble).await.expect("read sing-mux session preamble");
            let connection = Arc::new(Mutex::new(yamux::Connection::new(
                server.compat(),
                yamux::Config::default(),
                yamux::Mode::Server,
            )));
            let first = poll_fn(|cx| {
                connection.lock().unwrap_or_else(std::sync::PoisonError::into_inner).poll_next_inbound(cx)
            })
            .await
            .expect("first inbound stream")
            .expect("valid first inbound stream");
            let mut first = first.compat();
            read_stream_request(&mut first).await;
            first_request_tx.send(()).expect("signal first request");
            cancelled_rx.await.expect("client cancellation signal");
            drop(first);

            let second = poll_fn(|cx| {
                connection.lock().unwrap_or_else(std::sync::PoisonError::into_inner).poll_next_inbound(cx)
            })
            .await
            .expect("second inbound stream")
            .expect("valid second inbound stream");
            let mut second = second.compat();
            read_stream_request(&mut second).await;
            let driver_connection = Arc::clone(&connection);
            let driver = tokio::spawn(async move {
                loop {
                    let _ = poll_fn(|cx| {
                        driver_connection
                            .lock()
                            .unwrap_or_else(std::sync::PoisonError::into_inner)
                            .poll_next_inbound(cx)
                    })
                    .await;
                }
            });
            second.write_all(&[0]).await.expect("acknowledge second stream");
            second.flush().await.expect("flush second stream response");
            second_open_rx.await.expect("second stream opened by client");
            driver.abort();
        });

        let session = VlessYamuxSession::establish(Box::new(client), 2).await.expect("establish mux carrier");
        let cancelled_session = session.clone();
        let first_open = tokio::spawn(async move { cancelled_session.open_stream("cancelled.test:443").await });
        first_request_rx.await.expect("first stream request observed");
        first_open.abort();
        first_open.await.expect_err("first open must be cancelled");
        cancelled_tx.send(()).expect("release server after cancellation");

        let _second = session.open_stream("reused.test:443").await.expect("open later stream on the same carrier");
        second_open_tx.send(()).expect("release server after second open");
        server_task.await.expect("server task");
    }

    async fn read_stream_request<S>(stream: &mut S)
    where
        S: AsyncRead + Unpin,
    {
        let mut flags = [0_u8; 2];
        stream.read_exact(&mut flags).await.expect("read mux flags");
        assert_eq!(flags, [0, 0]);
        assert_eq!(stream.read_u8().await.expect("read address family"), 3);
        let host_len = usize::from(stream.read_u8().await.expect("read host length"));
        let mut host = vec![0_u8; host_len];
        stream.read_exact(&mut host).await.expect("read host");
        assert_eq!(stream.read_u16().await.expect("read port"), 443);
    }
}
