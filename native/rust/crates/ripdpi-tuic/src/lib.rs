#![forbid(unsafe_code)]

use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, ToSocketAddrs};
use std::pin::Pin;
use std::str::FromStr;
use std::sync::atomic::{AtomicU16, Ordering};
use std::sync::Arc;
use std::sync::Once;
use std::task::{Context, Poll};
use std::time::{Duration, Instant};

use bytes::{BufMut, BytesMut};
use quinn::congestion::{BbrConfig, CubicConfig, NewRenoConfig};
use quinn::{ClientConfig, Endpoint, RecvStream, SendStream, TransportConfig, VarInt};
use rustls::pki_types::CertificateDer;
use rustls::{ClientConfig as RustlsClientConfig, RootCertStore};
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::{mpsc, Mutex};
use tracing::debug;
use uuid::Uuid;

const TUIC_VERSION: u8 = 0x05;
const COMMAND_AUTHENTICATE: u8 = 0x00;
const COMMAND_CONNECT: u8 = 0x01;
const COMMAND_PACKET: u8 = 0x02;
const MAX_CONCURRENT_STREAMS: u32 = 512;
const CLEANUP_INTERVAL: Duration = Duration::from_secs(60);
const REASSEMBLY_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_REASSEMBLY_SLOTS: usize = 128;
static RUSTLS_PROVIDER: Once = Once::new();

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Config {
    pub server: String,
    pub server_port: i32,
    pub server_name: String,
    pub uuid: String,
    pub password: String,
    pub zero_rtt: bool,
    pub congestion_control: String,
    pub udp_enabled: bool,
}

#[derive(Clone)]
pub struct TuicClient {
    inner: Arc<ClientInner>,
}

impl TuicClient {
    pub async fn connect(config: Config) -> io::Result<Self> {
        let tls_config = build_tls_config(config.zero_rtt, None)?;
        Self::connect_with_tls(config, tls_config).await
    }

    async fn connect_with_tls(config: Config, tls_config: RustlsClientConfig) -> io::Result<Self> {
        validate_config(&config)?;
        let server_addr = resolve_server_addr(&config.server, config.server_port)?;
        let endpoint = build_endpoint(&config, tls_config)?;
        let connection = establish_connection(&endpoint, &config, server_addr).await?;
        authenticate_connection(&connection, &config).await?;
        let max_datagram_size = connection.max_datagram_size();

        let inner = Arc::new(ClientInner {
            _endpoint: endpoint,
            connection,
            next_assoc_id: AtomicU16::new(1),
            registrations: Mutex::new(HashMap::new()),
            max_datagram_size,
        });

        if config.udp_enabled && max_datagram_size.is_some() {
            tokio::spawn(dispatch_incoming_datagrams(Arc::clone(&inner)));
        }

        Ok(Self { inner })
    }

    pub async fn tcp_connect(&self, authority: &str) -> io::Result<DuplexStream> {
        let target = TuicAddress::from_authority(authority)?;
        let (mut send, recv) = self.inner.connection.open_bi().await?;
        encode_connect_header(&mut send, &target).await?;
        Ok(DuplexStream { send, recv })
    }

    pub async fn udp_session(&self) -> io::Result<UdpSession> {
        if self.inner.max_datagram_size.is_none() {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "TUIC datagram relay is not available on this connection",
            ));
        }

        let (incoming_tx, incoming_rx) = mpsc::channel(64);
        Ok(UdpSession {
            client: Arc::clone(&self.inner),
            incoming_rx,
            incoming_tx,
            assoc_ids: Mutex::new(HashMap::new()),
            packet_ids: Mutex::new(HashMap::new()),
        })
    }
}

struct ClientInner {
    _endpoint: Endpoint,
    connection: quinn::Connection,
    next_assoc_id: AtomicU16,
    registrations: Mutex<HashMap<u16, mpsc::Sender<UdpPacket>>>,
    max_datagram_size: Option<usize>,
}

pub struct DuplexStream {
    send: SendStream,
    recv: RecvStream,
}

