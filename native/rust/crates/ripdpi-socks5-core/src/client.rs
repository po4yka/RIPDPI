pub mod outbound;

use crate::read_exact;
use crate::util::stream::{tcp_connect, tcp_connect_with_timeout};
use crate::util::target_addr::{TargetAddr, ToTargetAddr, read_address};
use crate::{
    AuthenticationMethod, ReplyError, Result, Socks5Command, SocksError, consts, new_udp_header, parse_udp_request,
};
use anyhow::Context;
use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::task::Poll;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpStream, ToSocketAddrs, UdpSocket, lookup_host};

const MAX_ADDR_LEN: usize = 260;

#[derive(Debug)]
pub struct Config {
    /// Timeout of the socket connect
    connect_timeout: Option<Duration>,
    /// Avoid useless roundtrips if we don't need the Authentication layer
    /// make sure to also activate it on the server side.
    skip_auth: bool,
}

impl Default for Config {
    fn default() -> Self {
        Config { connect_timeout: None, skip_auth: false }
    }
}

impl Config {
    /// How much time it should wait until the socket connect times out.
    pub fn set_connect_timeout(&mut self, d: Duration) -> &mut Self {
        self.connect_timeout = Some(d);
        self
    }

    pub fn set_skip_auth(&mut self, value: bool) -> &mut Self {
        self.skip_auth = value;
        self
    }
}

/// A SOCKS5 client.
/// `Socks5Stream` implements [`AsyncRead`] and [`AsyncWrite`].
#[derive(Debug)]
pub struct Socks5Stream<S: AsyncRead + AsyncWrite + Unpin> {
    socket: S,
    target_addr: Option<TargetAddr>,
    config: Config,
}

