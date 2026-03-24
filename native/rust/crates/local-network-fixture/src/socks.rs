use std::io::{self, ErrorKind, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};

use crate::event::{event, EventLog};
use crate::fault::FaultController;
use crate::types::{FixtureConfig, FixtureFaultOutcome, FixtureFaultTarget, IO_POLL_DELAY, IO_TIMEOUT, SOCKS_IO_TIMEOUT};

pub(crate) fn start_socks5_server(
    config: FixtureConfig,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
) -> io::Result<(JoinHandle<()>, u16)> {
    let listener = TcpListener::bind((config.bind_host.as_str(), config.socks5_port))?;
    listener.set_nonblocking(true)?;
    let local_port = listener.local_addr()?.port();
    let udp_socket = UdpSocket::bind((config.bind_host.as_str(), 0))?;
    udp_socket.set_read_timeout(Some(IO_TIMEOUT))?;
    let udp_local = udp_socket.local_addr().ok();
    let udp_shared = Arc::new(udp_socket);
    Ok((
        thread::spawn(move || {
            let udp_worker = {
                let udp_socket = udp_shared.clone();
                let stop = stop.clone();
                let events = events.clone();
                let config = config.clone();
                thread::spawn(move || {
                    let mut frame = [0u8; 65535];
                    while !stop.load(Ordering::Relaxed) {
                        match udp_socket.recv_from(&mut frame) {
                            Ok((size, peer)) => {
                                if let Ok((destination, payload)) = decode_socks5_udp_frame(&frame[..size]) {
                                    let mapped = map_socket_addr(destination, &config);
                                    events.record(event(
                                        "socks5_relay",
                                        "udp",
                                        peer,
                                        udp_local,
                                        &mapped.to_string(),
                                        payload.len(),
                                        None,
                                    ));
                                    if let Ok(forward) = UdpSocket::bind((config.bind_host.as_str(), 0)) {
                                        let _ = forward.set_read_timeout(Some(SOCKS_IO_TIMEOUT));
                                        let _ = forward.send_to(&payload, mapped);
                                        let mut response = [0u8; 4096];
                                        if let Ok((read, from)) = forward.recv_from(&mut response) {
                                            let reply = encode_socks5_udp_frame(from, &response[..read]);
                                            let _ = udp_socket.send_to(&reply, peer);
                                        }
                                    }
                                }
                            }
                            Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                            Err(_) => break,
                        }
                    }
                })
            };

            while !stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, peer)) => {
                        let config = config.clone();
                        let events = events.clone();
                        let udp_shared = udp_shared.clone();
                        let faults = faults.clone();
                        thread::spawn(move || {
                            let local = stream.local_addr().ok();
                            let _ = stream.set_read_timeout(Some(SOCKS_IO_TIMEOUT));
                            let _ = stream.set_write_timeout(Some(SOCKS_IO_TIMEOUT));
                            if let Err(err) = read_socks_greeting(&mut stream) {
                                events.record(event(
                                    "socks5_error",
                                    "tcp",
                                    peer,
                                    local,
                                    &format!("greeting:{err}"),
                                    0,
                                    None,
                                ));
                                return;
                            }
                            if let Err(err) = stream.write_all(&[0x05, 0x00]) {
                                events.record(event(
                                    "socks5_error",
                                    "tcp",
                                    peer,
                                    local,
                                    &format!("greeting_reply:{err}"),
                                    0,
                                    None,
                                ));
                                return;
                            }

                            let mut header = [0u8; 4];
                            if let Err(err) = read_exact_with_retry(&mut stream, &mut header) {
                                events.record(event(
                                    "socks5_error",
                                    "tcp",
                                    peer,
                                    local,
                                    &format!("header:{err}"),
                                    0,
                                    None,
                                ));
                                return;
                            }

                            match header[1] {
                                0x01 => {
                                    let target = match read_socks_target(&mut stream, header[3]) {
                                        Ok(target) => target,
                                        Err(err) => {
                                            events.record(event(
                                                "socks5_error",
                                                "tcp",
                                                peer,
                                                local,
                                                &format!("target:{err}"),
                                                0,
                                                None,
                                            ));
                                            return;
                                        }
                                    };
                                    let mapped =
                                        map_target(target, &config).and_then(|target| resolve_socket_addr(&target));
                                    let Some(mapped) = mapped.ok() else {
                                        events.record(event(
                                            "socks5_error",
                                            "tcp",
                                            peer,
                                            local,
                                            "mapped_target_unavailable",
                                            0,
                                            None,
                                        ));
                                        let _ = stream.write_all(&encode_socks_reply_failure());
                                        return;
                                    };
                                    if let Some(_fault) = faults
                                        .take_matching(FixtureFaultTarget::Socks5Relay, |outcome| {
                                            matches!(outcome, FixtureFaultOutcome::SocksRejectConnect)
                                        })
                                    {
                                        events.record(event(
                                            "socks5_relay",
                                            "tcp",
                                            peer,
                                            local,
                                            &format!("fault:{mapped}"),
                                            0,
                                            None,
                                        ));
                                        let _ = stream.write_all(&encode_socks_reply_failure());
                                        return;
                                    }
                                    events.record(event(
                                        "socks5_relay",
                                        "tcp",
                                        peer,
                                        local,
                                        &mapped.to_string(),
                                        0,
                                        None,
                                    ));
                                    match TcpStream::connect_timeout(&mapped, SOCKS_IO_TIMEOUT) {
                                        Ok(upstream) => {
                                            let _ = stream.write_all(&encode_socks_reply(mapped));
                                            relay_bidirectional(stream, upstream);
                                        }
                                        Err(_) => {
                                            let _ = stream.write_all(&encode_socks_reply_failure());
                                        }
                                    }
                                }
                                0x03 => {
                                    if consume_socks_addr(&mut stream, header[3]).is_err() {
                                        return;
                                    }
                                    events.record(event("socks5_relay", "udp", peer, local, "udp_associate", 0, None));
                                    if let Ok(udp_local) = udp_shared.local_addr() {
                                        let _ = stream.write_all(&encode_socks_reply(udp_local));
                                    }
                                    let mut buf = [0u8; 16];
                                    loop {
                                        match stream.read(&mut buf) {
                                            Ok(0) => break,
                                            Ok(_) => {}
                                            Err(err)
                                                if matches!(
                                                    err.kind(),
                                                    ErrorKind::WouldBlock | ErrorKind::TimedOut
                                                ) => {}
                                            Err(_) => break,
                                        }
                                        thread::sleep(IO_POLL_DELAY);
                                    }
                                }
                                _ => {
                                    let _ = stream.write_all(&encode_socks_reply_failure());
                                }
                            }
                        });
                    }
                    Err(err) if err.kind() == ErrorKind::WouldBlock => thread::sleep(IO_POLL_DELAY),
                    Err(_) => break,
                }
            }

            let _ = udp_worker.join();
        }),
        local_port,
    ))
}