impl AsyncRead for DuplexStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.recv).poll_read(cx, buf)
    }
}

impl AsyncWrite for DuplexStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.send).poll_write(cx, buf).map_err(io::Error::other)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.send).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.send).poll_shutdown(cx)
    }
}

pub struct UdpSession {
    client: Arc<ClientInner>,
    incoming_rx: mpsc::Receiver<UdpPacket>,
    incoming_tx: mpsc::Sender<UdpPacket>,
    assoc_ids: Mutex<HashMap<String, u16>>,
    packet_ids: Mutex<HashMap<u16, u16>>,
}

impl UdpSession {
    pub async fn send_to(&self, address: &str, payload: &[u8]) -> io::Result<()> {
        let target = TuicAddress::from_authority(address)?;
        let assoc_id = self.assoc_id_for(address).await;
        let packet_id = self.next_packet_id(assoc_id).await;
        send_udp_payload(&self.client, assoc_id, packet_id, &target, payload)
    }

    pub async fn recv_from(&mut self) -> io::Result<(String, Vec<u8>)> {
        self.incoming_rx
            .recv()
            .await
            .ok_or_else(|| io::Error::new(io::ErrorKind::BrokenPipe, "TUIC UDP session ended"))
            .map(|packet| (packet.address, packet.payload))
    }

    async fn assoc_id_for(&self, address: &str) -> u16 {
        let mut assoc_ids = self.assoc_ids.lock().await;
        if let Some(existing) = assoc_ids.get(address).copied() {
            return existing;
        }

        let assoc_id = self.client.next_assoc_id.fetch_add(1, Ordering::SeqCst);
        assoc_ids.insert(address.to_owned(), assoc_id);
        self.client.registrations.lock().await.insert(assoc_id, self.incoming_tx.clone());
        assoc_id
    }

    async fn next_packet_id(&self, assoc_id: u16) -> u16 {
        let mut packet_ids = self.packet_ids.lock().await;
        let next = packet_ids.entry(assoc_id).or_insert(0);
        let packet_id = *next;
        *next = next.wrapping_add(1);
        packet_id
    }
}

#[derive(Debug)]
struct UdpPacket {
    address: String,
    payload: Vec<u8>,
}

