use std::io::{self, ErrorKind, Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use rcgen::generate_simple_self_signed;
use rustls::pki_types::PrivateKeyDer;
use rustls::{ServerConfig, ServerConnection, StreamOwned};

use crate::event::{EventLog, event};
use crate::http::{HttpResponse, read_until_marker};
use crate::types::{FixtureConfig, IO_POLL_DELAY};
use crate::util;
use crate::verified_http3;

pub(crate) struct TlsMaterial {
    pub(crate) certificate_pem: String,
    pub(crate) server_config: Arc<ServerConfig>,
}

pub(crate) fn start_verified_https_server(
    bind_host: &str,
    stop: Arc<AtomicBool>,
    server_config: Arc<ServerConfig>,
    events: EventLog,
    dns_answer: Ipv4Addr,
) -> io::Result<([JoinHandle<()>; 2], u16)> {
    let (listener, socket) = bind_verified_sockets(bind_host)?;
    listener.set_nonblocking(true)?;
    let port = listener.local_addr()?.port();
    let http3_handle = verified_http3::start(socket, stop.clone(), &server_config)?;
    let handle = thread::spawn(move || {
        while !stop.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((socket, _)) => {
                    let config = server_config.clone();
                    let events = events.clone();
                    thread::spawn(move || {
                        let _ = serve_verified_https(socket, config, events, dns_answer);
                    });
                }
                Err(err) if err.kind() == ErrorKind::WouldBlock => thread::sleep(IO_POLL_DELAY),
                Err(_) => break,
            }
        }
    });
    Ok(([handle, http3_handle], port))
}

fn bind_verified_sockets(bind_host: &str) -> io::Result<(TcpListener, UdpSocket)> {
    let mut retries = 7;
    loop {
        let listener = TcpListener::bind((bind_host, 0))?;
        match UdpSocket::bind(listener.local_addr()?) {
            Ok(socket) => return Ok((listener, socket)),
            Err(err) if err.kind() == ErrorKind::AddrInUse && retries > 0 => retries -= 1,
            Err(err) => return Err(err),
        }
    }
}

fn serve_verified_https(
    socket: TcpStream,
    server_config: Arc<ServerConfig>,
    events: EventLog,
    dns_answer: Ipv4Addr,
) -> io::Result<()> {
    let timeout = Some(Duration::from_secs(4));
    socket.set_nonblocking(false)?;
    socket.set_read_timeout(timeout)?;
    socket.set_write_timeout(timeout)?;
    let connection = ServerConnection::new(server_config).map_err(util::other_io)?;
    let mut stream = StreamOwned::new(connection, socket);
    let request = read_until_marker(&mut Read::by_ref(&mut stream).take(2048), b"\r\n\r\n");
    if !request.ends_with(b"\r\n\r\n") {
        return Ok(());
    }

    if request.starts_with(b"GET /verified-short HTTP/1.1\r\n") {
        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")?;
    } else if request.starts_with(b"GET /verified-long HTTP/1.1\r\n") {
        stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nlon")?;
        stream.flush()?;
        // Wait for the peer or the socket timeout before closing the unfinished response.
        let _ = stream.sock.peek(&mut [0u8; 1]);
    } else if request.starts_with(b"GET /dns-query?") {
        let first_line = String::from_utf8_lossy(&request);
        let target = first_line.split_whitespace().nth(1).unwrap_or("/dns-query");
        events.record(event(
            "dns_doh",
            "https",
            stream.sock.peer_addr()?,
            stream.sock.local_addr().ok(),
            target,
            request.len(),
            None,
        ));
        let name = target.split(['?', '&']).find_map(|param| param.strip_prefix("name="));
        let response = if let Some(name) = name.filter(|name| !name.is_empty()) {
            HttpResponse::json(
                serde_json::json!({
                    "Status": 0,
                    "Question": [{ "name": name, "type": 1 }],
                    "Answer": [{ "name": name, "type": 1, "TTL": 60, "data": dns_answer.to_string() }],
                })
                .to_string(),
            )
        } else {
            HttpResponse::bad_request("missing DNS query name")
        };
        stream.write_all(&response.to_bytes())?;
    } else {
        stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")?;
    }

    stream.conn.send_close_notify();
    stream.flush()
}

impl TlsMaterial {
    pub(crate) fn generate(config: &FixtureConfig) -> io::Result<Self> {
        let mut names = vec![config.fixture_domain.clone(), "localhost".to_string(), "127.0.0.1".to_string()];
        if !names.contains(&config.android_host) {
            names.push(config.android_host.clone());
        }
        let certificate = generate_simple_self_signed(names).map_err(util::other_io)?;
        let cert_der = certificate.cert.der().clone();
        let certificate_pem = certificate.cert.pem();
        let key_der = PrivateKeyDer::Pkcs8(certificate.signing_key.serialize_der().into());
        let server_config = Arc::new(
            ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_safe_default_protocol_versions()
                .expect("ring provider supports default TLS versions")
                .with_no_client_auth()
                .with_single_cert(vec![cert_der], key_der)
                .map_err(util::other_io)?,
        );

        Ok(Self { certificate_pem, server_config })
    }
}
