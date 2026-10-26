#![forbid(unsafe_code)]
use crate::read_exact;
use crate::socks4::{ReplyError, Socks4Command, consts};
use crate::util::target_addr::{TargetAddr, ToTargetAddr};
use crate::{Result, SocksError, SocksError::ReplySocks4Error};
use anyhow::Context;
use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::task::Poll;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpStream, ToSocketAddrs};

const MAX_ADDR_LEN: usize = 260;

/// A SOCKS4 client.
/// `Socks4Stream` implements [`AsyncRead`] and [`AsyncWrite`].
#[derive(Debug)]
pub struct Socks4Stream<S: AsyncRead + AsyncWrite + Unpin> {
    socket: S,
    target_addr: Option<TargetAddr>,
}

impl<S> Socks4Stream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    /// Possibility to use a stream already created rather than
    /// creating a whole new `TcpStream::connect()`.
    pub fn use_stream(socket: S) -> Result<Self> {
        let stream = Socks4Stream { socket, target_addr: None };
        Ok(stream)
    }

    /// https://www.openssh.com/txt/socks4.protocol
    /// https://www.openssh.com/txt/socks4a.protocol
    ///
    /// 1) CONNECT
    ///
    ///           +----+----+----+----+----+----+----+----+----+----+....+----+
    ///           | VN | CD | DSTPORT |      DSTIP        | USERID       |NULL|
    ///           +----+----+----+----+----+----+----+----+----+----+....+----+
    /// #of bytes    1    1      2              4           variable       1
    ///
    ///   VN is the SOCKS protocol version number and should be 4. CD is the
    ///   SOCKS command code and should be 1 for CONNECT request. NULL is a byte
    ///   of all zero bits.
    ///
    ///   The SOCKS server checks to see whether such a request should be granted
    ///   based on any combination of source IP address, destination IP address,
    ///   destination port number, the userid, and information it may obtain by
    ///   consulting IDENT, cf. RFC 1413.  If the request is granted, the SOCKS
    ///   server makes a connection to the specified port of the destination host.
    ///   A reply packet is sent to the client when this connection is established,
    ///   or when the request is rejected or the operation fails.
    ///
    ///  Response:
    ///
    ///           +----+----+----+----+----+----+----+----+
    ///           | VN | CD | DSTPORT |      DSTIP        |
    ///           +----+----+----+----+----+----+----+----+
    /// #of bytes    1    1      2              4
    ///
    ///   VN is the version of the reply code and should be 0. CD is the result
    ///   code with one of the following values:
    ///
    ///   	90: request granted
    ///   	91: request rejected or failed
    ///   	92: request rejected because SOCKS server cannot connect to
    ///   	    identd on the client
    ///   	93: request rejected because the client program and identd
    ///  	    report different user-ids
    ///
    pub async fn request(&mut self, cmd: Socks4Command, target_addr: TargetAddr, resolve_locally: bool) -> Result<()> {
        let resolved =
            if target_addr.is_domain() && resolve_locally { target_addr.resolve_dns().await? } else { target_addr };
        self.target_addr = Some(resolved);
        self.send_command_request(&cmd).await?;
        self.read_command_request().await?;

        Ok(())
    }

    async fn send_command_request(&mut self, cmd: &Socks4Command) -> Result<()> {
        // VN CD DSTPORT DSTIP USERID(empty) NULL, plus the SOCKS4a hostname and NULL.
        let mut packet = Vec::with_capacity(MAX_ADDR_LEN);
        packet.extend_from_slice(&[consts::SOCKS4_VERSION, cmd.as_u8()]);

        match &self.target_addr {
            Some(TargetAddr::Ip(SocketAddr::V4(addr))) => {
                packet.extend_from_slice(&addr.port().to_be_bytes());
                packet.extend_from_slice(&addr.ip().octets());
                packet.push(0);
                Ok(())
            }
            Some(TargetAddr::Ip(SocketAddr::V6(_))) => {
                error!("SOCKS4 does not support ipv6 targets");
                Err(ReplySocks4Error(ReplyError::AddressTypeNotSupported))
            }
            Some(TargetAddr::Domain(domain, port)) => {
                let domain_bytes = domain.as_bytes();
                if domain_bytes.len() > MAX_ADDR_LEN - 8 {
                    return Err(SocksError::ExceededMaxDomainLen(domain_bytes.len()));
                }
                packet.extend_from_slice(&port.to_be_bytes());
                packet.extend_from_slice(&[0, 0, 0, 1, 0]);
                packet.extend_from_slice(domain_bytes);
                packet.push(0);
                Ok(())
            }
            None => Err(SocksError::ArgumentInputError("send_command_request called before target_addr was set")),
        }?;
        self.socket.write_all(&packet).await?;
        Ok(())
    }

    #[rustfmt::skip]
    async fn read_command_request(&mut self) -> Result<()> {
        let [_, cd] = read_exact!(self.socket, [0u8; 2])?;
        let reply = ReplyError::from_u8(cd);
        match reply {
            ReplyError::Succeeded => Ok(()),
            _                     => Err(SocksError::ReplySocks4Error(reply))
        }
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

/// Api if you want to use TcpStream to create a new connection to the SOCKS4 server.
impl Socks4Stream<TcpStream> {
    /// Connects to a target server through a SOCKS4 proxy.
    pub async fn connect<T>(
        socks_server: T,
        target_addr: String,
        target_port: u16,
        resolve_locally: bool,
    ) -> Result<Self>
    where
        T: ToSocketAddrs,
    {
        Self::connect_raw(Socks4Command::Connect, socks_server, target_addr, target_port, resolve_locally).await
    }

    /// Process clients SOCKS requests
    /// This is the entry point where a whole request is processed.
    pub async fn connect_raw<T>(
        cmd: Socks4Command,
        socks_server: T,
        target_addr: String,
        target_port: u16,
        resolve_locally: bool,
    ) -> Result<Self>
    where
        T: ToSocketAddrs,
    {
        let socket = TcpStream::connect(socks_server).await?;
        debug!("SOCKS4 client connected");

        // Specify the target, here domain name, dns will be resolved on the server side
        let target_addr = (target_addr.as_str(), target_port)
            .to_target_addr()
            .context("Can't convert address to TargetAddr format")?;

        // upgrade the TcpStream to Socks4Stream
        let mut socks_stream = Self::use_stream(socket)?;
        socks_stream.request(cmd, target_addr, resolve_locally).await?;

        Ok(socks_stream)
    }
}

/// Allow us to read directly from the struct
impl<S> AsyncRead for Socks4Stream<S>
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
impl<S> AsyncWrite for Socks4Stream<S>
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
    use super::*;
    use tokio::net::TcpListener;

    /// Canned response written by the SOCKS4 fixture once the proxied
    /// "connection" is established. The single signature line is what the
    /// parser in [`get_humans_txt`] picks up.
    const FIXTURE_HUMANS_TXT_RESPONSE: &[u8] = b"\
HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n\
Google is built by a large team of engineers, designers, researchers, robots, \
and others in many different sites across the globe. It is updated continuously, \
and built with more tools and technologies than we can shake a stick at. If you'd \
like to help us out, see careers.google.com.\n";

    /// Read one SOCKS4 or SOCKS4a request: the 8-byte header, the USERID up
    /// to its NUL and, for a 0.0.0.x DSTIP, the hostname up to its NUL.
    async fn read_socks4_request<R: AsyncRead + Unpin>(socket: &mut R) -> Vec<u8> {
        let mut request = vec![0u8; 8];
        socket.read_exact(&mut request).await.expect("read socks4 header");
        let fields = if request[4..7] == [0, 0, 0] && request[7] != 0 { 2 } else { 1 };
        for _ in 0..fields {
            loop {
                let byte = socket.read_u8().await.expect("read socks4 field");
                request.push(byte);
                if byte == 0 {
                    break;
                }
            }
        }
        request
    }

    /// Spawn an in-process SOCKS4 server that accepts one CONNECT, replies
    /// success, drains the client's HTTP request, then writes a canned
    /// humans.txt body containing the signature "Google is built ..." line.
    /// Returns the bound address. The task exits after the first connection.
    async fn spawn_socks4_fixture() -> SocketAddr {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind socks4 fixture");
        let addr = listener.local_addr().expect("fixture addr");
        tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.expect("accept");

            let request = read_socks4_request(&mut socket).await;
            assert_eq!(request[0], consts::SOCKS4_VERSION, "VN must be 4");
            assert_eq!(request[1], consts::SOCKS4_CMD_CONNECT, "CD must be CONNECT");

            // Reply: VN=0, CD=0x5A (succeeded), DSTPORT/DSTIP echo.
            let reply = [
                0u8,
                consts::SOCKS4_REPLY_SUCCEEDED,
                request[2],
                request[3],
                request[4],
                request[5],
                request[6],
                request[7],
            ];
            socket.write_all(&reply).await.expect("write reply");

            // Drain the HTTP request the client writes after handshake.
            let mut scratch = [0u8; 2048];
            let _ = socket.read(&mut scratch).await.expect("read HTTP request");
            socket.write_all(FIXTURE_HUMANS_TXT_RESPONSE).await.expect("write canned body");
            socket.shutdown().await.expect("shutdown fixture connection");
        });
        addr
    }

    fn get_domain() -> String {
        // Use a literal v4 address: SOCKS4 has no IPv6 support, and
        // `localhost` may resolve to `::1` first on dual-stack hosts.
        "127.0.0.1".to_string()
    }

    async fn get_humans_txt(socks: &mut Socks4Stream<TcpStream>) -> Option<String> {
        let headers = format!(
            "GET /humans.txt HTTP/1.1\r\n\
                     Host: {}\r\n\
                     User-Agent: fast-socks5/0.1.0\r\n\
                     Accept: */*\r\n\r\n",
            get_domain()
        );

        socks.write_all(headers.as_bytes()).await.expect("should successfully write");

        // Read until EOF so we capture the full canned body in one buffer.
        let mut response = Vec::with_capacity(2048);
        socks.read_to_end(&mut response).await.expect("should successfully read");

        let response_str = String::from_utf8_lossy(&response);
        response_str.split('\n').rfind(|x| x.starts_with("Google")).map(|x| x.to_string())
    }

    fn assert_response_body(response_body: &String) {
        let expected = "Google is built by a large team of engineers, designers, researchers, robots, \
        and others in many different sites across the globe. It is updated continuously, \
        and built with more tools and technologies than we can shake a stick at. If you'd \
        like to help us out, see careers.google.com.";
        assert_eq!(expected, response_body);
    }

    #[tokio::test]
    pub async fn test_use_stream() {
        let socks_addr = spawn_socks4_fixture().await;
        let tcp = TcpStream::connect(socks_addr).await.expect("should connect to local socks4 fixture");

        let mut socks = Socks4Stream::use_stream(tcp).expect("should wrap to socks stream");

        socks
            .request(Socks4Command::Connect, TargetAddr::Domain(get_domain(), 80), true)
            .await
            .expect("should send connect successfully");

        let response_body = get_humans_txt(&mut socks).await.expect("should have response_body");

        assert_response_body(&response_body);
    }

    #[tokio::test]
    pub async fn test_use_stream_local_resolve() {
        let socks_addr = spawn_socks4_fixture().await;
        let mut socks = Socks4Stream::connect(socks_addr, get_domain(), 80, true)
            .await
            .expect("should connect successfully to socks4 server");

        let response_body = get_humans_txt(&mut socks).await.expect("should have response_body");

        assert_response_body(&response_body);
    }

    /// Run `request` against an in-memory peer that records the request and
    /// answers with `reply`.
    async fn wire_request(target: TargetAddr, reply: [u8; 8]) -> (Vec<u8>, Result<()>) {
        let (client_half, mut server_half) = tokio::io::duplex(1024);
        let server = tokio::spawn(async move {
            let request = read_socks4_request(&mut server_half).await;
            server_half.write_all(&reply).await.expect("write reply");
            request
        });
        let mut stream = Socks4Stream::use_stream(client_half).expect("wrap stream");
        let result = stream.request(Socks4Command::Connect, target, false).await;
        let request = server.await.expect("server task");
        (request, result)
    }

    #[tokio::test]
    async fn connect_request_carries_no_padding_after_the_userid_terminator() {
        let (client_half, mut server_half) = tokio::io::duplex(1024);
        let client = tokio::spawn(async move {
            let mut stream = Socks4Stream::use_stream(client_half).expect("wrap stream");
            let target = TargetAddr::Ip("10.1.2.3:8080".parse().expect("target"));
            let _ = stream.request(Socks4Command::Connect, target, false).await;
        });
        let mut header = [0u8; 9];
        server_half.read_exact(&mut header).await.expect("read request");
        assert_eq!(header, [4, 1, 0x1f, 0x90, 10, 1, 2, 3, 0]);
        server_half.write_all(&[0, consts::SOCKS4_REPLY_SUCCEEDED, 0, 0, 0, 0, 0, 0]).await.expect("reply");
        client.await.expect("client task");
        let mut trailing = Vec::new();
        server_half.read_to_end(&mut trailing).await.expect("read trailing bytes");
        assert!(trailing.is_empty(), "request must end at the USERID NUL, got {} more bytes", trailing.len());
    }

    #[tokio::test]
    async fn socks4a_request_puts_the_hostname_after_the_userid_terminator() {
        let target = TargetAddr::Domain("example.org".to_string(), 443);
        let (request, result) = wire_request(target, [0, consts::SOCKS4_REPLY_SUCCEEDED, 0, 0, 0, 0, 0, 0]).await;
        result.expect("granted");
        assert_eq!(request, b"\x04\x01\x01\xbb\x00\x00\x00\x01\x00example.org\x00");
    }

    #[tokio::test]
    async fn socks4_domain_too_long_returns_error() {
        // A domain longer than MAX_ADDR_LEN - 8 (= 252) bytes previously caused
        // an index-out-of-bounds panic in `send_command_request`. Verify it now
        // returns `Err(SocksError::ExceededMaxDomainLen)` without panicking.
        let long_domain = "a".repeat(300);

        // Use an in-memory duplex stream — no real network required.
        let (client_half, _server_half) = tokio::io::duplex(1024);
        let mut stream = Socks4Stream::use_stream(client_half).expect("wrap stream");

        let result = stream.request(Socks4Command::Connect, TargetAddr::Domain(long_domain, 80), false).await;

        assert!(
            matches!(result, Err(SocksError::ExceededMaxDomainLen(_))),
            "expected ExceededMaxDomainLen, got {result:?}",
        );
    }
}