#[derive(Debug)]
struct PartialPacket {
    started_at: Instant,
    address: Option<TuicAddress>,
    fragments: Vec<Option<Vec<u8>>>,
    received: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum TuicAddress {
    None,
    Domain(String, u16),
    Socket(SocketAddr),
}

impl TuicAddress {
    fn from_authority(authority: &str) -> io::Result<Self> {
        if authority.trim().is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "TUIC authority must not be blank"));
        }
        if let Ok(socket) = SocketAddr::from_str(authority.trim()) {
            return Ok(Self::Socket(socket));
        }

        let (host, port) = split_authority(authority)?;
        let port = port.parse::<u16>().map_err(|error| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid TUIC authority port {port}: {error}"))
        })?;
        if let Ok(ip) = IpAddr::from_str(&host) {
            return Ok(Self::Socket(SocketAddr::new(ip, port)));
        }

        if host.len() > usize::from(u8::MAX) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "TUIC domain authorities are limited to 255 bytes",
            ));
        }

        Ok(Self::Domain(host, port))
    }

    fn to_authority(&self) -> io::Result<String> {
        match self {
            Self::None => {
                Err(io::Error::new(io::ErrorKind::InvalidData, "TUIC packet fragment is missing its address"))
            }
            Self::Domain(host, port) => Ok(format!("{host}:{port}")),
            Self::Socket(socket) => Ok(socket.to_string()),
        }
    }

    fn encoded_len(&self) -> usize {
        1 + match self {
            Self::None => 0,
            Self::Domain(host, _) => 1 + host.len() + 2,
            Self::Socket(SocketAddr::V4(_)) => 4 + 2,
            Self::Socket(SocketAddr::V6(_)) => 16 + 2,
        }
    }

    fn encode(&self, buffer: &mut BytesMut) {
        match self {
            Self::None => buffer.put_u8(0xff),
            Self::Domain(host, port) => {
                buffer.put_u8(0x00);
                buffer.put_u8(host.len() as u8);
                buffer.extend_from_slice(host.as_bytes());
                buffer.put_u16(*port);
            }
            Self::Socket(SocketAddr::V4(address)) => {
                buffer.put_u8(0x01);
                buffer.extend_from_slice(&address.ip().octets());
                buffer.put_u16(address.port());
            }
            Self::Socket(SocketAddr::V6(address)) => {
                buffer.put_u8(0x02);
                for segment in address.ip().segments() {
                    buffer.put_u16(segment);
                }
                buffer.put_u16(address.port());
            }
        }
    }

    fn decode(input: &mut &[u8]) -> io::Result<Self> {
        let Some((&kind, rest)) = input.split_first() else {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "missing TUIC address kind"));
        };
        *input = rest;

        match kind {
            0xff => Ok(Self::None),
            0x00 => {
                let length = read_u8(input)? as usize;
                let host_bytes = take_bytes(input, length)?;
                let port = read_u16(input)?;
                let host = String::from_utf8(host_bytes.to_vec())
                    .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "TUIC domain name is not valid UTF-8"))?;
                Ok(Self::Domain(host, port))
            }
            0x01 => {
                let octets = take_bytes(input, 4)?;
                let port = read_u16(input)?;
                Ok(Self::Socket(SocketAddr::from(([octets[0], octets[1], octets[2], octets[3]], port))))
            }
            0x02 => {
                let octets = take_bytes(input, 16)?;
                let port = read_u16(input)?;
                let ip = [
                    u16::from_be_bytes([octets[0], octets[1]]),
                    u16::from_be_bytes([octets[2], octets[3]]),
                    u16::from_be_bytes([octets[4], octets[5]]),
                    u16::from_be_bytes([octets[6], octets[7]]),
                    u16::from_be_bytes([octets[8], octets[9]]),
                    u16::from_be_bytes([octets[10], octets[11]]),
                    u16::from_be_bytes([octets[12], octets[13]]),
                    u16::from_be_bytes([octets[14], octets[15]]),
                ];
                Ok(Self::Socket(SocketAddr::from((ip, port))))
            }
            _ => Err(io::Error::new(io::ErrorKind::InvalidData, format!("unsupported TUIC address kind {kind:#x}"))),
        }
    }
}

#[derive(Debug, Clone)]
struct PacketHeader {
    assoc_id: u16,
    packet_id: u16,
    fragment_total: u8,
    fragment_id: u8,
    payload_len: u16,
    address: TuicAddress,
}

impl PacketHeader {
    fn encoded_len(&self) -> usize {
        2 + 2 + 1 + 1 + 2 + self.address.encoded_len()
    }

    fn encode(&self, buffer: &mut BytesMut) {
        buffer.put_u8(TUIC_VERSION);
        buffer.put_u8(COMMAND_PACKET);
        buffer.put_u16(self.assoc_id);
        buffer.put_u16(self.packet_id);
        buffer.put_u8(self.fragment_total);
        buffer.put_u8(self.fragment_id);
        buffer.put_u16(self.payload_len);
        self.address.encode(buffer);
    }

    fn decode(datagram: &[u8]) -> io::Result<(Self, &[u8])> {
        let mut input = datagram;
        if read_u8(&mut input)? != TUIC_VERSION {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid TUIC packet version"));
        }
        if read_u8(&mut input)? != COMMAND_PACKET {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid TUIC packet command"));
        }

        let header = Self {
            assoc_id: read_u16(&mut input)?,
            packet_id: read_u16(&mut input)?,
            fragment_total: read_u8(&mut input)?.max(1),
            fragment_id: read_u8(&mut input)?,
            payload_len: read_u16(&mut input)?,
            address: TuicAddress::decode(&mut input)?,
        };
        let payload = take_bytes(&mut input, usize::from(header.payload_len))?;
        Ok((header, payload))
    }
}

