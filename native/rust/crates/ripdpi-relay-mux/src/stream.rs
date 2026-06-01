use std::pin::Pin;
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

use crate::RelaySession;
use crate::lease::LeaseGuard;

pub struct MuxStream<T, S>
where
    S: RelaySession,
{
    inner: T,
    _guard: LeaseGuard<S>,
}

impl<T, S> MuxStream<T, S>
where
    S: RelaySession,
{
    pub(crate) fn new(inner: T, guard: LeaseGuard<S>) -> Self {
        Self { inner, _guard: guard }
    }
}

pub struct MuxLease<T, S>
where
    S: RelaySession,
{
    inner: T,
    _guard: LeaseGuard<S>,
}

impl<T, S> MuxLease<T, S>
where
    S: RelaySession,
{
    pub(crate) fn new(inner: T, guard: LeaseGuard<S>) -> Self {
        Self { inner, _guard: guard }
    }

    pub fn get_ref(&self) -> &T {
        &self.inner
    }

    pub fn get_mut(&mut self) -> &mut T {
        &mut self.inner
    }
}

impl<T, S> AsyncRead for MuxStream<T, S>
where
    T: AsyncRead + Unpin,
    S: RelaySession,
{
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_read(cx, buf)
    }
}

impl<T, S> AsyncWrite for MuxStream<T, S>
where
    T: AsyncWrite + Unpin,
    S: RelaySession,
{
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<std::io::Result<usize>> {
        Pin::new(&mut self.inner).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}
