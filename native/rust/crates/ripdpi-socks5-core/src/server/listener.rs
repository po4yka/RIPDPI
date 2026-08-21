/// Wrapper of TcpListener
/// Useful if you don't use any existing TcpListener's streams.
pub struct Socks5Server<A: Authentication = DenyAuthentication> {
    listener: TcpListener,
    config: Arc<Config<A>>,
}

#[allow(deprecated)]
impl<A: Authentication + Default> Socks5Server<A> {
    pub async fn bind<S: AsyncToSocketAddrs>(addr: S) -> io::Result<Self> {
        let listener = TcpListener::bind(&addr).await?;
        let config = Arc::new(Config::default());

        Ok(Socks5Server { listener, config })
    }
}

#[allow(deprecated)]
impl<A: Authentication> Socks5Server<A> {
    /// Set a custom config
    pub fn with_config<T: Authentication>(self, config: Config<T>) -> Socks5Server<T> {
        Socks5Server { listener: self.listener, config: Arc::new(config) }
    }

    /// Can loop on `incoming().next()` to iterate over incoming connections.
    pub fn incoming(&self) -> Incoming<'_, A> {
        Incoming(self, None)
    }
}

/// `Incoming` implements [`futures_core::stream::Stream`].
///
/// [`futures_core::stream::Stream`]: https://docs.rs/futures/0.3.30/futures/stream/trait.Stream.html
#[allow(deprecated)]
pub struct Incoming<'a, A: Authentication>(
    &'a Socks5Server<A>,
    Option<Pin<Box<dyn Future<Output = io::Result<(TcpStream, SocketAddr)>> + Send + Sync + 'a>>>,
);

/// Iterator for each incoming stream connection
/// this wrapper will convert async_std TcpStream into Socks5Socket.
#[allow(deprecated)]
impl<'a, A: Authentication> Stream for Incoming<'a, A> {
    type Item = Result<Socks5Socket<TcpStream, A>, SocksError>;

    /// this code is mainly borrowed from [`Incoming::poll_next()` of `TcpListener`][tcpListenerLink]
    ///
    /// [tcpListenerLink]: https://docs.rs/async-std/1.8.0/async_std/net/struct.TcpListener.html#method.incoming
    fn poll_next(mut self: Pin<&mut Self>, cx: &mut AsyncContext<'_>) -> Poll<Option<Self::Item>> {
        loop {
            if self.1.is_none() {
                self.1 = Some(Box::pin(self.0.listener.accept()));
            }

            if let Some(f) = &mut self.1 {
                // early returns if pending
                let (socket, _) = ready!(f.as_mut().poll(cx))?;
                self.1 = None;

                debug!("SOCKS incoming connection accepted");

                // Wrap the TcpStream into Socks5Socket
                let socket = Socks5Socket::new(socket, self.0.config.clone());

                return Poll::Ready(Some(Ok(socket)));
            }
        }
    }
}
