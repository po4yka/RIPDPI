//! VLESS+Reality loopback fixture.
//!
//! A proxying VLESS+Reality server for the chain-relay end-to-end tests
//! (`audit-vless-chained-connect-over-relay-end-to-end-tests`). It completes a
//! real TLS 1.3 handshake with the boring-based [`ripdpi_vless::VlessRealityClient`]
//! (the client disables certificate verification — `SslVerifyMode::NONE` — because
//! REALITY authenticates with the sealed SessionID, not the cover certificate, so
//! a self-signed cover cert interoperates), reads the VLESS request header, writes
//! the VLESS response header, then proxies the decrypted byte stream to the
//! request's stated target. It can serve as either chain hop:
//!
//! - as the **entry** hop, the client's request target is the next hop's
//!   authority, and the fixture proxies the next hop's nested TLS records onward;
//! - as the **exit** hop, the request target is the caller's final destination
//!   (the fixture's own embedded echo, via [`VlessRealityLoopback::target_port`]).
//!
//! The server uses a **boring `SslAcceptor`** (not rustls): the REALITY client
//! emits a heavily uTLS-fingerprinted ClientHello (browser extension ordering,
//! record choreography, PQ-hybrid key shares) that a strict rustls server rejects
//! with `DECODE_ERROR`; a boring server accepts it natively (same TLS stack as the
//! client). REALITY auth is intentionally NOT validated — per the loopback-harness
//! design (`docs/architecture/protocol-loopback-harness-design.md`, "Risks"), a
//! real server-side REALITY/BoringSSL handshake is too brittle for unit scope.
//! This is a dev/test fixture, never a production VLESS server.

use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::thread::{self, JoinHandle};

use boring::pkey::{PKey, Private};
use boring::ssl::{SslAcceptor, SslMethod, SslVerifyMode};
use boring::x509::X509;
use futures::future::poll_fn;
use rcgen::generate_simple_self_signed;
use ripdpi_vless::vision::{VisionStream, XtlsDirectRead, XtlsDirectWrite};
use ripdpi_vless::wire::{ParseRequestError, VlessCommand, encode_response, parse_request_header};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::oneshot;
use tokio_boring::SslStream;
use tokio_util::compat::{FuturesAsyncReadCompatExt, TokioAsyncReadCompatExt};

// Independent server-side oracle for the Xray limits pinned by the fixture.
const MAX_XUDP_METADATA: usize = 512;
const MAX_XUDP_PAYLOAD: usize = 8192 - 666;

const SERVER_NAME: &str = "vless.fixture.test";

/// Max bytes buffered while waiting for a full VLESS request header. The header
/// is at most version(1) + uuid(16) + addons_len(1) + addons(<=255) + cmd(1) +
/// port(2) + addrtype(1) + addr(<=257); 1 KiB is comfortably above that ceiling
/// and bounds a malformed peer.
const MAX_REQUEST_HEADER: usize = 1024;

/// A loopback VLESS+Reality server that proxies to the request's target.
// Drop order: shutdown sends before thread join, so the fixture tasks observe the
// shutdown signal before their runtime thread is joined.
pub struct VlessRealityLoopback {
    address: SocketAddr,
    target_address: SocketAddr,
    udp_target_address: SocketAddr,
    certificate_pem: String,
    observed_target: Arc<Mutex<Option<String>>>,
    shutdown: Option<oneshot::Sender<()>>,
    thread: Option<JoinHandle<()>>,
}