impl<S> Socks5Stream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    /// Possibility to use a stream already created rather than
    /// creating a whole new `TcpStream::connect()`.
    pub async fn use_stream(socket: S, auth: Option<AuthenticationMethod>, config: Config) -> Result<Self> {
        let mut stream = Socks5Stream { socket, config, target_addr: None };

        // Auth none is always used by default.
        let mut methods = vec![AuthenticationMethod::None];

        if let Some(method) = auth {
            // add any other method if supplied
            methods.push(method);
        }

        // Handshake Lifecycle
        if !stream.config.skip_auth {
            let methods = stream.send_version_and_methods(methods).await?;
            stream.which_method_accepted(methods).await?;
        } else {
            debug!("skipping auth");
        }

        Ok(stream)
    }

    pub async fn request(&mut self, cmd: Socks5Command, target_addr: TargetAddr) -> Result<TargetAddr> {
        self.target_addr = Some(target_addr);

        // Request Lifecycle
        let target_kind = self.target_addr.as_ref().map_or("unset", TargetAddr::logging_kind);
        debug!("SOCKS request started target_kind={target_kind}");
        self.request_header(cmd).await?;
        let bind_addr = self.read_request_reply().await?;

        Ok(bind_addr)
    }

    /// Decide to whether or not, accept the authentication method
    /// A client send a list of methods that he supports, he could send
    ///
    ///   - 0: Non auth
    ///   - 2: Auth with username/password
    ///
    /// Altogether, then the server choose to use of of these,
    /// or deny the handshake (thus the connection).
    ///
    /// # Examples
    /// ```text
    ///                    {SOCKS Version, methods-length}
    ///     eg. (non-auth) {5, 2}
    ///     eg. (auth)     {5, 3}
    /// ```
    ///
    async fn send_version_and_methods(
        &mut self,
        methods: Vec<AuthenticationMethod>,
    ) -> Result<Vec<AuthenticationMethod>> {
        debug!("Client's version and method len [{}, {}]", consts::SOCKS5_VERSION, methods.len());
        // the first 2 bytes which contains the SOCKS version and the methods len()
        let mut packet = vec![consts::SOCKS5_VERSION, methods.len() as u8];

        let auth = methods.iter().map(|l| l.as_u8()).collect::<Vec<_>>();
        debug!("client auth methods supported: {:?}", auth);
        packet.extend(auth);

        self.socket
            .write_all(&packet)
            .await
            .context("Couldn't write SOCKS version & methods len & supported auth methods")?;

        // Return methods available
        Ok(methods)
    }

    /// Decide to whether or not, accept the authentication method.
    /// Don't forget that the methods list sent by the client, contains one or more methods.
    ///
    /// # Request
    ///
    ///  Client send an array of 3 entries: [0, 1, 2]
    /// ```text
    ///                          {SOCKS Version,  Authentication chosen}
    ///     eg. (non-auth)       {5, 0}
    ///     eg. (GSSAPI)         {5, 1}
    ///     eg. (auth)           {5, 2}
    /// ```
    ///
    /// # Response
    /// ```text
    ///     eg. (accept non-auth) {5, 0x00}
    ///     eg. (non-acceptable)  {5, 0xff}
    /// ```
    ///
    async fn which_method_accepted(&mut self, methods: Vec<AuthenticationMethod>) -> Result<()> {
        let [version, method] = read_exact!(self.socket, [0u8; 2]).context("Can't get chosen auth method")?;
        debug!("Socks version ({version}), method chosen: {method}.", version = version, method = method,);

        if version != consts::SOCKS5_VERSION {
            return Err(SocksError::UnsupportedSocksVersion(version));
        }

        match method {
            consts::SOCKS5_AUTH_METHOD_NONE => debug!("No auth will be used"),
            consts::SOCKS5_AUTH_METHOD_PASSWORD => self.use_password_auth(methods).await?,
            _ => {
                debug!("Don't support this auth method, reply with (0xff)");
                self.socket
                    .write_all(&[consts::SOCKS5_VERSION, consts::SOCKS5_AUTH_METHOD_NOT_ACCEPTABLE])
                    .await
                    .context("Can't write that the methods are unsupported.")?;

                return Err(SocksError::AuthMethodUnacceptable(vec![method]));
            }
        }

        Ok(())
    }

    async fn use_password_auth(&mut self, methods: Vec<AuthenticationMethod>) -> Result<()> {
        debug!("Password will be used");
        let (username, password) = match methods.get(1) {
            Some(AuthenticationMethod::None) => unreachable!(),
            Some(AuthenticationMethod::Password { username, password }) => Ok((username, password)),
            None => Err(SocksError::AuthenticationRejected(format!("Authentication rejected, missing user pass"))),
        }?;

        let user_bytes = username.as_bytes();
        let pass_bytes = password.as_bytes();

        // The username/password sub-negotiation (RFC 1929) encodes each length
        // as a single byte (ULEN/PLEN). A credential longer than 255 bytes
        // would silently truncate the `as u8` cast, desynchronizing the auth
        // frame and producing a spurious "auth rejected" the caller cannot
        // distinguish from a real failure. Reject it explicitly instead — this
        // matches the bounds check already enforced in `client::outbound`.
        if user_bytes.len() > 255 {
            return Err(SocksError::ArgumentInputError("SOCKS5 credentials exceed RFC 1929 length limit"));
        }
        if pass_bytes.len() > 255 {
            return Err(SocksError::ArgumentInputError("SOCKS5 credentials exceed RFC 1929 length limit"));
        }

        let mut packet: Vec<u8> = vec![1, user_bytes.len() as u8];
        packet.extend(user_bytes);
        packet.push(pass_bytes.len() as u8);
        packet.extend(pass_bytes);

        self.socket.write_all(&packet).await.context("Can't send password")?;

        // Check the server reply, if whether it approved the auth or not
        let [version, is_success] = read_exact!(self.socket, [0u8; 2]).context("Can't read is_success")?;
        debug!("Auth: [version: {version}, is_success: {is_success}]", version = version, is_success = is_success,);

        if is_success != consts::SOCKS5_REPLY_SUCCEEDED {
            return Err(SocksError::AuthenticationRejected("SOCKS5 authentication rejected".to_owned()));
        }

        Ok(())
    }

    /// Decide to whether or not, accept the authentication method.
    /// Don't forget that the methods list sent by the client, contains one or more methods.
    ///
    /// # Request
    /// ```test
    ///          +----+-----+-------+------+----------+----------+
    ///          |VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
    ///          +----+-----+-------+------+----------+----------+
    ///          | 1  |  1  |   1   |  1   | Variable |    2     |
    ///          +----+-----+-------+------+----------+----------+
    /// ```
    ///
    /// # Help
    ///
    /// To debug request use a netcat server with hexadecimal output to parse the hidden bytes:
    ///
    /// ```bash
    ///    $ nc -k -l 80 | hexdump -C
    /// ```
    ///
    /// # Cancel safety:
    /// Not cancel-safe: a partial frame can be written. Drop the stream if cancelled.
    // NOT cancel-safe: cancellation can leave a partial request on the stream.
    async fn request_header(&mut self, cmd: Socks5Command) -> Result<()> {
        let mut packet = [0u8; MAX_ADDR_LEN + 3];
        let padding; // maximum len of the headers sent
        // build our request packet with (socks version, Command, reserved)
        packet[..3].copy_from_slice(&[consts::SOCKS5_VERSION, cmd.as_u8(), 0x00]);

        match self.target_addr.as_ref() {
            None => {
                if cmd == Socks5Command::UDPAssociate {
                    debug!("UDPAssociate without target_addr, fallback to zeros.");
                    padding = 10;

                    packet[3] = 0x01;
                    packet[4..8].copy_from_slice(&[0, 0, 0, 0]); // ip
                    packet[8..padding].copy_from_slice(&[0, 0]); // port
                } else {
                    return Err(anyhow::Error::msg("target addr should be present").into());
                }
            }
            Some(target_addr) => match target_addr {
                TargetAddr::Ip(SocketAddr::V4(addr)) => {
                    debug!("SOCKS request address_kind=ipv4");
                    padding = 10;

                    packet[3] = 0x01;
                    packet[4..8].copy_from_slice(&(addr.ip()).octets()); // ip
                    packet[8..padding].copy_from_slice(&addr.port().to_be_bytes());
                    // port
                }
                TargetAddr::Ip(SocketAddr::V6(addr)) => {
                    debug!("SOCKS request address_kind=ipv6");
                    padding = 22;

                    packet[3] = 0x04;
                    packet[4..20].copy_from_slice(&(addr.ip()).octets()); // ip
                    packet[20..padding].copy_from_slice(&addr.port().to_be_bytes());
                    // port
                }
                TargetAddr::Domain(domain, port) => {
                    debug!("SOCKS request address_kind=domain");
                    if domain.len() > u8::MAX as usize {
                        return Err(SocksError::ExceededMaxDomainLen(domain.len()));
                    }
                    padding = 5 + domain.len() + 2;

                    packet[3] = 0x03; // Specify domain type
                    packet[4] = domain.len() as u8; // domain length
                    packet[5..(5 + domain.len())].copy_from_slice(domain.as_bytes()); // domain content
                    packet[(5 + domain.len())..padding].copy_from_slice(&port.to_be_bytes());
                    // port content (.to_be_bytes() convert from u16 to u8 type)
                }
            },
        }

        let target_kind = self.target_addr.as_ref().map_or("unspecified", TargetAddr::logging_kind);
        debug!("SOCKS request encoded target_kind={target_kind} request_bytes={padding}");

        // we limit the end of the packet right after the domain + port number, we don't need to print
        // useless 0 bytes, otherwise other protocol won't understand the request (like HTTP servers).
        self.socket.write_all(&packet[..padding]).await.context("Can't write request header's packet.")?;

        self.socket.flush().await.context("Can't flush request header's packet")?;

        Ok(())
    }

    /// The server send a confirmation (reply) that he had successfully connected (or not) to the
    /// remote server.
    async fn read_request_reply(&mut self) -> Result<TargetAddr> {
        let [version, reply, rsv, address_type] =
            read_exact!(self.socket, [0u8; 4]).context("Received malformed reply")?;

        debug!(
            "Reply received: [version: {version}, reply: {reply}, rsv: {rsv}, address_type: {address_type}]",
            version = version,
            reply = reply,
            rsv = rsv,
            address_type = address_type,
        );

        if version != consts::SOCKS5_VERSION {
            return Err(SocksError::UnsupportedSocksVersion(version));
        }

        if reply != consts::SOCKS5_REPLY_SUCCEEDED {
            return Err(ReplyError::from_u8(reply).into()); // Convert reply received into correct error
        }

        let address = read_address(&mut self.socket, address_type).await?;
        debug!("SOCKS reply bind address_kind={}", address.logging_kind());

        Ok(address)
    }

    pub fn get_socket(self) -> S {
        self.socket
    }

    pub fn get_socket_ref(&self) -> &S {
        &self.socket
    }

    pub fn get_socket_mut(&mut self) -> &mut S {
        &mut self.socket
    }
}

