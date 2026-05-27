use std::collections::BTreeMap;
use std::env;
use std::fmt;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use thiserror::Error;

use crate::bridge_line::{parse_bridge_line, BridgeLineError, WebTunnelBridgeConfig};
use crate::client::{connect_webtunnel, WebTunnelStream};

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
    match connect_webtunnel(&bridge, false) {
        Ok(mut webtunnel) => {
            write_socks_reply(&mut stream, 0x00)?;
            relay_socks_over_webtunnel(&mut stream, &mut webtunnel)
        }
        Err(error) => {
            let _ = write_socks_reply(&mut stream, SOCKS_GENERAL_FAILURE);
            Err(io::Error::other(error.to_string()))
        }
    }
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
    stream.write_all(&[SOCKS_VERSION, reply, 0x00, SOCKS_ATYP_IPV4, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])?;
    stream.flush()
}

fn relay_socks_over_webtunnel(client: &mut TcpStream, webtunnel: &mut WebTunnelStream) -> io::Result<()> {
    client.set_read_timeout(Some(Duration::from_millis(100)))?;
    webtunnel.get_mut().set_read_timeout(Some(Duration::from_millis(100)))?;
    let mut client_closed = false;
    let mut bridge_closed = false;
    let mut buffer = [0_u8; 8192];
    while !client_closed || !bridge_closed {
        if !client_closed {
            match client.read(&mut buffer) {
                Ok(0) => client_closed = true,
                Ok(read) => {
                    webtunnel.write_all(&buffer[..read])?;
                    webtunnel.flush()?;
                }
                Err(error) if is_retryable_io(&error) => {}
                Err(error) => return Err(error),
            }
        }
        if !bridge_closed {
            match webtunnel.read(&mut buffer) {
                Ok(0) => bridge_closed = true,
                Ok(read) => {
                    client.write_all(&buffer[..read])?;
                    client.flush()?;
                }
                Err(error) if is_retryable_io(&error) => {}
                Err(error) => return Err(error),
            }
        }
    }
    Ok(())
}

fn is_retryable_io(error: &io::Error) -> bool {
    matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut)
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