impl VlessRealityLoopback {
    /// Start the fixture: a VLESS proxy listener plus an embedded TCP echo
    /// upstream (the exit-hop final target).
    pub async fn start() -> io::Result<Self> {
        let tls = tls_acceptor()?;
        let acceptor = Arc::new(tls.acceptor);
        let observed_target = Arc::new(Mutex::new(None));
        let (addr_tx, addr_rx) = std::sync::mpsc::channel();
        let (shutdown_tx, shutdown_rx) = oneshot::channel();
        let observed_for_thread = Arc::clone(&observed_target);

        let thread = thread::spawn(move || {
            let runtime =
                tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("build vless fixture runtime");
            runtime.block_on(async move {
                let vless_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind vless fixture");
                let echo_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind vless target echo");
                let udp_echo = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind vless UDP target echo");
                let vless_address = vless_listener.local_addr().expect("vless fixture local addr");
                let target_address = echo_listener.local_addr().expect("vless echo local addr");
                let udp_target_address = udp_echo.local_addr().expect("vless UDP echo local addr");
                addr_tx.send((vless_address, target_address, udp_target_address)).ok();
                let echo_task = tokio::spawn(serve_echo(echo_listener));
                let udp_echo_task = tokio::spawn(serve_udp_echo(udp_echo));
                tokio::select! {
                    _ = shutdown_rx => {}
                    _ = serve_vless(vless_listener, acceptor, observed_for_thread) => {}
                }
                echo_task.abort();
                udp_echo_task.abort();
            });
        });

        let (address, target_address, udp_target_address) =
            addr_rx.recv().map_err(|error| io::Error::other(format!("fixture failed to start: {error}")))?;
        Ok(Self {
            address,
            target_address,
            udp_target_address,
            certificate_pem: tls.certificate_pem,
            observed_target,
            shutdown: Some(shutdown_tx),
            thread: Some(thread),
        })
    }

    /// Port of the VLESS proxy listener (a chain hop's `server_port`).
    pub fn port(&self) -> u16 {
        self.address.port()
    }

    /// Port of the embedded TCP echo upstream (the exit hop's final target).
    pub fn target_port(&self) -> u16 {
        self.target_address.port()
    }

    /// Port of the embedded UDP echo upstream used by XUDP tests.
    pub fn udp_target_port(&self) -> u16 {
        self.udp_target_address.port()
    }

    /// The SNI / `server_name` a hop config should carry. Cover-cert only — the
    /// client does not verify it (REALITY auth model).
    pub fn server_name(&self) -> &'static str {
        SERVER_NAME
    }

    /// Self-signed cover certificate in PEM, for callers that want to pin it.
    pub fn certificate_pem(&self) -> &str {
        &self.certificate_pem
    }

    /// The last VLESS request target the fixture parsed, for assertions.
    pub fn observed_target(&self) -> Option<String> {
        self.observed_target.lock().expect("fixture observation").clone()
    }
}

impl Drop for VlessRealityLoopback {
    fn drop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            shutdown.send(()).ok();
        }
        if let Some(thread) = self.thread.take() {
            thread.join().ok();
        }
    }
}

struct TlsAcceptorBundle {
    acceptor: SslAcceptor,
    certificate_pem: String,
}

fn tls_acceptor() -> io::Result<TlsAcceptorBundle> {
    let certificate = generate_simple_self_signed(vec![SERVER_NAME.to_owned(), "127.0.0.1".to_owned()])
        .map_err(|error| io::Error::other(error.to_string()))?;
    let certificate_pem = certificate.cert.pem();
    let cert = X509::from_der(certificate.cert.der().as_ref()).map_err(|error| io::Error::other(error.to_string()))?;
    let key: PKey<Private> = PKey::private_key_from_der(&certificate.signing_key.serialize_der())
        .map_err(|error| io::Error::other(error.to_string()))?;
    let mut acceptor =
        SslAcceptor::mozilla_intermediate(SslMethod::tls()).map_err(|error| io::Error::other(error.to_string()))?;
    acceptor.set_certificate(&cert).map_err(|error| io::Error::other(error.to_string()))?;
    acceptor.set_private_key(&key).map_err(|error| io::Error::other(error.to_string()))?;
    // The fixture does not authenticate the client (REALITY's auth lives in the
    // sealed SessionID, which this dev fixture does not validate).
    acceptor.set_verify(SslVerifyMode::NONE);
    Ok(TlsAcceptorBundle { acceptor: acceptor.build(), certificate_pem })
}

async fn serve_echo(listener: TcpListener) {
    loop {
        let Ok((mut socket, _)) = listener.accept().await else {
            break;
        };
        tokio::spawn(async move {
            let (mut reader, mut writer) = socket.split();
            let _ = tokio::io::copy(&mut reader, &mut writer).await;
        });
    }
}