async fn authenticate_connection(connection: &quinn::Connection, config: &Config) -> io::Result<()> {
    let uuid = Uuid::parse_str(config.uuid.trim())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid TUIC UUID: {error}")))?;
    let mut token = [0u8; 32];
    connection
        .export_keying_material(&mut token, uuid.as_bytes(), config.password.as_bytes())
        .map_err(|error| io::Error::other(format!("TUIC exporter failed: {error:?}")))?;

    let mut payload = BytesMut::with_capacity(2 + 16 + 32);
    payload.put_u8(TUIC_VERSION);
    payload.put_u8(COMMAND_AUTHENTICATE);
    payload.extend_from_slice(uuid.as_bytes());
    payload.extend_from_slice(&token);

    let mut send = connection.open_uni().await?;
    send.write_all(&payload).await?;
    send.finish()?;
    Ok(())
}

async fn encode_connect_header(send: &mut SendStream, address: &TuicAddress) -> io::Result<()> {
    let mut payload = BytesMut::with_capacity(2 + address.encoded_len());
    payload.put_u8(TUIC_VERSION);
    payload.put_u8(COMMAND_CONNECT);
    address.encode(&mut payload);
    send.write_all(&payload).await.map_err(io::Error::other)
}

async fn dispatch_incoming_datagrams(client: Arc<ClientInner>) {
    let mut partials: HashMap<(u16, u16), PartialPacket> = HashMap::new();
    let mut last_cleanup = Instant::now();

    loop {
        let datagram = match client.connection.read_datagram().await {
            Ok(datagram) => datagram,
            Err(error) => {
                debug!(error = %error, "TUIC datagram dispatcher stopped");
                break;
            }
        };

        if last_cleanup.elapsed() >= CLEANUP_INTERVAL {
            partials.retain(|_, partial| partial.started_at.elapsed() < REASSEMBLY_TIMEOUT);
            while partials.len() > MAX_REASSEMBLY_SLOTS {
                if let Some(oldest_key) =
                    partials.iter().min_by_key(|(_, partial)| partial.started_at).map(|(key, _)| *key)
                {
                    partials.remove(&oldest_key);
                }
            }
            last_cleanup = Instant::now();
        }

        let (header, payload) = match PacketHeader::decode(&datagram) {
            Ok(decoded) => decoded,
            Err(error) => {
                debug!(error = %error, "Ignoring malformed TUIC datagram");
                continue;
            }
        };

        let sender = {
            let registrations = client.registrations.lock().await;
            registrations.get(&header.assoc_id).cloned()
        };
        let Some(sender) = sender else {
            continue;
        };

        if header.fragment_total <= 1 {
            let Ok(address) = header.address.to_authority() else {
                continue;
            };
            let _ = sender.send(UdpPacket { address, payload: payload.to_vec() }).await;
            continue;
        }

        let key = (header.assoc_id, header.packet_id);
        let partial = partials.entry(key).or_insert_with(|| PartialPacket {
            started_at: Instant::now(),
            address: None,
            fragments: vec![None; usize::from(header.fragment_total)],
            received: 0,
        });
        let fragment_index = usize::from(header.fragment_id);
        if fragment_index >= partial.fragments.len() {
            continue;
        }
        if !matches!(header.address, TuicAddress::None) {
            partial.address = Some(header.address.clone());
        }
        if partial.fragments[fragment_index].is_none() {
            partial.fragments[fragment_index] = Some(payload.to_vec());
            partial.received += 1;
        }

        if partial.received == partial.fragments.len() {
            let Some(address) = partial.address.clone() else {
                partials.remove(&key);
                continue;
            };
            let mut assembled = Vec::new();
            for fragment in partial.fragments.iter().flatten() {
                assembled.extend_from_slice(fragment);
            }
            partials.remove(&key);
            let Ok(address) = address.to_authority() else {
                continue;
            };
            let _ = sender.send(UdpPacket { address, payload: assembled }).await;
        }
    }

    client.registrations.lock().await.clear();
}