/// A SOCKS5 UDP client.
#[derive(Debug)]
pub struct Socks5Datagram<S: AsyncRead + AsyncWrite + Unpin> {
    socket: UdpSocket,
    // keeps the session alive
    #[allow(dead_code)]
    stream: Socks5Stream<S>,
    proxy_addr: Option<TargetAddr>,
}

impl<S: AsyncRead + AsyncWrite + Unpin> Socks5Datagram<S> {
    /// Creates a UDP socket bound to the specified address which will have its
    /// traffic routed through the specified proxy.
    ///
    /// # Arguments
    /// * `backing_socket` - The underlying socket carrying the socks5 traffic.
    /// * `client_bind_addr` - A socket address indicates the binding source address used to
    /// communicate with the socks5 server.
    ///
    /// # Examples
    /// ```no_run
    /// # use tokio::net::TcpStream;
    /// # use ripdpi_socks5_core::client;
    /// # #[tokio::main(flavor = "current_thread")]
    /// # async fn main() -> Result<(), Box<dyn std::error::Error>>{
    ///     let backing_socket = TcpStream::connect("127.0.0.1:1080").await?;
    ///     let tunnel = client::Socks5Datagram::bind(backing_socket, "[::]:0").await?;
    /// #   Ok(())
    /// # }
    /// ```
    /// # Cancel safety
    ///
    /// NOT cancel-safe: cancellation can interrupt the SOCKS authentication or UDP ASSOCIATE
    /// handshake after it has written a partial frame to `backing_socket`.
    pub async fn bind<U>(backing_socket: S, client_bind_addr: U) -> Result<Socks5Datagram<S>>
    where
        U: ToSocketAddrs,
    {
        Self::bind_internal(backing_socket, Self::create_out_sock(client_bind_addr).await?, None).await
    }
    /// Creates a UDP socket bound to the specified address which will have its
    /// traffic routed through the specified proxy. The given username and password
    /// is used to authenticate to the SOCKS proxy.
    /// # Cancel safety
    ///
    /// NOT cancel-safe: cancellation can interrupt the SOCKS authentication or UDP ASSOCIATE
    /// handshake after it has written a partial frame to `backing_socket`.
    pub async fn bind_with_password<U>(
        backing_socket: S,
        client_bind_addr: U,
        username: &str,
        password: &str,
    ) -> Result<Socks5Datagram<S>>
    where
        U: ToSocketAddrs,
    {
        let auth = AuthenticationMethod::Password { username: username.to_owned(), password: password.to_owned() };
        Self::bind_internal(backing_socket, Self::create_out_sock(client_bind_addr).await?, Some(auth)).await
    }
    /// Use a UdpSocket already created rather than creating a whole new `UdpSocket::bind`.
    pub async fn use_socket(backing_socket: S, out_sock: UdpSocket) -> Result<Socks5Datagram<S>> {
        Self::bind_internal(backing_socket, out_sock, None).await
    }
    /// Same as `use_socket` but with credentials.
    pub async fn use_socket_with_password(
        backing_socket: S,
        out_sock: UdpSocket,
        username: &str,
        password: &str,
    ) -> Result<Socks5Datagram<S>> {
        let auth = AuthenticationMethod::Password { username: username.to_owned(), password: password.to_owned() };
        Self::bind_internal(backing_socket, out_sock, Some(auth)).await
    }

