use std::collections::BTreeMap;
use std::env;
use std::fmt;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

use thiserror::Error;

use crate::bridge_line::{BridgeLineError, WebTunnelBridgeConfig, parse_bridge_line};
use crate::client::connect_webtunnel_async;

const TRANSPORT_NAME: &str = "webtunnel";
const SUPPORTED_MANAGED_VERSION: &str = "1";
const SOCKS_VERSION: u8 = 0x05;
const SOCKS_NO_AUTH: u8 = 0x00;
const SOCKS_USERNAME_PASSWORD: u8 = 0x02;
const SOCKS_NO_ACCEPTABLE_METHODS: u8 = 0xFF;
const SOCKS_CONNECT: u8 = 0x01;
const SOCKS_ATYP_IPV4: u8 = 0x01;
const SOCKS_ATYP_DOMAIN: u8 = 0x03;
const SOCKS_ATYP_IPV6: u8 = 0x04;
const SOCKS_GENERAL_FAILURE: u8 = 0x01;
const RFC1929_VERSION: u8 = 0x01;
const IO_TIMEOUT: Duration = Duration::from_secs(5);
const ACCEPT_POLL: Duration = Duration::from_millis(50);
/// Budget for TCP connect, TLS (with the h2 retry) and the HTTP Upgrade.
const BRIDGE_CONNECT_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManagedClientEnvironment {
    pub state_location: String,
    pub client_transports: Vec<String>,
    pub exit_on_stdin_close: bool,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum PtEnvironmentError {
    #[error("TOR_PT_MANAGED_TRANSPORT_VER is required")]
    MissingManagedTransportVersion,
    #[error("unsupported TOR_PT_MANAGED_TRANSPORT_VER: {0}")]
    UnsupportedManagedTransportVersion(String),
    #[error("TOR_PT_STATE_LOCATION is required")]
    MissingStateLocation,
    #[error("TOR_PT_CLIENT_TRANSPORTS is required")]
    MissingClientTransports,
}

#[derive(Debug, Error)]
pub enum PtRuntimeError {
    #[error(transparent)]
    Environment(#[from] PtEnvironmentError),
    #[error("PROXY-ERROR {0}")]
    Io(#[from] io::Error),
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum PtArgsError {
    #[error("SOCKS auth arguments must be UTF-8: {0}")]
    InvalidUtf8(String),
    #[error("PT argument is malformed: {0}")]
    MalformedArgument(String),
    #[error("PT argument key must not be empty")]
    EmptyKey,
    #[error("PT argument escape is incomplete")]
    IncompleteEscape,
    #[error(transparent)]
    BridgeLine(#[from] BridgeLineError),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SocksConnectRequest {
    pub target_host: String,
    pub target_port: u16,
    pub bridge: Option<WebTunnelBridgeConfig>,
}

impl ManagedClientEnvironment {
    pub fn parse(env: &BTreeMap<String, String>) -> Result<Self, PtEnvironmentError> {
        let version = required_env(env, "TOR_PT_MANAGED_TRANSPORT_VER")?;
        if version != SUPPORTED_MANAGED_VERSION {
            return Err(PtEnvironmentError::UnsupportedManagedTransportVersion(version.to_owned()));
        }
        let state_location = required_env(env, "TOR_PT_STATE_LOCATION")?.to_owned();
        let client_transports = required_env(env, "TOR_PT_CLIENT_TRANSPORTS")?
            .split(',')
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(ToOwned::to_owned)
            .collect::<Vec<_>>();
        if client_transports.is_empty() {
            return Err(PtEnvironmentError::MissingClientTransports);
        }
        let exit_on_stdin_close = env.get("TOR_PT_EXIT_ON_STDIN_CLOSE").is_some_and(|value| value == "1");

        Ok(Self { state_location, client_transports, exit_on_stdin_close })
    }

    fn requests_webtunnel(&self) -> bool {
        self.client_transports.iter().any(|transport| transport == "*" || transport == TRANSPORT_NAME)
    }
}

pub fn pt_client_transcript(config: &ManagedClientEnvironment, listener_addr: SocketAddr) -> Vec<String> {
    let mut lines = vec![format!("VERSION {SUPPORTED_MANAGED_VERSION}")];
    if config.requests_webtunnel() {
        lines.push(format!("CMETHOD {TRANSPORT_NAME} socks5 {listener_addr}"));
    }
    lines.push("CMETHODS DONE".to_owned());
    lines
}

pub fn decode_pt_auth_args(username: &[u8], password: &[u8]) -> Result<String, PtArgsError> {
    let mut bytes = Vec::with_capacity(username.len() + password.len());
    bytes.extend_from_slice(username);
    if password != [0x00] {
        bytes.extend_from_slice(password);
    }
    String::from_utf8(bytes).map_err(|error| PtArgsError::InvalidUtf8(error.to_string()))
}

pub fn parse_client_pt_args(raw_args: &str) -> Result<WebTunnelBridgeConfig, PtArgsError> {
    let options = parse_semicolon_arguments(raw_args)?;
    let mut bridge_line = String::from("Bridge webtunnel 0.0.0.0:1");
    for (key, value) in options {
        bridge_line.push(' ');
        bridge_line.push_str(&key);
        bridge_line.push('=');
        bridge_line.push_str(&value);
    }
    parse_bridge_line(&bridge_line).map_err(PtArgsError::from)
}

pub fn run_managed_client_from_env() -> Result<(), PtRuntimeError> {
    let env = env::vars().collect::<BTreeMap<_, _>>();
    run_managed_client(io::stdin(), io::stdout(), &env)
}

pub fn run_managed_client<R: Read, W: Write>(
    mut stdin: R,
    mut stdout: W,
    env: &BTreeMap<String, String>,
) -> Result<(), PtRuntimeError> {
    let config = ManagedClientEnvironment::parse(env)?;
    let listener = TcpListener::bind("127.0.0.1:0")?;
    listener.set_nonblocking(true)?;
    let listener_addr = listener.local_addr()?;
    for line in pt_client_transcript(&config, listener_addr) {
        writeln!(stdout, "{line}")?;
    }
    stdout.flush()?;

    let running = Arc::new(AtomicBool::new(true));
    let accept_running = Arc::clone(&running);
    let accept_thread = thread::spawn(move || accept_loop(listener, accept_running));
    if config.exit_on_stdin_close {
        let mut discard = [0_u8; 256];
        while stdin.read(&mut discard)? != 0 {}
        running.store(false, Ordering::Release);
    } else {
        loop {
            thread::park_timeout(Duration::from_secs(60));
        }
    }
    accept_thread.join().map_err(|_| io::Error::other("managed client accept thread panicked"))??;
    Ok(())
}

fn required_env<'a>(env: &'a BTreeMap<String, String>, key: &'static str) -> Result<&'a str, PtEnvironmentError> {
    env.get(key).map(String::as_str).filter(|value| !value.is_empty()).ok_or(match key {
        "TOR_PT_MANAGED_TRANSPORT_VER" => PtEnvironmentError::MissingManagedTransportVersion,
        "TOR_PT_STATE_LOCATION" => PtEnvironmentError::MissingStateLocation,
        "TOR_PT_CLIENT_TRANSPORTS" => PtEnvironmentError::MissingClientTransports,
        _ => unreachable!("unknown required PT environment key"),
    })
}

fn parse_semicolon_arguments(raw_args: &str) -> Result<BTreeMap<String, String>, PtArgsError> {
    let mut options = BTreeMap::new();
    for raw_entry in split_escaped(raw_args, ';')? {
        if raw_entry.is_empty() {
            continue;
        }
        let delimiter = raw_entry.find('=').ok_or_else(|| PtArgsError::MalformedArgument(raw_entry.clone()))?;
        let raw_key = &raw_entry[..delimiter];
        if raw_key.is_empty() {
            return Err(PtArgsError::EmptyKey);
        }
        options.insert(raw_key.to_owned(), raw_entry[delimiter + 1..].to_owned());
    }
    Ok(options)
}

fn split_escaped(value: &str, delimiter: char) -> Result<Vec<String>, PtArgsError> {
    let mut entries = Vec::new();
    let mut current = String::new();
    let mut escaped = false;
    for character in value.chars() {
        if escaped {
            current.push(character);
            escaped = false;
            continue;
        }
        match character {
            '\\' => escaped = true,
            character if character == delimiter => {
                entries.push(std::mem::take(&mut current));
            }
            _ => current.push(character),
        }
    }
    if escaped {
        return Err(PtArgsError::IncompleteEscape);
    }
    entries.push(current);
    Ok(entries)
}

fn accept_loop(listener: TcpListener, running: Arc<AtomicBool>) -> io::Result<()> {
    while running.load(Ordering::Acquire) {
        match listener.accept() {
            Ok((stream, _peer)) => {
                thread::spawn(move || {
                    let _ = handle_socks_client(stream);
                });
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                thread::sleep(ACCEPT_POLL);
            }
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

fn handle_socks_client(mut stream: TcpStream) -> io::Result<()> {
    stream.set_read_timeout(Some(IO_TIMEOUT))?;
    stream.set_write_timeout(Some(IO_TIMEOUT))?;
    let request = read_socks5_request(&mut stream)?;
    let Some(bridge) = request.bridge else {
        return write_socks_reply(&mut stream, SOCKS_GENERAL_FAILURE);
    };
    connect_and_relay(stream, &bridge)
}

/// Connects to the bridge, sends the SOCKS reply, and relays until both sides
/// are done.
///
/// The relay runs on a per-connection current-thread runtime so each direction
/// is driven only when it has data (`copy_bidirectional`), and EOF on one side
/// shuts down the write half of the other. A single thread that polled both
/// blocking sockets with read timeouts capped a one-way transfer at one buffer
/// per timeout and never propagated EOF.
fn connect_and_relay(stream: TcpStream, bridge: &WebTunnelBridgeConfig) -> io::Result<()> {
    let runtime = tokio::runtime::Builder::new_current_thread().enable_io().enable_time().build()?;
    runtime.block_on(connect_and_relay_async(stream, bridge))
}

/// # Cancel safety
///
/// cancel-safe: the future owns the app and bridge streams; dropping it
/// closes both, and no state outlives it.
async fn connect_and_relay_async(stream: TcpStream, bridge: &WebTunnelBridgeConfig) -> io::Result<()> {
    use tokio::io::AsyncWriteExt;

    stream.set_nonblocking(true)?;
    let mut client = tokio::net::TcpStream::from_std(stream)?;
    let connected = tokio::time::timeout(BRIDGE_CONNECT_TIMEOUT, connect_webtunnel_async(bridge)).await;
    let mut webtunnel = match connected {
        Ok(Ok(webtunnel)) => webtunnel,
        Ok(Err(error)) => {
            let _ = client.write_all(&socks_reply(SOCKS_GENERAL_FAILURE)).await;
            return Err(io::Error::other(error.to_string()));
        }
        Err(_elapsed) => {
            let _ = client.write_all(&socks_reply(SOCKS_GENERAL_FAILURE)).await;
            return Err(io::Error::new(io::ErrorKind::TimedOut, "WebTunnel bridge connect timed out"));
        }
    };
    client.write_all(&socks_reply(0x00)).await?;
    tokio::io::copy_bidirectional(&mut client, &mut webtunnel).await?;
    Ok(())
}

fn read_socks5_request(stream: &mut TcpStream) -> io::Result<SocksConnectRequest> {
    let version = read_u8(stream)?;
    if version != SOCKS_VERSION {
        return Err(invalid_data(format!("unsupported SOCKS version {version:#x}")));
    }
    let method_count = read_u8(stream)? as usize;
    let mut methods = vec![0_u8; method_count];
    stream.read_exact(&mut methods)?;
    let auth_method = selected_auth_method(&methods);
    stream.write_all(&[SOCKS_VERSION, auth_method])?;
    stream.flush()?;
    if auth_method == SOCKS_NO_ACCEPTABLE_METHODS {
        return Err(invalid_data("client offered no supported SOCKS5 auth method"));
    }

    let bridge = if auth_method == SOCKS_USERNAME_PASSWORD { Some(read_rfc1929_auth(stream)?) } else { None };
    let request_version = read_u8(stream)?;
    if request_version != SOCKS_VERSION {
        return Err(invalid_data(format!("unsupported SOCKS request version {request_version:#x}")));
    }
    let command = read_u8(stream)?;
    let reserved = read_u8(stream)?;
    if command != SOCKS_CONNECT || reserved != 0 {
        return Err(invalid_data("only SOCKS5 CONNECT is supported"));
    }
    let target_host = read_socks_host(stream)?;
    let target_port = read_u16(stream)?;
    Ok(SocksConnectRequest { target_host, target_port, bridge })
}

fn selected_auth_method(methods: &[u8]) -> u8 {
    if methods.contains(&SOCKS_USERNAME_PASSWORD) {
        SOCKS_USERNAME_PASSWORD
    } else if methods.contains(&SOCKS_NO_AUTH) {
        SOCKS_NO_AUTH
    } else {
        SOCKS_NO_ACCEPTABLE_METHODS
    }
}

fn read_rfc1929_auth(stream: &mut TcpStream) -> io::Result<WebTunnelBridgeConfig> {
    let version = read_u8(stream)?;
    if version != RFC1929_VERSION {
        return Err(invalid_data(format!("unsupported RFC1929 version {version:#x}")));
    }
    let username_len = read_u8(stream)? as usize;
    let mut username = vec![0_u8; username_len];
    stream.read_exact(&mut username)?;
    let password_len = read_u8(stream)? as usize;
    let mut password = vec![0_u8; password_len];
    stream.read_exact(&mut password)?;
    let args = decode_pt_auth_args(&username, &password).map_err(invalid_data)?;
    let bridge = parse_client_pt_args(&args).map_err(invalid_data)?;
    stream.write_all(&[RFC1929_VERSION, 0x00])?;
    stream.flush()?;
    Ok(bridge)
}

fn read_socks_host(stream: &mut TcpStream) -> io::Result<String> {
    match read_u8(stream)? {
        SOCKS_ATYP_IPV4 => {
            let mut bytes = [0_u8; 4];
            stream.read_exact(&mut bytes)?;
            Ok(format!("{}.{}.{}.{}", bytes[0], bytes[1], bytes[2], bytes[3]))
        }
        SOCKS_ATYP_DOMAIN => {
            let len = read_u8(stream)? as usize;
            let mut bytes = vec![0_u8; len];
            stream.read_exact(&mut bytes)?;
            String::from_utf8(bytes).map_err(invalid_data)
        }
        SOCKS_ATYP_IPV6 => {
            let mut bytes = [0_u8; 16];
            stream.read_exact(&mut bytes)?;
            Ok(std::net::Ipv6Addr::from(bytes).to_string())
        }
        atyp => Err(invalid_data(format!("unsupported SOCKS address type {atyp:#x}"))),
    }
}

fn write_socks_reply(stream: &mut TcpStream, reply: u8) -> io::Result<()> {
    stream.write_all(&socks_reply(reply))?;
    stream.flush()
}

fn socks_reply(reply: u8) -> [u8; 10] {
    [SOCKS_VERSION, reply, 0x00, SOCKS_ATYP_IPV4, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]
}

fn read_u8(stream: &mut TcpStream) -> io::Result<u8> {
    let mut byte = [0_u8; 1];
    stream.read_exact(&mut byte)?;
    Ok(byte[0])
}

fn read_u16(stream: &mut TcpStream) -> io::Result<u16> {
    let mut bytes = [0_u8; 2];
    stream.read_exact(&mut bytes)?;
    Ok(u16::from_be_bytes(bytes))
}

fn invalid_data(error: impl fmt::Display) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, error.to_string())
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::{Shutdown, TcpListener, TcpStream};
    use std::thread;
    use std::time::{Duration, Instant};

    use boring::asn1::Asn1Time;
    use boring::bn::BigNum;
    use boring::ec::{EcGroup, EcKey};
    use boring::hash::MessageDigest;
    use boring::nid::Nid;
    use boring::pkey::{PKey, Private};
    use boring::ssl::{SslAcceptor, SslMethod};
    use boring::x509::extension::SubjectAlternativeName;
    use boring::x509::{X509, X509NameBuilder};
    use local_network_fixture::WebTunnelFixture;

    use super::connect_and_relay;
    use crate::bridge_line::{WebTunnelBridgeConfig, parse_bridge_line};

    const SOCKS_REPLY_LEN: usize = 10;

    fn bridge_for(addr: std::net::SocketAddr, url: &str, root_pem: &str) -> WebTunnelBridgeConfig {
        let mut bridge = parse_bridge_line(&format!(
            "Bridge webtunnel 192.0.2.3:1 url={url} addr={addr} servername=localhost utls=hellochrome_auto"
        ))
        .expect("bridge line");
        bridge.root_certificate_pem = Some(root_pem.to_owned());
        bridge
    }

    /// Runs `connect_and_relay` for one accepted app connection and returns
    /// the app side after the SOCKS success reply.
    fn relay_client(bridge: WebTunnelBridgeConfig) -> TcpStream {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind relay listener");
        let addr = listener.local_addr().expect("relay addr");
        thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept app connection");
            let _ = connect_and_relay(stream, &bridge);
        });
        let mut client = TcpStream::connect(addr).expect("connect relay");
        client.set_read_timeout(Some(Duration::from_secs(5))).expect("client read timeout");
        let mut reply = [0_u8; SOCKS_REPLY_LEN];
        client.read_exact(&mut reply).expect("read SOCKS reply");
        assert_eq!(reply[1], 0x00, "SOCKS CONNECT must succeed");
        client
    }

    fn self_signed_localhost() -> (X509, PKey<Private>) {
        let group = EcGroup::from_curve_name(Nid::X9_62_PRIME256V1).expect("ec group");
        let key = PKey::from_ec_key(EcKey::generate(&group).expect("ec key")).expect("pkey");
        let mut name = X509NameBuilder::new().expect("name builder");
        name.append_entry_by_nid(Nid::COMMONNAME, "localhost").expect("common name");
        let name = name.build();
        let mut builder = X509::builder().expect("x509 builder");
        builder.set_version(2).expect("version");
        builder
            .set_serial_number(&BigNum::from_u32(1).expect("bn").to_asn1_integer().expect("serial"))
            .expect("serial");
        builder.set_subject_name(&name).expect("subject");
        builder.set_issuer_name(&name).expect("issuer");
        builder.set_pubkey(&key).expect("pubkey");
        builder.set_not_before(&Asn1Time::days_from_now(0).expect("not before")).expect("not before");
        builder.set_not_after(&Asn1Time::days_from_now(1).expect("not after")).expect("not after");
        let san =
            SubjectAlternativeName::new().dns("localhost").build(&builder.x509v3_context(None, None)).expect("san");
        builder.append_extension(&san).expect("append san");
        builder.sign(&key, MessageDigest::sha256()).expect("sign");
        (builder.build(), key)
    }

    /// A bridge that accepts the upgrade, pushes `bytes` downstream, then
    /// closes. The app never writes after its request, as in a download.
    fn start_push_bridge(bytes: usize) -> WebTunnelBridgeConfig {
        let (cert, key) = self_signed_localhost();
        let root_pem = String::from_utf8(cert.to_pem().expect("cert pem")).expect("pem utf8");
        let mut acceptor = SslAcceptor::mozilla_intermediate(SslMethod::tls()).expect("acceptor");
        acceptor.set_certificate(&cert).expect("cert");
        acceptor.set_private_key(&key).expect("key");
        let acceptor = acceptor.build();
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind bridge");
        let addr = listener.local_addr().expect("bridge addr");
        thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept relay");
            let mut tls = acceptor.accept(stream).expect("bridge TLS accept");
            let mut head = Vec::new();
            let mut byte = [0_u8; 1];
            while !head.ends_with(b"\r\n\r\n") {
                tls.read_exact(&mut byte).expect("read upgrade request");
                head.push(byte[0]);
            }
            tls.write_all(b"HTTP/1.1 101 Switching Protocols\r\nConnection: upgrade\r\nUpgrade: websocket\r\n\r\n")
                .expect("write 101");
            let chunk = [0x5a_u8; 16 * 1024];
            let mut left = bytes;
            while left > 0 {
                let len = left.min(chunk.len());
                tls.write_all(&chunk[..len]).expect("push payload");
                left -= len;
            }
            let _ = tls.shutdown();
        });
        bridge_for(addr, &format!("https://localhost:{}/secret", addr.port()), &root_pem)
    }

    #[test]
    fn download_is_not_throttled_by_the_idle_upload_direction() {
        const TOTAL: usize = 4 * 1024 * 1024;
        let mut client = relay_client(start_push_bridge(TOTAL));

        let started = Instant::now();
        let mut received = 0;
        let mut buffer = [0_u8; 64 * 1024];
        while received < TOTAL {
            match client.read(&mut buffer) {
                Ok(0) => break,
                Ok(read) => received += read,
                Err(error) => panic!("download stalled after {received} bytes: {error}"),
            }
            assert!(started.elapsed() < Duration::from_secs(5), "download too slow: {received} bytes");
        }

        assert_eq!(received, TOTAL);
    }

    #[test]
    fn app_eof_reaches_the_bridge_and_bridge_eof_reaches_the_app() {
        let fixture = WebTunnelFixture::start("/secret").expect("start WebTunnel fixture");
        let mut client = relay_client(bridge_for(fixture.addr(), &fixture.url(), fixture.root_certificate_pem()));

        client.write_all(b"ping").expect("write tunnel payload");
        client.shutdown(Shutdown::Write).expect("half-close app side");

        // The echo bridge closes only after it sees EOF, so the app reads
        // the echo and then EOF only if the relay propagated both.
        let mut echoed = Vec::new();
        client.read_to_end(&mut echoed).expect("read echo and EOF");
        assert_eq!(echoed, b"ping");
    }
}