async fn serve_udp_echo(socket: UdpSocket) {
    let mut payload = vec![0_u8; MAX_XUDP_PAYLOAD];
    while let Ok((read, peer)) = socket.recv_from(&mut payload).await {
        if socket.send_to(&payload[..read], peer).await.is_err() {
            return;
        }
    }
}

async fn serve_vless(listener: TcpListener, acceptor: Arc<SslAcceptor>, observed_target: Arc<Mutex<Option<String>>>) {
    loop {
        let Ok((socket, _)) = listener.accept().await else {
            break;
        };
        let _ = socket.set_nodelay(true);
        let acceptor = Arc::clone(&acceptor);
        let observed_target = Arc::clone(&observed_target);
        tokio::spawn(async move {
            let Ok(tls) = tokio_boring::accept(&acceptor, socket).await else {
                return;
            };
            let _ = handle_connection(tls, observed_target).await;
        });
    }
}

async fn handle_connection(
    mut tls: SslStream<TcpStream>,
    observed_target: Arc<Mutex<Option<String>>>,
) -> io::Result<()> {
    // 1. Read the VLESS request header incrementally until it parses. The
    //    decoder is shared with the client, keeping the fixture in lockstep
    //    with the real wire format.
    let mut buf = Vec::with_capacity(64);
    let header = loop {
        match parse_request_header(&buf) {
            Ok(header) => break header,
            Err(ParseRequestError::NeedMoreData) => {}
            Err(error) => return Err(io::Error::new(io::ErrorKind::InvalidData, error)),
        }
        if buf.len() >= MAX_REQUEST_HEADER {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "VLESS request header exceeded bound"));
        }
        let mut chunk = [0_u8; 256];
        let read = tls.read(&mut chunk).await?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "EOF before full VLESS request header"));
        }
        buf.extend_from_slice(&chunk[..read]);
    };

    *observed_target.lock().expect("fixture observation") = Some(header.target.clone());

    // 2. XUDP uses the VLESS Mux command without a destination in the VLESS
    //    header. It is not the Sager/yamux carrier used for TCP streams.
    if header.command == VlessCommand::Mux {
        if !buf[header.consumed_len..].is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "XUDP frame arrived before VLESS response"));
        }
        tls.write_all(&encode_response(&[])?).await?;
        return serve_xudp_carrier(VisionStream::new_vision_tls_only(FixtureRealityTlsStream(tls), header.uuid)).await;
    }

    // 3. A Sager mux carrier has a fixed reserved VLESS destination. It is
    // acknowledged before the SagerNet sing-mux/yamux session is driven.
    if header.target == ripdpi_vless::mux::SING_MUX_DESTINATION {
        tls.write_all(&encode_response(&[])?).await?;
        return serve_yamux_carrier(tls, &buf[header.consumed_len..]).await;
    }

    // 4. Connect to the proxy target FIRST; a failure here closes the
    //    connection without a VLESS response, so the client's `read_response`
    //    surfaces the second-hop failure as a recognizable handshake error
    //    rather than hanging.
    let mut upstream = TcpStream::connect(header.target.as_str()).await?;

    // 5. Acknowledge with the VLESS response header, then splice. Any bytes
    //    already buffered past the header (e.g. the next hop's ClientHello in a
    //    chained connect) are forwarded before the bidirectional copy.
    tls.write_all(&encode_response(&[])?).await?;
    let leftover = &buf[header.consumed_len..];
    if !leftover.is_empty() {
        upstream.write_all(leftover).await?;
    }
    let _ = tokio::io::copy_bidirectional(&mut tls, &mut upstream).await;
    Ok(())
}

struct FixtureRealityTlsStream(SslStream<TcpStream>);

impl AsyncRead for FixtureRealityTlsStream {
    fn poll_read(
        mut self: Pin<&mut Self>,
        context: &mut Context<'_>,
        buffer: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        Pin::new(&mut self.0).poll_read(context, buffer)
    }
}

