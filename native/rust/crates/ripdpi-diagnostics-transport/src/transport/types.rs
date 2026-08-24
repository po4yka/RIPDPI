use std::io::{self, Read, Write};
use std::net::{IpAddr, Shutdown, SocketAddr, TcpStream};

use ripdpi_socks5_core::{SocksError, UdpHeaderError};

use super::address::DnsResolveError;
use rustls::{ClientConnection, StreamOwned};

#[derive(Clone, Debug)]
pub enum TransportConfig {
    Direct { route_experiment: Option<RouteExperimentConfig> },
    Socks5 { host: String, port: u16, credentials: Option<Socks5Credentials> },
}

#[derive(Clone, PartialEq, Eq)]
pub struct Socks5Credentials {
    username: String,
    password: String,
}

impl Socks5Credentials {
    pub fn new(username: impl Into<String>, password: impl Into<String>) -> Option<Self> {
        let username = username.into();
        let password = password.into();
        let valid = !username.is_empty()
            && username.len() <= u8::MAX as usize
            && !password.is_empty()
            && password.len() <= u8::MAX as usize;
        valid.then_some(Self { username, password })
    }

    pub fn username(&self) -> &str {
        &self.username
    }

    pub fn password(&self) -> &str {
        &self.password
    }
}

impl std::fmt::Debug for Socks5Credentials {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("Socks5Credentials(<redacted>)")
    }
}

#[derive(Clone, Debug)]
pub struct RouteExperimentConfig {
    pub stable_flow_attempts: usize,
    pub diversity_buckets: usize,
    pub diversity_on_failure_only: bool,
    pub session_seed: u64,
}

#[derive(Clone, Debug)]
pub struct RouteExperimentReport {
    pub selected_bucket: usize,
    pub selected_bucket_kind: String,
    pub stable_attempts_run: usize,
    pub diversity_attempts_run: usize,
    pub summary: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TargetAddress {
    Host(String),
    Ip(IpAddr),
}

#[derive(Debug)]
pub struct TransportConnectResult {
    pub stream: TcpStream,
    pub connected_addr: Option<SocketAddr>,
    pub local_addr: Option<SocketAddr>,
    pub route_report: Option<RouteExperimentReport>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TransportFailureStage {
    DnsResolution,
    TcpConnect,
    Socks5Negotiation,
}

impl TransportFailureStage {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::DnsResolution => "dns_resolution",
            Self::TcpConnect => "tcp_connect",
            Self::Socks5Negotiation => "socks5_negotiation",
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TransportConnectError {
    pub stage: TransportFailureStage,
    pub message: String,
}

impl TransportConnectError {
    pub fn new(stage: TransportFailureStage, message: impl Into<String>) -> Self {
        Self { stage, message: message.into() }
    }
}

impl std::fmt::Display for TransportConnectError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for TransportConnectError {}

/// Typed error for the probe transport surface.
///
/// Display texts are byte-for-byte compatible with the historical
/// `Result<_, String>` messages so diagnostics summaries and JNI-visible text
/// are unchanged. `RouteExperiment` marks the one remaining legacy seam: the
/// route-experiment tracker formats failures into its report as strings.
#[derive(Debug)]
#[non_exhaustive]
pub enum TransportError {
    Dns(DnsResolveError),
    Io(io::Error),
    Socks(SocksError),
    SocksUdpHeader(UdpHeaderError),
    ScanDeadlineExceeded,
    NoSocketAddrs,
    NoTargetCandidates,
    NoAddresses,
    ConnectRacePanicked,
    ConnectRaceSpawnFailed(io::Error),
    ListenerNotReady(SocketAddr),
    MissingSocketPort,
    MissingSocketHost,
    InvalidSocketHost,
    InvalidSocketPort(std::num::ParseIntError),
    SocksNegotiationTimedOut,
    SocksAuthFailed([u8; 2]),
    SocksUserpassFailed(u8),
    SocksUdpAssociateFailed(u8),
    SocksUdpAssociateAtypUnsupported(u8),
    SocksUdpFrameTooShort,
    SocksUdpFrameTooShortIpv6,
    SocksUdpAtypUnsupported(u8),
    UdpRecvTimeout,
    UdpRecvWouldBlock,
    UdpRecvFailed { kind: io::ErrorKind, source: io::Error },
    ConnectFailed { stage: TransportFailureStage, message: String },
    RouteExperiment(String),
}

impl TransportError {
    pub(crate) fn udp_recv_error(error: io::Error) -> Self {
        match error.kind() {
            io::ErrorKind::TimedOut => Self::UdpRecvTimeout,
            io::ErrorKind::WouldBlock => Self::UdpRecvWouldBlock,
            kind => Self::UdpRecvFailed { kind, source: error },
        }
    }
}

impl std::fmt::Display for TransportError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Dns(error) => write!(formatter, "{error}"),
            Self::Io(error) => write!(formatter, "{error}"),
            Self::Socks(error) => write!(formatter, "{error}"),
            Self::SocksUdpHeader(error) => write!(formatter, "SOCKS5 UDP: {error}"),
            Self::ScanDeadlineExceeded => formatter.write_str("scan_deadline_exceeded"),
            Self::NoSocketAddrs => formatter.write_str("no_socket_addrs"),
            Self::NoTargetCandidates => formatter.write_str("no_target_candidates"),
            Self::NoAddresses => formatter.write_str("no_addresses"),
            Self::ConnectRacePanicked => formatter.write_str("connect_race_panicked"),
            Self::ConnectRaceSpawnFailed(error) => write!(formatter, "connect_race_spawn_failed: {error}"),
            Self::ListenerNotReady(addr) => {
                write!(formatter, "probe runtime listener did not become ready on {addr}")
            }
            Self::MissingSocketPort => formatter.write_str("missing_socket_port"),
            Self::MissingSocketHost => formatter.write_str("missing_socket_host"),
            Self::InvalidSocketHost => formatter.write_str("invalid_socket_host"),
            Self::InvalidSocketPort(error) => write!(formatter, "invalid_socket_port: {error}"),
            Self::SocksNegotiationTimedOut => formatter.write_str("SOCKS5 negotiation timed out"),
            Self::SocksAuthFailed(reply) => write!(formatter, "SOCKS5 auth failed: {reply:?}"),
            Self::SocksUserpassFailed(code) => write!(formatter, "SOCKS5 USERPASS authentication failed: {code}"),
            Self::SocksUdpAssociateFailed(code) => write!(formatter, "SOCKS5 UDP ASSOCIATE failed: {code:x}"),
            Self::SocksUdpAssociateAtypUnsupported(atyp) => {
                write!(formatter, "SOCKS5 UDP ASSOCIATE atyp unsupported: {atyp}")
            }
            Self::SocksUdpFrameTooShort => formatter.write_str("SOCKS5 UDP frame too short"),
            Self::SocksUdpFrameTooShortIpv6 => formatter.write_str("SOCKS5 UDP IPv6 frame too short"),
            Self::SocksUdpAtypUnsupported(atyp) => write!(formatter, "SOCKS5 UDP atyp unsupported: {atyp}"),
            Self::UdpRecvTimeout => formatter.write_str("udp_recv_timeout"),
            Self::UdpRecvWouldBlock => formatter.write_str("udp_recv_would_block"),
            Self::UdpRecvFailed { kind, source } => write!(formatter, "udp_recv_{kind:?}: {source}"),
            Self::ConnectFailed { message, .. } => formatter.write_str(message),
            Self::RouteExperiment(message) => formatter.write_str(message),
        }
    }
}