    /// # Cancel safety
    ///
    /// Cancel-safe: `lookup_host` and `UdpSocket::bind` leave no shared state behind when their
    /// future is dropped; the unreturned socket is dropped with this future.
    async fn create_out_sock<U: ToSocketAddrs>(client_bind_addr: U) -> Result<UdpSocket> {
        let client_bind_addr = lookup_host(client_bind_addr).await?.next().context("unreachable")?;
        let out_sock = UdpSocket::bind(client_bind_addr).await?;
        debug!("SOCKS UDP client socket bound");
        Ok(out_sock)
    }

    /// # Cancel safety
    ///
    /// NOT cancel-safe: the SOCKS authentication and UDP ASSOCIATE handshake use `write_all` and
    /// `read_exact`, so cancellation can leave the proxy with a partially processed request.
    async fn bind_internal(
        backing_socket: S,
        out_sock: UdpSocket,
        auth: Option<AuthenticationMethod>,
    ) -> Result<Socks5Datagram<S>> {
        // Init socks5 stream.
        let mut proxy_stream = Socks5Stream::use_stream(backing_socket, auth, Config::default()).await?;

        // we don't know what our IP is from the perspective of the proxy, so
        // don't try to pass `addr` in here.
        let client_src = TargetAddr::Ip("[::]:0".parse().unwrap());
        let proxy_addr = proxy_stream.request(Socks5Command::UDPAssociate, client_src).await?;

        let proxy_addr_resolved =
            proxy_addr.socket_addr().context("SOCKS UDP ASSOCIATE reply must contain an IP address")?;
        debug!("SOCKS UDP client connecting proxy_kind={}", proxy_addr.logging_kind());
        out_sock.connect(proxy_addr_resolved).await?;
        debug!("UdpSocket client connected");

        Ok(Socks5Datagram { socket: out_sock, stream: proxy_stream, proxy_addr: Some(proxy_addr) })
    }

