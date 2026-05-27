use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use boring::pkey::{PKey, Private};
use boring::ssl::{SslAcceptor, SslMethod, SslStream};
use boring::x509::X509;
use rcgen::generate_simple_self_signed;

use crate::util;

const IO_TIMEOUT: Duration = Duration::from_secs(5);
const HEADER_LIMIT: usize = 16 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WebTunnelObservedRequest {
    pub path: String,
    pub host: String,
}

pub struct WebTunnelFixture {
    addr: SocketAddr,
    secret_path: String,
    observed_requests: Arc<Mutex<Vec<WebTunnelObservedRequest>>>,
    running: Arc<AtomicBool>,
    handle: Option<JoinHandle<io::Result<()>>>,
}

impl WebTunnelFixture {
    pub fn start(secret_path: &str) -> io::Result<Self> {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))?;
        listener.set_nonblocking(true)?;
        let addr = listener.local_addr()?;
        let acceptor = Arc::new(tls_acceptor()?);
        let observed_requests = Arc::new(Mutex::new(Vec::new()));
        let running = Arc::new(AtomicBool::new(true));
        let handle = {
            let secret_path = secret_path.to_owned();
            let observed_requests = Arc::clone(&observed_requests);
            let running = Arc::clone(&running);
            thread::spawn(move || accept_loop(listener, acceptor, secret_path, observed_requests, running))
        };

        Ok(Self { addr, secret_path: secret_path.to_owned(), observed_requests, running, handle: Some(handle) })
    }

    pub fn addr(&self) -> SocketAddr {
        self.addr
    }

    pub fn url(&self) -> String {
        format!("https://localhost:{}{}", self.addr.port(), self.secret_path)
    }

    pub fn observed_requests(&self) -> Vec<WebTunnelObservedRequest> {
        self.observed_requests.lock().expect("observed requests lock").clone()
    }
}

impl Drop for WebTunnelFixture {
    fn drop(&mut self) {
        self.running.store(false, Ordering::Release);
        util::wake_tcp("127.0.0.1", self.addr.port());
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

fn tls_acceptor() -> io::Result<SslAcceptor> {
    let certificate =
        generate_simple_self_signed(vec!["localhost".to_owned(), "127.0.0.1".to_owned()]).map_err(util::other_io)?;
    let cert = X509::from_der(certificate.cert.der().as_ref()).map_err(util::other_io)?;
    let key: PKey<Private> =
        PKey::private_key_from_der(&certificate.signing_key.serialize_der()).map_err(util::other_io)?;
    let mut acceptor = SslAcceptor::mozilla_intermediate(SslMethod::tls()).map_err(util::other_io)?;
    acceptor.set_certificate(&cert).map_err(util::other_io)?;
    acceptor.set_private_key(&key).map_err(util::other_io)?;
    Ok(acceptor.build())
}

fn accept_loop(
    listener: TcpListener,
    acceptor: Arc<SslAcceptor>,
    secret_path: String,
    observed_requests: Arc<Mutex<Vec<WebTunnelObservedRequest>>>,
    running: Arc<AtomicBool>,
) -> io::Result<()> {
    while running.load(Ordering::Acquire) {
        match listener.accept() {
            Ok((stream, _peer)) => {
                stream.set_nonblocking(false)?;
                let acceptor = Arc::clone(&acceptor);
                let secret_path = secret_path.clone();
                let observed_requests = Arc::clone(&observed_requests);
                thread::spawn(move || {
                    let _ = handle_connection(stream, acceptor, &secret_path, observed_requests);
                });
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(25));
            }
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

fn handle_connection(
    stream: TcpStream,
    acceptor: Arc<SslAcceptor>,
    secret_path: &str,
    observed_requests: Arc<Mutex<Vec<WebTunnelObservedRequest>>>,
) -> io::Result<()> {
    stream.set_read_timeout(Some(IO_TIMEOUT))?;
    stream.set_write_timeout(Some(IO_TIMEOUT))?;
    let mut tls = acceptor.accept(stream).map_err(util::other_io)?;
    let headers = read_headers(&mut tls)?;
    let request = parse_upgrade_request(&headers)?;
    observed_requests.lock().expect("observed requests lock").push(request.clone());
    if request.path != secret_path {
        tls.write_all(b"HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n")?;
        tls.flush()?;
        return Ok(());
    }
    tls.write_all(b"HTTP/1.1 101 Switching Protocols\r\nConnection: upgrade\r\nUpgrade: websocket\r\n\r\n")?;
    tls.flush()?;
    echo_loop(&mut tls)
}

fn read_headers<R: Read>(reader: &mut R) -> io::Result<Vec<u8>> {
    let mut headers = Vec::new();
    let mut byte = [0_u8; 1];
    while headers.len() < HEADER_LIMIT {
        reader.read_exact(&mut byte)?;
        headers.push(byte[0]);
        if headers.ends_with(b"\r\n\r\n") {
            return Ok(headers);
        }
    }
    Err(io::Error::new(io::ErrorKind::InvalidData, "WebTunnel request headers exceed limit"))
}

fn parse_upgrade_request(headers: &[u8]) -> io::Result<WebTunnelObservedRequest> {
    let request = std::str::from_utf8(headers).map_err(util::other_io)?;
    let mut lines = request.split("\r\n");
    let request_line =
        lines.next().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing WebTunnel request line"))?;
    let mut request_parts = request_line.split_whitespace();
    let method = request_parts.next().unwrap_or_default();
    let path = request_parts.next().unwrap_or_default();
    let version = request_parts.next().unwrap_or_default();
    if method != "GET" || version != "HTTP/1.1" {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid WebTunnel upgrade request line"));
    }
    let mut host = None;
    let mut connection_upgrade = false;
    let mut websocket_upgrade = false;
    for line in lines {
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        let name = name.trim();
        let value = value.trim();
        if name.eq_ignore_ascii_case("host") {
            host = Some(value.to_owned());
        }
        if name.eq_ignore_ascii_case("connection")
            && value.split(',').any(|token| token.trim().eq_ignore_ascii_case("upgrade"))
        {
            connection_upgrade = true;
        }
        if name.eq_ignore_ascii_case("upgrade") && value.eq_ignore_ascii_case("websocket") {
            websocket_upgrade = true;
        }
    }
    if !connection_upgrade || !websocket_upgrade {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "missing WebTunnel upgrade headers"));
    }
    Ok(WebTunnelObservedRequest {
        path: path.to_owned(),
        host: host.ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing Host header"))?,
    })
}

fn echo_loop(stream: &mut SslStream<TcpStream>) -> io::Result<()> {
    let mut buffer = [0_u8; 8192];
    loop {
        let read = stream.read(&mut buffer)?;
        if read == 0 {
            return Ok(());
        }
        stream.write_all(&buffer[..read])?;
        stream.flush()?;
    }
}