impl AsyncWrite for FixtureRealityTlsStream {
    fn poll_write(mut self: Pin<&mut Self>, context: &mut Context<'_>, buffer: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.0).poll_write(context, buffer)
    }

    fn poll_flush(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.0).poll_flush(context)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.0).poll_shutdown(context)
    }
}

impl XtlsDirectRead for FixtureRealityTlsStream {
    fn poll_read_direct(
        mut self: Pin<&mut Self>,
        context: &mut Context<'_>,
        buffer: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        Pin::new(self.0.get_mut()).poll_read(context, buffer)
    }
}

impl XtlsDirectWrite for FixtureRealityTlsStream {
    fn poll_write_direct(
        mut self: Pin<&mut Self>,
        context: &mut Context<'_>,
        buffer: &[u8],
    ) -> Poll<io::Result<usize>> {
        Pin::new(self.0.get_mut()).poll_write(context, buffer)
    }

    fn poll_flush_direct(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(self.0.get_mut()).poll_flush(context)
    }

    fn poll_shutdown_direct(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(self.0.get_mut()).poll_shutdown(context)
    }
}

async fn serve_xudp_carrier(mut stream: VisionStream<FixtureRealityTlsStream>) -> io::Result<()> {
    let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let mut response = vec![0_u8; MAX_XUDP_PAYLOAD];
    loop {
        let target = read_xudp_datagram(&mut stream).await?;
        socket.send_to(&target.payload, target.authority.as_str()).await?;
        let (read, source) = socket.recv_from(&mut response).await?;
        write_xudp_datagram(&mut stream, &source.to_string(), &response[..read]).await?;
    }
}

struct FixtureXudpDatagram {
    authority: String,
    payload: Vec<u8>,
}

async fn read_xudp_datagram<S>(stream: &mut S) -> io::Result<FixtureXudpDatagram>
where
    S: tokio::io::AsyncRead + Unpin,
{
    let metadata_len = usize::from(stream.read_u16().await?);
    if !(7..=MAX_XUDP_METADATA).contains(&metadata_len) {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid XUDP metadata length"));
    }
    let mut metadata = vec![0_u8; metadata_len];
    stream.read_exact(&mut metadata).await?;
    if metadata[..2] != [0, 0] || !matches!(metadata[2], 1 | 2) || metadata[3] != 1 || metadata[4] != 2 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported XUDP metadata"));
    }
    let mut cursor = 5;
    let authority = decode_xudp_target(&metadata, &mut cursor)?;
    if metadata[2] == 1 {
        cursor = cursor
            .checked_add(8)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "XUDP metadata overflow"))?;
    }
    if cursor != metadata.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unexpected XUDP metadata suffix"));
    }
    let payload_len = usize::from(stream.read_u16().await?);
    if payload_len == 0 || payload_len > MAX_XUDP_PAYLOAD {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid XUDP payload length"));
    }
    let mut payload = vec![0_u8; payload_len];
    stream.read_exact(&mut payload).await?;
    Ok(FixtureXudpDatagram { authority, payload })
}

fn decode_xudp_target(metadata: &[u8], cursor: &mut usize) -> io::Result<String> {
    let port = u16::from_be_bytes(read_xudp_array::<2>(metadata, cursor)?);
    let address_type = read_xudp_array::<1>(metadata, cursor)?[0];
    let host = match address_type {
        1 => std::net::Ipv4Addr::from(read_xudp_array::<4>(metadata, cursor)?).to_string(),
        2 => {
            let length = usize::from(read_xudp_array::<1>(metadata, cursor)?[0]);
            let bytes = metadata
                .get(*cursor..cursor.saturating_add(length))
                .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "truncated XUDP domain"))?;
            *cursor += length;
            std::str::from_utf8(bytes)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid XUDP domain"))?
                .to_owned()
        }
        3 => std::net::Ipv6Addr::from(read_xudp_array::<16>(metadata, cursor)?).to_string(),
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported XUDP address type")),
    };
    Ok(if host.contains(':') { format!("[{host}]:{port}") } else { format!("{host}:{port}") })
}