    /// Like `UdpSocket::send_to`.
    ///
    /// # Note
    ///
    /// The SOCKS protocol inserts a header at the beginning of the message. The
    /// header will be 10 bytes for an IPv4 address, 22 bytes for an IPv6
    /// address, and 7 bytes plus the length of the domain for a domain address.
    pub async fn send_to<A>(&self, data: &[u8], addr: A) -> Result<usize>
    where
        A: ToTargetAddr,
    {
        let mut buf = new_udp_header(addr)?;
        let buf_len = buf.len();
        buf.extend_from_slice(data);

        return Ok(self.socket.send(&buf).await? - buf_len);
    }

    /// Like `UdpSocket::recv_from`.
    pub async fn recv_from(&self, data_store: &mut [u8]) -> Result<(usize, TargetAddr)> {
        let mut buf = [0u8; 0x10000];
        let (size, _) = self.socket.recv_from(&mut buf).await?;

        let (frag, target_addr, data) = parse_udp_request(&mut buf[..size]).await?;

        if frag != 0 {
            return Err(SocksError::Other(anyhow::anyhow!("Unsupported frag value.")));
        }

        data_store[..data.len()].copy_from_slice(data);
        Ok((data.len(), target_addr))
    }

    /// Returns the address of the proxy-side UDP socket through which all
    /// messages will be routed.
    pub fn proxy_addr(&self) -> Result<&TargetAddr> {
        Ok(self.proxy_addr.as_ref().context("proxy addr is not ready")?)
    }

    /// Returns a shared reference to the inner socket.
    pub fn get_ref(&self) -> &UdpSocket {
        &self.socket
    }

    /// Returns a mutable reference to the inner socket.
    pub fn get_mut(&mut self) -> &mut UdpSocket {
        &mut self.socket
    }
}

/// Api if you want to use TcpStream to create a new connection to the SOCKS5 server.
impl Socks5Stream<TcpStream> {
    /// Connects to a target server through a SOCKS5 proxy.
    /// # Cancel safety
    ///
    /// NOT cancel-safe: cancellation can interrupt the SOCKS handshake after a partial request
    /// has been written to the proxy connection.
    pub async fn connect<T>(socks_server: T, target_addr: String, target_port: u16, config: Config) -> Result<Self>
    where
        T: ToSocketAddrs,
    {
        Self::connect_raw(Socks5Command::TCPConnect, socks_server, target_addr, target_port, None, config).await
    }