fn send_udp_payload(
    client: &ClientInner,
    assoc_id: u16,
    packet_id: u16,
    address: &TuicAddress,
    payload: &[u8],
) -> io::Result<()> {
    let max_datagram_size = client
        .max_datagram_size
        .ok_or_else(|| io::Error::new(io::ErrorKind::Unsupported, "TUIC UDP is unavailable"))?;

    let first_header = PacketHeader {
        assoc_id,
        packet_id,
        fragment_total: 1,
        fragment_id: 0,
        payload_len: 0,
        address: address.clone(),
    };
    let next_header = PacketHeader { address: TuicAddress::None, ..first_header.clone() };
    let first_payload_capacity = max_datagram_size.saturating_sub(first_header.encoded_len());
    let next_payload_capacity = max_datagram_size.saturating_sub(next_header.encoded_len());

    if first_payload_capacity == 0 || next_payload_capacity == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "TUIC datagram header exceeds the maximum QUIC datagram size",
        ));
    }

    let fragment_total = if payload.len() <= first_payload_capacity {
        1
    } else {
        let remaining = payload.len() - first_payload_capacity;
        1 + remaining.div_ceil(next_payload_capacity)
    };
    if fragment_total > usize::from(u8::MAX) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "payload requires too many TUIC fragments"));
    }

    let mut written = 0usize;
    for fragment_id in 0..fragment_total {
        let address = if fragment_id == 0 { address.clone() } else { TuicAddress::None };
        let capacity = if fragment_id == 0 { first_payload_capacity } else { next_payload_capacity };
        let end = (written + capacity).min(payload.len());
        let chunk = &payload[written..end];
        let header = PacketHeader {
            assoc_id,
            packet_id,
            fragment_total: fragment_total as u8,
            fragment_id: fragment_id as u8,
            payload_len: chunk.len() as u16,
            address,
        };
        let mut frame = BytesMut::with_capacity(header.encoded_len() + chunk.len());
        header.encode(&mut frame);
        frame.extend_from_slice(chunk);
        client.connection.send_datagram(frame.freeze()).map_err(io::Error::other)?;
        written = end;
    }

    Ok(())
}

fn build_endpoint(config: &Config, tls_config: RustlsClientConfig) -> io::Result<Endpoint> {
    let mut transport = TransportConfig::default();
    transport.max_concurrent_bidi_streams(VarInt::from_u32(MAX_CONCURRENT_STREAMS));
    transport.max_concurrent_uni_streams(VarInt::from_u32(MAX_CONCURRENT_STREAMS));
    transport.max_idle_timeout(None);
    match config.congestion_control.trim().to_ascii_lowercase().as_str() {
        "cubic" => transport.congestion_controller_factory(Arc::new(CubicConfig::default())),
        "new_reno" | "newreno" => transport.congestion_controller_factory(Arc::new(NewRenoConfig::default())),
        _ => transport.congestion_controller_factory(Arc::new(BbrConfig::default())),
    };

    let mut client_config = ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls_config).map_err(io::Error::other)?,
    ));
    client_config.transport_config(Arc::new(transport));

    let mut endpoint = Endpoint::client(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)))?;
    endpoint.set_default_client_config(client_config);
    Ok(endpoint)
}

fn build_tls_config(
    enable_early_data: bool,
    additional_roots: Option<Vec<CertificateDer<'static>>>,
) -> io::Result<RustlsClientConfig> {
    ensure_crypto_provider();
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    if let Some(certificates) = additional_roots {
        for certificate in certificates {
            roots.add(certificate).map_err(io::Error::other)?;
        }
    }

    let mut tls_config = RustlsClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
    tls_config.alpn_protocols = vec![b"h3".to_vec()];
    tls_config.enable_early_data = enable_early_data;
    Ok(tls_config)
}

fn ensure_crypto_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
}