#[derive(Debug, Clone)]
pub(crate) enum SocksTarget {
    Socket(SocketAddr),
    Domain(String, u16),
}

fn read_socks_greeting(stream: &mut TcpStream) -> io::Result<()> {
    let mut header = [0u8; 2];
    read_exact_with_retry(stream, &mut header)?;
    let methods_len = header[1] as usize;
    let mut methods = vec![0u8; methods_len];
    read_exact_with_retry(stream, &mut methods)?;
    Ok(())
}

fn consume_socks_addr(stream: &mut TcpStream, atyp: u8) -> io::Result<()> {
    match atyp {
        0x01 => {
            let mut buf = [0u8; 6];
            read_exact_with_retry(stream, &mut buf)
        }
        0x04 => {
            let mut buf = [0u8; 18];
            read_exact_with_retry(stream, &mut buf)
        }
        0x03 => {
            let mut len = [0u8; 1];
            read_exact_with_retry(stream, &mut len)?;
            let mut buf = vec![0u8; len[0] as usize + 2];
            read_exact_with_retry(stream, &mut buf)
        }
        _ => Err(io::Error::new(ErrorKind::InvalidData, "unsupported atyp")),
    }
}

fn read_socks_target(stream: &mut TcpStream, atyp: u8) -> io::Result<SocksTarget> {
    match atyp {
        0x01 => {
            let mut addr = [0u8; 4];
            let mut port = [0u8; 2];
            read_exact_with_retry(stream, &mut addr)?;
            read_exact_with_retry(stream, &mut port)?;
            Ok(SocksTarget::Socket(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(addr)), u16::from_be_bytes(port))))
        }
        0x03 => {
            let mut len = [0u8; 1];
            read_exact_with_retry(stream, &mut len)?;
            let mut domain = vec![0u8; len[0] as usize];
            let mut port = [0u8; 2];
            read_exact_with_retry(stream, &mut domain)?;
            read_exact_with_retry(stream, &mut port)?;
            Ok(SocksTarget::Domain(String::from_utf8_lossy(&domain).to_string(), u16::from_be_bytes(port)))
        }
        0x04 => {
            let mut addr = [0u8; 16];
            let mut port = [0u8; 2];
            read_exact_with_retry(stream, &mut addr)?;
            read_exact_with_retry(stream, &mut port)?;
            Ok(SocksTarget::Socket(SocketAddr::new(IpAddr::from(addr), u16::from_be_bytes(port))))
        }
        _ => Err(io::Error::new(ErrorKind::InvalidData, "unsupported atyp")),
    }
}