impl std::error::Error for TransportError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Dns(error) => Some(error),
            Self::Io(error) | Self::ConnectRaceSpawnFailed(error) | Self::UdpRecvFailed { source: error, .. } => {
                Some(error)
            }
            Self::Socks(error) => Some(error),
            Self::SocksUdpHeader(error) => Some(error),
            Self::InvalidSocketPort(error) => Some(error),
            _ => None,
        }
    }
}

impl From<io::Error> for TransportError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

impl From<DnsResolveError> for TransportError {
    fn from(value: DnsResolveError) -> Self {
        Self::Dns(value)
    }
}

impl From<SocksError> for TransportError {
    fn from(value: SocksError) -> Self {
        Self::Socks(value)
    }
}

impl From<UdpHeaderError> for TransportError {
    fn from(value: UdpHeaderError) -> Self {
        Self::SocksUdpHeader(value)
    }
}

#[derive(Debug)]
pub struct UdpRelayResult {
    pub payload: Vec<u8>,
    pub connected_addr: Option<SocketAddr>,
    pub local_addr: Option<SocketAddr>,
    pub route_report: Option<RouteExperimentReport>,
}

#[derive(Debug)]
pub enum ConnectionStream {
    Plain(TcpStream),
    Tls(Box<StreamOwned<ClientConnection, TcpStream>>),
}

impl Read for ConnectionStream {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        match self {
            Self::Plain(stream) => stream.read(buf),
            Self::Tls(stream) => stream.read(buf),
        }
    }
}

impl Write for ConnectionStream {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        match self {
            Self::Plain(stream) => stream.write(buf),
            Self::Tls(stream) => stream.write(buf),
        }
    }

    fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Self::Plain(stream) => stream.flush(),
            Self::Tls(stream) => stream.flush(),
        }
    }
}

impl ConnectionStream {
    pub fn shutdown(&mut self) {
        match self {
            Self::Plain(stream) => {
                let _ = stream.shutdown(Shutdown::Both);
            }
            Self::Tls(stream) => {
                stream.conn.send_close_notify();
                let _ = stream.flush();
                let _ = stream.sock.shutdown(Shutdown::Both);
            }
        }
    }
}
