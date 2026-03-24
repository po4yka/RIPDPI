use std::io::{self, ErrorKind, Read, Write};
use std::net::{Shutdown, TcpListener, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use rustls::{ServerConfig, ServerConnection, StreamOwned};

use crate::event::{event, EventLog};
use crate::fault::FaultController;
use crate::types::{FixtureFaultOutcome, FixtureFaultTarget, IO_POLL_DELAY, IO_TIMEOUT};

pub(crate) fn start_tcp_echo_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
) -> io::Result<(JoinHandle<()>, u16)> {
    let listener = TcpListener::bind((bind_host.as_str(), port))?;
    listener.set_nonblocking(true)?;
    let local_port = listener.local_addr()?.port();
    Ok((
        thread::spawn(move || {
            while !stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, peer)) => {
                        let events = events.clone();
                        let faults = faults.clone();
                        thread::spawn(move || {
                            if let Some(fault) = faults.take_matching(FixtureFaultTarget::TcpEcho, |outcome| {
                                matches!(outcome, FixtureFaultOutcome::TcpReset | FixtureFaultOutcome::TcpTruncate)
                            }) {
                                events.record(event(
                                    "tcp_echo",
                                    "tcp",
                                    peer,
                                    stream.local_addr().ok(),
                                    &format!("fault:{:?}", fault.outcome),
                                    0,
                                    None,
                                ));
                                match fault.outcome {
                                    FixtureFaultOutcome::TcpReset => {
                                        let _ = stream.shutdown(Shutdown::Both);
                                        return;
                                    }
                                    FixtureFaultOutcome::TcpTruncate => {
                                        let mut buf = [0u8; 4096];
                                        if let Ok(read) = stream.read(&mut buf) {
                                            let truncated = read.min(4);
                                            let _ = stream.write_all(&buf[..truncated]);
                                            let _ = stream.flush();
                                        }
                                        let _ = stream.shutdown(Shutdown::Both);
                                        return;
                                    }
                                    _ => {}
                                }
                            }
                            let mut buf = [0u8; 4096];
                            loop {
                                match stream.read(&mut buf) {
                                    Ok(0) => return,
                                    Ok(read) => {
                                        events.record(event(
                                            "tcp_echo",
                                            "tcp",
                                            peer,
                                            stream.local_addr().ok(),
                                            "echo",
                                            read,
                                            None,
                                        ));
                                        if stream.write_all(&buf[..read]).is_err() {
                                            return;
                                        }
                                    }
                                    Err(err) if err.kind() == ErrorKind::Interrupted => {}
                                    Err(_) => return,
                                }
                            }
                        });
                    }
                    Err(err) if err.kind() == ErrorKind::WouldBlock => thread::sleep(IO_POLL_DELAY),
                    Err(_) => break,
                }
            }
        }),
        local_port,
    ))
}

pub(crate) fn start_udp_echo_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
) -> io::Result<(JoinHandle<()>, u16)> {
    let socket = UdpSocket::bind((bind_host.as_str(), port))?;
    socket.set_read_timeout(Some(IO_TIMEOUT))?;
    let local_port = socket.local_addr()?.port();
    let local = socket.local_addr().ok();
    Ok((
        thread::spawn(move || {
            let mut buf = [0u8; 4096];
            while !stop.load(Ordering::Relaxed) {
                match socket.recv_from(&mut buf) {
                    Ok((read, peer)) => {
                        if let Some(fault) = faults.take_matching(FixtureFaultTarget::UdpEcho, |outcome| {
                            matches!(outcome, FixtureFaultOutcome::UdpDrop | FixtureFaultOutcome::UdpDelay)
                        }) {
                            events.record(event(
                                "udp_echo",
                                "udp",
                                peer,
                                local,
                                &format!("fault:{:?}", fault.outcome),
                                read,
                                None,
                            ));
                            match fault.outcome {
                                FixtureFaultOutcome::UdpDrop => continue,
                                FixtureFaultOutcome::UdpDelay => {
                                    thread::sleep(Duration::from_millis(fault.delay_ms.unwrap_or(1_500)));
                                }
                                _ => {}
                            }
                        }
                        events.record(event("udp_echo", "udp", peer, local, "echo", read, None));
                        let _ = socket.send_to(&buf[..read], peer);
                    }
                    Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                    Err(_) => break,
                }
            }
        }),
        local_port,
    ))
}

pub(crate) fn start_tls_echo_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
    server_config: Arc<ServerConfig>,
) -> io::Result<(JoinHandle<()>, u16)> {
    let listener = TcpListener::bind((bind_host.as_str(), port))?;
    listener.set_nonblocking(true)?;
    let local_port = listener.local_addr()?.port();
    Ok((
        thread::spawn(move || {
            while !stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, peer)) => {
                        let config = server_config.clone();
                        let events = events.clone();
                        let faults = faults.clone();
                        thread::spawn(move || {
                            let _ = stream.set_nonblocking(false);
                            let local = stream.local_addr().ok();
                            events.record(event("tls_echo", "tls", peer, local, "accept", 0, None));
                            if let Some(_fault) = faults.take_matching(FixtureFaultTarget::TlsEcho, |outcome| {
                                matches!(outcome, FixtureFaultOutcome::TlsAbort)
                            }) {
                                events.record(event("tls_echo", "tls", peer, local, "fault:tls_abort", 0, None));
                                let _ = stream.shutdown(Shutdown::Both);
                                return;
                            }
                            let Ok(mut connection) = ServerConnection::new(config) else {
                                return;
                            };
                            while connection.is_handshaking() {
                                if let Err(err) = connection.complete_io(&mut stream) {
                                    let detail = format!("handshake_error:{err}");
                                    events.record(event("tls_echo", "tls", peer, local, &detail, 0, None));
                                    return;
                                }
                            }
                            let sni = connection.server_name().map(ToOwned::to_owned);
                            let mut tls = StreamOwned::new(connection, stream);
                            events.record(event("tls_echo", "tls", peer, local, "handshake", 0, sni));
                            let body = b"fixture tls ok";
                            let response = format!(
                                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                                body.len(),
                                String::from_utf8_lossy(body)
                            );
                            let _ = tls.write_all(response.as_bytes());
                            let _ = tls.flush();
                            tls.conn.send_close_notify();
                            let _ = tls.flush();
                        });
                    }
                    Err(err) if err.kind() == ErrorKind::WouldBlock => thread::sleep(IO_POLL_DELAY),
                    Err(_) => break,
                }
            }
        }),
        local_port,
    ))
}
