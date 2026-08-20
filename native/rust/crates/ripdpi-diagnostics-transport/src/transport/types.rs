use std::io::{Read, Write};
use std::net::{IpAddr, Shutdown, SocketAddr, TcpStream};

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
