use std::future::Future;
use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::task::{Context, Poll};
use std::time::Duration;

use russh::client;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::task::JoinHandle;
use tokio::time::{Instant, Sleep};
use tokio_util::sync::{CancellationToken, WaitForCancellationFutureOwned};

use super::{SshChannelStream, SshHandler, parse_target};
use crate::{Result, SshConfig, SshError};

type Handle = client::Handle<SshHandler>;

enum State {
    Pending(JoinHandle<Result<Handle>>),
    Ready(Handle),
    Failed { reason: Option<SshError>, fatal_cleanup: bool },
    Closed,
}

/// Owns construction before any caller can await it. `ready` cancellation never
/// drops russh's KEX future. Call `close` to join before reporting stopped; Drop
/// only signals cancellation and does not prove completion.
pub struct SshClient {
    control: Arc<ConnectionControl>,
    state: Mutex<State>,
}

impl std::fmt::Debug for SshClient {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.debug_struct("SshClient").finish_non_exhaustive()
    }
}

impl SshClient {
    pub(super) fn start(config: SshConfig, protection: ripdpi_native_protect::SocketProtectionPolicy) -> Result<Self> {
        config.validate()?;
        let runtime = tokio::runtime::Handle::try_current()
            .map_err(|_| SshError::Ssh("SSH connection requires a Tokio runtime".into()))?;
        let control = Arc::new(ConnectionControl::new());
        let worker_control = Arc::clone(&control);
        let worker = runtime.spawn(async move { super::establish(config, protection, worker_control).await });
        Ok(Self { control, state: Mutex::new(State::Pending(worker)) })
    }

    /// # Cancel safety
    /// Cancel-safe: the construction handle stays in the owned state while this
    /// waiter is cancelled. The owner must subsequently call `close`.
    pub async fn ready(&self) -> Result<()> {
        let mut state = self.state.lock().await;
        resolve_pending(&mut state).await;
        match &mut *state {
            State::Ready(_) if !self.control.cancel.is_cancelled() => Ok(()),
            State::Failed { reason, .. } => {
                Err(reason.take().unwrap_or_else(|| SshError::Ssh("SSH connection previously failed".into())))
            }
            _ => Err(SshError::Ssh("SSH connection closed".into())),
        }
    }

    /// Signal cancellation of either construction or the established transport.
    /// The transport converts cancellation to I/O failure, allowing russh's
    /// normal KEX failure path to join its own worker.
    pub fn cancel(&self) {
        self.control.cancel();
    }

    /// # Cancel safety
    /// Cancel-safe: pending construction and live russh handles remain in state
    /// across every await. A later close resumes joining; observed join failure
    /// remains failed even after repeated close attempts.
    pub async fn close(&self) -> Result<()> {
        self.cancel();
        let mut state = self.state.lock().await;
        resolve_pending(&mut state).await;
        match &mut *state {
            State::Ready(handle) => {
                if matches!(handle.await, Err(russh::Error::Join(_))) {
                    *state = State::Failed { reason: None, fatal_cleanup: true };
                    return Err(SshError::CleanupFailed);
                }
            }
            State::Failed { fatal_cleanup: true, .. } => return Err(SshError::CleanupFailed),
            State::Pending(_) => unreachable!("pending construction resolved"),
            State::Failed { .. } | State::Closed => {}
        }
        *state = State::Closed;
        Ok(())
    }

    /// # Cancel safety
    /// A cancelled channel open leaves the parent session owned. Parent close
    /// cancels transport I/O and joins before it reports completion.
    pub async fn tcp_connect(&self, target: &str) -> Result<SshChannelStream> {
        let (host, port) = parse_target(target)?;
        self.ready().await?;
        let state = self.state.lock().await;
        let State::Ready(handle) = &*state else {
            return Err(SshError::Ssh("SSH connection closed".into()));
        };
        let channel = handle
            .channel_open_direct_tcpip(host, u32::from(port), "127.0.0.1", 0)
            .await
            .map_err(|error| SshError::Ssh(error.to_string()))?;
        Ok(channel.into_stream())
    }
}

impl Drop for SshClient {
    fn drop(&mut self) {
        self.cancel();
    }
}