fn read_exact_with_retry(stream: &mut TcpStream, mut buf: &mut [u8]) -> io::Result<()> {
    let deadline = std::time::Instant::now() + SOCKS_IO_TIMEOUT;
    while !buf.is_empty() {
        match stream.read(buf) {
            Ok(0) => return Err(io::Error::new(ErrorKind::UnexpectedEof, "unexpected EOF")),
            Ok(read) => {
                let (_, rest) = buf.split_at_mut(read);
                buf = rest;
            }
            Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                if std::time::Instant::now() >= deadline {
                    return Err(err);
                }
                thread::sleep(IO_POLL_DELAY);
            }
            Err(err) => return Err(err),
        }
    }
    Ok(())
}

pub(crate) fn map_target(target: SocksTarget, config: &FixtureConfig) -> io::Result<SocksTarget> {
    Ok(match target {
        SocksTarget::Socket(address) => SocksTarget::Socket(map_socket_addr(address, config)),
        SocksTarget::Domain(domain, port) => {
            if domain == config.fixture_domain {
                SocksTarget::Socket(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port))
            } else {
                SocksTarget::Domain(domain, port)
            }
        }
    })
}

fn resolve_socket_addr(target: &SocksTarget) -> io::Result<SocketAddr> {
    match target {
        SocksTarget::Socket(address) => Ok(*address),
        SocksTarget::Domain(domain, port) => (domain.as_str(), *port)
            .to_socket_addrs()?
            .next()
            .ok_or_else(|| io::Error::new(ErrorKind::AddrNotAvailable, "no target addr")),
    }
}

pub(crate) fn map_socket_addr(address: SocketAddr, config: &FixtureConfig) -> SocketAddr {
    let fixture_ip =
        config.fixture_ipv4.parse::<IpAddr>().unwrap_or_else(|_| IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)));
    if address.ip() == fixture_ip {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), address.port())
    } else {
        address
    }
}

fn encode_socks_reply(address: SocketAddr) -> Vec<u8> {
    match address {
        SocketAddr::V4(addr) => {
            let mut reply = vec![0x05, 0x00, 0x00, 0x01];
            reply.extend_from_slice(&addr.ip().octets());
            reply.extend_from_slice(&addr.port().to_be_bytes());
            reply
        }
        SocketAddr::V6(addr) => {
            let mut reply = vec![0x05, 0x00, 0x00, 0x04];
            reply.extend_from_slice(&addr.ip().octets());
            reply.extend_from_slice(&addr.port().to_be_bytes());
            reply
        }
    }
}

fn encode_socks_reply_failure() -> Vec<u8> {
    vec![0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0]
}

fn relay_bidirectional(client: TcpStream, upstream: TcpStream) {
    let _ = client.set_read_timeout(None);
    let _ = client.set_write_timeout(None);
    let _ = upstream.set_read_timeout(None);
    let _ = upstream.set_write_timeout(None);
    let mut client_reader = match client.try_clone() {
        Ok(stream) => stream,
        Err(_) => return,
    };
    let mut client_writer = client;
    let mut upstream_reader = match upstream.try_clone() {
        Ok(stream) => stream,
        Err(_) => return,
    };
    let mut upstream_writer = upstream;
    let to_upstream = thread::spawn(move || {
        let _ = io::copy(&mut client_reader, &mut upstream_writer);
    });
    let _ = io::copy(&mut upstream_reader, &mut client_writer);
    let _ = to_upstream.join();
}

pub(crate) fn encode_socks5_udp_frame(destination: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(payload.len() + 22);
    frame.extend_from_slice(&[0x00, 0x00, 0x00]);
    match destination {
        SocketAddr::V4(addr) => {
            frame.push(0x01);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            frame.push(0x04);
            frame.extend_from_slice(&addr.ip().octets());
            frame.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    frame.extend_from_slice(payload);
    frame
}

pub(crate) fn decode_socks5_udp_frame(frame: &[u8]) -> Result<(SocketAddr, Vec<u8>), io::Error> {
    if frame.len() < 10 {
        return Err(io::Error::new(ErrorKind::InvalidData, "SOCKS5 UDP frame too short"));
    }
    match frame[3] {
        0x01 => Ok((
            SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(frame[4], frame[5], frame[6], frame[7])),
                u16::from_be_bytes([frame[8], frame[9]]),
            ),
            frame[10..].to_vec(),
        )),
        0x04 => {
            if frame.len() < 22 {
                return Err(io::Error::new(ErrorKind::InvalidData, "SOCKS5 UDP IPv6 frame too short"));
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&frame[4..20]);
            Ok((SocketAddr::new(IpAddr::from(raw), u16::from_be_bytes([frame[20], frame[21]])), frame[22..].to_vec()))
        }
        atyp => Err(io::Error::new(ErrorKind::InvalidData, format!("SOCKS5 UDP atyp unsupported: {atyp}"))),
    }
}