async fn establish_connection(
    endpoint: &Endpoint,
    config: &Config,
    server_addr: SocketAddr,
) -> io::Result<quinn::Connection> {
    let connecting = endpoint.connect(server_addr, &config.server_name).map_err(io::Error::other)?;
    if config.zero_rtt {
        match connecting.into_0rtt() {
            Ok((connection, accepted)) => {
                let _ = accepted.await;
                Ok(connection)
            }
            Err(connecting) => Ok(connecting.await.map_err(io::Error::other)?),
        }
    } else {
        Ok(connecting.await.map_err(io::Error::other)?)
    }
}

fn validate_config(config: &Config) -> io::Result<()> {
    if config.server.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC server"));
    }
    if config.server_port <= 0 || config.server_port > i32::from(u16::MAX) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid TUIC server port"));
    }
    if config.server_name.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC server name"));
    }
    if config.uuid.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC UUID"));
    }
    if config.password.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing TUIC password"));
    }
    Ok(())
}

fn resolve_server_addr(server: &str, port: i32) -> io::Result<SocketAddr> {
    (server, port as u16)
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "unable to resolve TUIC server"))
}

fn split_authority(authority: &str) -> io::Result<(String, String)> {
    let trimmed = authority.trim();
    if trimmed.starts_with('[') {
        let end = trimmed
            .find(']')
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid bracketed authority"))?;
        let host = trimmed[1..end].to_owned();
        let remainder = trimmed[end + 1..]
            .strip_prefix(':')
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid bracketed authority port"))?;
        return Ok((host, remainder.to_owned()));
    }

    trimmed
        .rsplit_once(':')
        .map(|(host, port)| (host.to_owned(), port.to_owned()))
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "authority must include a port"))
}

fn read_u8(input: &mut &[u8]) -> io::Result<u8> {
    let Some((&value, rest)) = input.split_first() else {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected end of TUIC payload"));
    };
    *input = rest;
    Ok(value)
}

fn read_u16(input: &mut &[u8]) -> io::Result<u16> {
    let bytes = take_bytes(input, 2)?;
    Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn take_bytes<'a>(input: &mut &'a [u8], count: usize) -> io::Result<&'a [u8]> {
    if input.len() < count {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected end of TUIC payload"));
    }
    let (head, tail) = input.split_at(count);
    *input = tail;
    Ok(head)
}

#[cfg(test)]
mod tests {
    use super::*;

    use rcgen::generate_simple_self_signed;
    use rustls::pki_types::PrivateKeyDer;
    use rustls::ServerConfig as RustlsServerConfig;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[test]
    fn packet_header_round_trips() {
        let header = PacketHeader {
            assoc_id: 7,
            packet_id: 9,
            fragment_total: 2,
            fragment_id: 1,
            payload_len: 5,
            address: TuicAddress::Domain("relay.example".to_owned(), 443),
        };
        let mut buffer = BytesMut::new();
        header.encode(&mut buffer);
        buffer.extend_from_slice(b"hello");

        let (decoded, payload) = PacketHeader::decode(&buffer).expect("decode");
        assert_eq!(decoded.assoc_id, header.assoc_id);
        assert_eq!(decoded.packet_id, header.packet_id);
        assert_eq!(decoded.fragment_total, header.fragment_total);
        assert_eq!(decoded.fragment_id, header.fragment_id);
        assert_eq!(decoded.address, header.address);
        assert_eq!(payload, b"hello");
    }