    /// Connect with credentials
    /// # Cancel safety
    ///
    /// NOT cancel-safe: cancellation can interrupt the authenticated SOCKS handshake after a
    /// partial request has been written to the proxy connection.
    pub async fn connect_with_password<T>(
        socks_server: T,
        target_addr: String,
        target_port: u16,
        username: String,
        password: String,
        config: Config,
    ) -> Result<Self>
    where
        T: ToSocketAddrs,
    {
        let auth = AuthenticationMethod::Password { username, password };

        Self::connect_raw(Socks5Command::TCPConnect, socks_server, target_addr, target_port, Some(auth), config).await
    }

    /// Process clients SOCKS requests
    /// This is the entry point where a whole request is processed.
    /// # Cancel safety
    ///
    /// NOT cancel-safe: although DNS lookup and TCP connection are cancel-safe, `use_stream` and
    /// `request` can be cancelled during `write_all` or `read_exact`, leaving a partial SOCKS
    /// exchange on the discarded connection.
    pub async fn connect_raw<T>(
        cmd: Socks5Command,
        socks_server: T,
        target_addr: String,
        target_port: u16,
        auth: Option<AuthenticationMethod>,
        config: Config,
    ) -> Result<Self>
    where
        T: ToSocketAddrs,
    {
        let addr = lookup_host(socks_server).await?.next().context("unreachable")?;
        let socket = match config.connect_timeout {
            None => tcp_connect(addr).await?,
            Some(connect_timeout) => tcp_connect_with_timeout(addr, connect_timeout).await?,
        };
        debug!("SOCKS TCP client connected");

        // Specify the target, here domain name, dns will be resolved on the server side
        let target_addr = (target_addr.as_str(), target_port)
            .to_target_addr()
            .context("Can't convert address to TargetAddr format")?;

        // upgrade the TcpStream to Socks5Stream
        let mut socks_stream = Self::use_stream(socket, auth, config).await?;
        socks_stream.request(cmd, target_addr).await?;

        Ok(socks_stream)
    }
}

/// Allow us to read directly from the struct
impl<S> AsyncRead for Socks5Stream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_read(
        mut self: Pin<&mut Self>,
        context: &mut std::task::Context,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.socket).poll_read(context, buf)
    }
}

