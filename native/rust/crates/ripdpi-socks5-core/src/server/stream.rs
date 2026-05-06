impl<T, A: Authentication> Unpin for Socks5Socket<T, A> where T: AsyncRead + AsyncWrite + Unpin {}

/// Allow us to read directly from the struct
#[allow(deprecated)]
impl<T, A: Authentication> AsyncRead for Socks5Socket<T, A>
where
    T: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_read(
        mut self: Pin<&mut Self>,
        context: &mut std::task::Context,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_read(context, buf)
    }
}

/// Allow us to write directly into the struct
#[allow(deprecated)]
impl<T, A: Authentication> AsyncWrite for Socks5Socket<T, A>
where
    T: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_write(mut self: Pin<&mut Self>, context: &mut std::task::Context, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.inner).poll_write(context, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, context: &mut std::task::Context) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(context)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, context: &mut std::task::Context) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(context)
    }
}