    #[tokio::test]
    async fn tuic_client_relays_tcp_and_udp() {
        ensure_crypto_provider();
        let certificate = generate_simple_self_signed(vec!["localhost".to_owned()]).expect("certificate");
        let cert_der = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(certificate.signing_key.serialize_der().into());
        let mut server_tls = RustlsServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert_der.clone()], key_der)
            .expect("server cert");
        server_tls.alpn_protocols = vec![b"h3".to_vec()];
        let server_config = quinn::ServerConfig::with_crypto(Arc::new(
            quinn::crypto::rustls::QuicServerConfig::try_from(server_tls).expect("quic server config"),
        ));
        let server = Endpoint::server(server_config, SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).expect("endpoint");
        let server_addr = server.local_addr().expect("server addr");

        let expected_uuid = Uuid::parse_str("00000000-0000-0000-0000-000000000000").expect("uuid");
        let expected_password = "fixture-pass".to_owned();
        let server_password = expected_password.clone();
        let server_task = tokio::spawn(async move {
            let incoming = server.accept().await.expect("incoming");
            let connection = incoming.await.expect("connection");

            let mut auth_stream = connection.accept_uni().await.expect("auth stream");
            let mut auth_payload = Vec::new();
            auth_stream.read_to_end(1024).await.map(|bytes| auth_payload = bytes).expect("auth read");
            assert_eq!(auth_payload[0], TUIC_VERSION);
            assert_eq!(auth_payload[1], COMMAND_AUTHENTICATE);
            assert_eq!(Uuid::from_slice(&auth_payload[2..18]).expect("uuid"), expected_uuid);
            let mut expected_token = [0u8; 32];
            connection
                .export_keying_material(&mut expected_token, expected_uuid.as_bytes(), server_password.as_bytes())
                .expect("export token");
            assert_eq!(&auth_payload[18..50], expected_token.as_slice());

            let tcp_conn = connection.clone();
            let tcp_task = tokio::spawn(async move {
                let (mut send, mut recv) = tcp_conn.accept_bi().await.expect("tcp stream");
                let mut header = [0u8; 2];
                recv.read_exact(&mut header).await.expect("connect header");
                assert_eq!(header, [TUIC_VERSION, COMMAND_CONNECT]);
                let mut address_payload = [0u8; 16];
                recv.read_exact(&mut address_payload).await.expect("connect target");
                let mut address_input = address_payload.as_slice();
                assert_eq!(
                    TuicAddress::decode(&mut address_input).expect("decode target"),
                    TuicAddress::Domain("echo.example".to_owned(), 443)
                );
                let mut buffer = [0u8; 5];
                recv.read_exact(&mut buffer).await.expect("tcp payload");
                assert_eq!(&buffer, b"hello");
                send.write_all(&buffer).await.expect("tcp echo");
                send.finish().expect("finish");
            });

            let udp_conn = connection.clone();
            let udp_task = tokio::spawn(async move {
                let datagram = udp_conn.read_datagram().await.expect("udp datagram");
                let (header, payload) = PacketHeader::decode(&datagram).expect("decode packet");
                assert_eq!(payload, b"world");
                let response = PacketHeader {
                    assoc_id: header.assoc_id,
                    packet_id: header.packet_id,
                    fragment_total: 1,
                    fragment_id: 0,
                    payload_len: payload.len() as u16,
                    address: header.address,
                };
                let mut frame = BytesMut::with_capacity(response.encoded_len() + payload.len());
                response.encode(&mut frame);
                frame.extend_from_slice(payload);
                udp_conn.send_datagram(frame.freeze()).expect("send datagram");
            });

            let _ = tokio::join!(tcp_task, udp_task);
        });

        let tls_config = build_tls_config(false, Some(vec![cert_der])).expect("tls config");
        let client = TuicClient::connect_with_tls(
            Config {
                server: server_addr.ip().to_string(),
                server_port: i32::from(server_addr.port()),
                server_name: "localhost".to_owned(),
                uuid: expected_uuid.to_string(),
                password: expected_password,
                zero_rtt: false,
                congestion_control: "bbr".to_owned(),
                udp_enabled: true,
            },
            tls_config,
        )
        .await
        .expect("client");

        let mut tcp = client.tcp_connect("echo.example:443").await.expect("tcp connect");
        tcp.write_all(b"hello").await.expect("tcp write");
        let mut echoed = [0u8; 5];
        tcp.read_exact(&mut echoed).await.expect("tcp read");
        assert_eq!(&echoed, b"hello");

        let mut udp = client.udp_session().await.expect("udp session");
        udp.send_to("dns.example:53", b"world").await.expect("udp send");
        let (address, payload) = udp.recv_from().await.expect("udp recv");
        assert_eq!(address, "dns.example:53");
        assert_eq!(payload, b"world");

        server_task.await.expect("server task");
    }
}