/// # Cancel safety
/// The borrowed JoinHandle remains inside State until its result is observed.
async fn resolve_pending(state: &mut State) {
    if let State::Pending(worker) = state {
        let next = match worker.await {
            Ok(Ok(handle)) => State::Ready(handle),
            Ok(Err(reason)) => {
                let fatal_cleanup = matches!(reason, SshError::CleanupFailed);
                State::Failed { reason: Some(reason), fatal_cleanup }
            }
            Err(_) => State::Failed { reason: Some(SshError::CleanupFailed), fatal_cleanup: true },
        };
        *state = next;
    }
}

pub(super) struct ConnectionControl {
    pub(super) cancel: CancellationToken,
    pub(super) deadline: Instant,
    handshaking: AtomicBool,
}

impl ConnectionControl {
    fn new() -> Self {
        Self {
            cancel: CancellationToken::new(),
            deadline: Instant::now() + Duration::from_secs(10),
            handshaking: AtomicBool::new(true),
        }
    }
    pub(super) fn cancel(&self) {
        self.cancel.cancel();
    }
    pub(super) fn authenticated(&self) {
        self.handshaking.store(false, Ordering::Release);
    }
    pub(super) fn wrap(self: &Arc<Self>, stream: TcpStream) -> CancellableIo {
        CancellableIo {
            stream,
            control: Arc::clone(self),
            cancelled: Box::pin(self.cancel.clone().cancelled_owned()),
            deadline: Box::pin(tokio::time::sleep_until(self.deadline)),
        }
    }
}

pub(super) struct CancellableIo {
    stream: TcpStream,
    control: Arc<ConnectionControl>,
    cancelled: Pin<Box<WaitForCancellationFutureOwned>>,
    deadline: Pin<Box<Sleep>>,
}

impl CancellableIo {
    fn check(&mut self, cx: &mut Context<'_>) -> io::Result<()> {
        if self.cancelled.as_mut().poll(cx).is_ready() {
            return Err(io::Error::new(io::ErrorKind::ConnectionAborted, "SSH connection cancelled"));
        }
        if self.control.handshaking.load(Ordering::Acquire) && self.deadline.as_mut().poll(cx).is_ready() {
            self.control.cancel();
            return Err(io::Error::new(io::ErrorKind::TimedOut, "SSH connection handshake timed out"));
        }
        Ok(())
    }
}
impl AsyncRead for CancellableIo {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        if let Err(error) = self.check(cx) {
            return Poll::Ready(Err(error));
        }
        Pin::new(&mut self.stream).poll_read(cx, buf)
    }
}
impl AsyncWrite for CancellableIo {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        if let Err(error) = self.check(cx) {
            return Poll::Ready(Err(error));
        }
        Pin::new(&mut self.stream).poll_write(cx, buf)
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        if let Err(error) = self.check(cx) {
            return Poll::Ready(Err(error));
        }
        Pin::new(&mut self.stream).poll_flush(cx)
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        if let Err(error) = self.check(cx) {
            return Poll::Ready(Err(error));
        }
        Pin::new(&mut self.stream).poll_shutdown(cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn cancelled_close_retains_pending_worker_for_retry() {
        let (release, released) = tokio::sync::oneshot::channel();
        let worker = tokio::spawn(async move {
            released.await.expect("release construction");
            Err(SshError::AuthFailed)
        });
        let client =
            SshClient { control: Arc::new(ConnectionControl::new()), state: Mutex::new(State::Pending(worker)) };
        let mut close = Box::pin(client.close());
        std::future::poll_fn(|cx| {
            assert!(close.as_mut().poll(cx).is_pending());
            Poll::Ready(())
        })
        .await;
        drop(close);
        assert!(matches!(*client.state.lock().await, State::Pending(_)));
        release.send(()).expect("release worker");
        client.close().await.expect("join retained worker");
        assert!(matches!(*client.state.lock().await, State::Closed));
    }

    #[tokio::test]
    async fn observed_construction_panic_remains_failed_on_repeated_close() {
        let worker = tokio::spawn(async {
            panic!("test construction panic");
        });
        let client =
            SshClient { control: Arc::new(ConnectionControl::new()), state: Mutex::new(State::Pending(worker)) };
        assert!(matches!(client.close().await, Err(SshError::CleanupFailed)));
        assert!(matches!(client.close().await, Err(SshError::CleanupFailed)));
    }
}