/// Allow us to write directly into the struct
impl<S> AsyncWrite for Socks5Stream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_write(mut self: Pin<&mut Self>, context: &mut std::task::Context, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.socket).poll_write(context, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, context: &mut std::task::Context) -> Poll<io::Result<()>> {
        Pin::new(&mut self.socket).poll_flush(context)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, context: &mut std::task::Context) -> Poll<io::Result<()>> {
        Pin::new(&mut self.socket).poll_shutdown(context)
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Mutex, Once};

    use super::*;
    use log::{LevelFilter, Log, Metadata, Record};
    use tokio::io::DuplexStream;

    // cancel-safe: cancellation drops the local mock and protocol state.
    #[tokio::test(flavor = "current_thread")]
    async fn request_header_completes_short_writes() {
        let packet = [5, 1, 0, 1, 127, 0, 0, 1, 1, 187];
        let socket = tokio_test::io::Builder::new()
            .write(&packet[..1])
            .wait(std::time::Duration::from_millis(1))
            .write(&packet[1..])
            .build();
        let mut stream = Socks5Stream {
            socket,
            config: Config::default(),
            target_addr: Some(TargetAddr::Ip("127.0.0.1:443".parse().expect("target"))),
        };
        stream.request_header(Socks5Command::TCPConnect).await.expect("complete request");
    }

    struct CapturingLogger {
        messages: Mutex<Vec<String>>,
    }

    impl Log for CapturingLogger {
        fn enabled(&self, _metadata: &Metadata<'_>) -> bool {
            true
        }

        fn log(&self, record: &Record<'_>) {
            if self.enabled(record.metadata()) {
                self.messages.lock().expect("lock captured logs").push(record.args().to_string());
            }
        }

        fn flush(&self) {}
    }

    static CAPTURING_LOGGER: CapturingLogger = CapturingLogger { messages: Mutex::new(Vec::new()) };
    static CAPTURING_LOGGER_INIT: Once = Once::new();

    fn reset_captured_logs() {
        CAPTURING_LOGGER_INIT.call_once(|| {
            log::set_logger(&CAPTURING_LOGGER).expect("install capturing logger");
        });
        log::set_max_level(LevelFilter::Debug);
        CAPTURING_LOGGER.messages.lock().expect("lock captured logs").clear();
    }

    fn captured_logs() -> String {
        CAPTURING_LOGGER.messages.lock().expect("lock captured logs").join("\n")
    }

    /// Build a `Socks5Stream` over an in-memory duplex socket without running
    /// the SOCKS handshake, so a single sub-negotiation method can be exercised
    /// in isolation. Returns the stream and the peer half (server side).
    fn duplex_stream() -> (Socks5Stream<DuplexStream>, DuplexStream) {
        let (client, server) = tokio::io::duplex(1024);
        let stream = Socks5Stream { socket: client, target_addr: None, config: Config::default() };
        (stream, server)
    }

    fn password_methods(username: &str, password: &str) -> Vec<AuthenticationMethod> {
        vec![
            AuthenticationMethod::None,
            AuthenticationMethod::Password { username: username.to_owned(), password: password.to_owned() },
        ]
    }

    // cancel-safe: cancellation drops both in-memory stream halves and leaves no shared protocol state.
    #[tokio::test(flavor = "current_thread")]
    async fn socks5_domain_request_preserves_wire_target_without_logging_it() {
        reset_captured_logs();
        let domain = "privacy-sentinel.youtube.example";
        let port = 443;
        let (mut stream, mut server) = duplex_stream();
        stream.target_addr = Some(TargetAddr::Domain(domain.to_owned(), port));
        let mut frame = vec![0u8; 5 + domain.len() + 2];

        let (request_result, read_result) =
            tokio::join!(stream.request_header(Socks5Command::TCPConnect), server.read_exact(&mut frame),);
        request_result.expect("encode and write SOCKS5 domain request");
        read_result.expect("drain SOCKS5 domain request");

        assert_eq!(frame[0..5], [consts::SOCKS5_VERSION, consts::SOCKS5_CMD_TCP_CONNECT, 0, 3, domain.len() as u8]);
        assert_eq!(&frame[5..5 + domain.len()], domain.as_bytes());
        assert_eq!(&frame[5 + domain.len()..], &port.to_be_bytes());

        let logs = captured_logs();
        let decimal_domain = domain.as_bytes().iter().map(u8::to_string).collect::<Vec<_>>().join(", ");
        assert!(
            !logs.contains(domain) && !logs.contains(&decimal_domain),
            "SOCKS5 debug logs must not reveal the domain as text or decimal bytes; logs: {logs}",
        );
    }

    #[test]
    fn socks_request_logs_exclude_raw_wire_and_target_values() {
        let source = include_str!("client.rs");
        let forbidden = [
            "Bytes ".to_owned() + "long version",
            "Bytes ".to_owned() + "shorted version",
            "Requesting ".to_owned() + "headers `{:?}`",
            "addr ".to_owned() + "ip {:?}",
        ];

        for phrase in forbidden {
            assert!(!source.contains(&phrase), "raw SOCKS logging must not include {phrase}");
        }
    }

    /// A 256-byte username must be rejected with a clean error rather than
    /// silently truncating the ULEN byte and desynchronizing the auth frame.
    #[tokio::test]
    async fn use_password_auth_rejects_oversized_username() {
        let (mut stream, _server) = duplex_stream();
        let sensitive_username = "private-user-".repeat(32);
        let methods = password_methods(&sensitive_username, "secret");

        let result = stream.use_password_auth(methods).await;

        match result {
            Err(SocksError::ArgumentInputError(msg)) => {
                assert!(!msg.contains("username"));
                assert!(!msg.contains(&sensitive_username));
                assert_eq!(msg, "SOCKS5 credentials exceed RFC 1929 length limit");
            }
            other => panic!("expected ArgumentInputError for oversized username, got {other:?}"),
        }
    }

    /// A 256-byte password must be rejected the same way (PLEN limit).
    #[tokio::test]
    async fn use_password_auth_rejects_oversized_password() {
        let (mut stream, _server) = duplex_stream();
        let methods = password_methods("user", &"p".repeat(256));

        let result = stream.use_password_auth(methods).await;

        match result {
            Err(SocksError::ArgumentInputError(msg)) => {
                assert_eq!(msg, "SOCKS5 credentials exceed RFC 1929 length limit");
            }
            other => panic!("expected ArgumentInputError for oversized password, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn use_password_auth_rejection_does_not_expose_username() {
        let (mut stream, mut server) = duplex_stream();
        let sensitive_username = "private-user-42";
        let methods = password_methods(sensitive_username, "secret");
        let handle = tokio::spawn(async move { stream.use_password_auth(methods).await });
        let mut frame = vec![0u8; 1 + 1 + sensitive_username.len() + 1 + "secret".len()];

        server.read_exact(&mut frame).await.expect("read auth frame");
        server.write_all(&[consts::SOCKS5_VERSION, 1]).await.expect("write rejection reply");

        let result = handle.await.expect("join");
        match result {
            Err(SocksError::AuthenticationRejected(message)) => {
                assert!(!message.contains(sensitive_username));
                assert_eq!(message, "SOCKS5 authentication rejected");
            }
            other => panic!("expected authentication rejection, got {other:?}"),
        }
    }

    /// The rejection must happen *before* anything is written to the socket,
    /// otherwise a truncated/partial auth frame would already be on the wire.
    /// We assert the server half observes zero bytes from the failed attempt.
    #[tokio::test]
    async fn oversized_credential_writes_no_bytes() {
        let (mut stream, mut server) = duplex_stream();
        let methods = password_methods(&"u".repeat(300), "secret");

        let result = stream.use_password_auth(methods).await;
        assert!(matches!(result, Err(SocksError::ArgumentInputError(_))));

        // Drop the client side so the server read returns EOF promptly rather
        // than blocking. No bytes should have been written.
        drop(stream);
        let mut buf = [0u8; 16];
        let n = server.read(&mut buf).await.expect("server read");
        assert_eq!(n, 0, "no auth bytes should be written when credentials are rejected");
    }

    /// A 255-byte credential is exactly at the limit and must still be encoded
    /// (the length check is `> 255`, not `>= 255`). We can't complete the auth
    /// without a cooperating server, so we just confirm the frame is written
    /// with the correct ULEN/PLEN bytes by reading the client's output.
    #[tokio::test]
    async fn max_length_credential_encodes_full_frame() {
        let (mut stream, mut server) = duplex_stream();
        let username = "u".repeat(255);
        let methods = password_methods(&username, "p");

        // Spawn the auth attempt; it will write the frame then block on the
        // server reply, which we read and answer with a success code.
        let handle = tokio::spawn(async move { stream.use_password_auth(methods).await });

        // Frame layout: [0x01, ULEN(255), username(255), PLEN(1), password(1)]
        let mut frame = vec![0u8; 1 + 1 + 255 + 1 + 1];
        server.read_exact(&mut frame).await.expect("read auth frame");
        assert_eq!(frame[0], 0x01, "auth sub-negotiation version");
        assert_eq!(frame[1], 255, "ULEN must encode the full 255-byte username, not a truncated value");
        assert_eq!(&frame[2..257], username.as_bytes());
        assert_eq!(frame[257], 1, "PLEN");

        // Reply with success so the spawned task completes cleanly.
        server.write_all(&[consts::SOCKS5_VERSION, consts::SOCKS5_REPLY_SUCCEEDED]).await.expect("write reply");
        let result = handle.await.expect("join");
        assert!(result.is_ok(), "max-length credential should authenticate, got {result:?}");
    }
}