fn read_xudp_array<const N: usize>(metadata: &[u8], cursor: &mut usize) -> io::Result<[u8; N]> {
    let end =
        cursor.checked_add(N).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "XUDP metadata overflow"))?;
    let bytes = metadata
        .get(*cursor..end)
        .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "truncated XUDP metadata"))?;
    *cursor = end;
    bytes.try_into().map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "truncated XUDP metadata"))
}

async fn write_xudp_datagram<S>(stream: &mut S, source: &str, payload: &[u8]) -> io::Result<()>
where
    S: tokio::io::AsyncWrite + Unpin,
{
    let source: SocketAddr =
        source.parse().map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid XUDP fixture source"))?;
    let mut metadata = vec![0_u8, 0, 0, 0, 2, 1, 2];
    metadata.extend_from_slice(&source.port().to_be_bytes());
    match source.ip() {
        std::net::IpAddr::V4(address) => {
            metadata.push(1);
            metadata.extend_from_slice(&address.octets());
        }
        std::net::IpAddr::V6(address) => {
            metadata.push(3);
            metadata.extend_from_slice(&address.octets());
        }
    }
    let metadata_len = u16::try_from(metadata.len() - 2)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "XUDP metadata too large"))?;
    metadata[..2].copy_from_slice(&metadata_len.to_be_bytes());
    stream.write_all(&metadata).await?;
    stream
        .write_u16(
            u16::try_from(payload.len())
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "XUDP payload too large"))?,
        )
        .await?;
    stream.write_all(payload).await?;
    stream.flush().await
}

async fn serve_yamux_carrier(mut tls: SslStream<TcpStream>, leftover: &[u8]) -> io::Result<()> {
    if !leftover.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "mux preamble arrived before VLESS response"));
    }
    let mut preamble = [0u8; 2];
    tls.read_exact(&mut preamble).await?;
    if preamble != ripdpi_vless::mux::encode_session_request() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported sing-mux preamble"));
    }
    let mut session = yamux::Connection::new(tls.compat(), yamux::Config::default(), yamux::Mode::Server);
    loop {
        let Some(stream) = poll_fn(|cx| session.poll_next_inbound(cx)).await else {
            return Ok(());
        };
        let stream = stream.map_err(|error| io::Error::new(io::ErrorKind::ConnectionAborted, error.to_string()))?;
        tokio::spawn(async move {
            let _ = handle_yamux_stream(stream).await;
        });
    }
}

async fn handle_yamux_stream(stream: yamux::Stream) -> io::Result<()> {
    let mut stream = stream.compat();
    let mut flags = [0u8; 2];
    stream.read_exact(&mut flags).await?;
    if flags != [0, 0] {
        return Err(io::Error::new(io::ErrorKind::Unsupported, "fixture supports only mux TCP streams"));
    }
    let target = read_socksaddr(&mut stream).await?;
    let mut upstream = TcpStream::connect(target).await?;
    stream.write_all(&[0]).await?;
    stream.flush().await?;
    let _ = tokio::io::copy_bidirectional(&mut stream, &mut upstream).await;
    Ok(())
}

async fn read_socksaddr(stream: &mut tokio_util::compat::Compat<yamux::Stream>) -> io::Result<String> {
    let family = stream.read_u8().await?;
    let host = match family {
        1 => {
            let mut raw = [0u8; 4];
            stream.read_exact(&mut raw).await?;
            std::net::Ipv4Addr::from(raw).to_string()
        }
        3 => {
            let length = usize::from(stream.read_u8().await?);
            let mut raw = vec![0u8; length];
            stream.read_exact(&mut raw).await?;
            String::from_utf8(raw).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid mux hostname"))?
        }
        4 => {
            let mut raw = [0u8; 16];
            stream.read_exact(&mut raw).await?;
            format!("[{}]", std::net::Ipv6Addr::from(raw))
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "unknown mux Socksaddr family")),
    };
    let port = stream.read_u16().await?;
    Ok(format!("{host}:{port}"))
}
