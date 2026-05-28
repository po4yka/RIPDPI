use std::io::{self, ErrorKind, Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs, UdpSocket};
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use bytes::Bytes;
use odoh_rs::{
    compose, decrypt_query, encrypt_response, parse, ObliviousDoHKeyPair, ObliviousDoHMessage,
    ObliviousDoHMessagePlaintext,
};
use quinn::crypto::rustls::QuicServerConfig;
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use tokio::runtime::Builder;

use crate::event::{event, EventLog};
use crate::fault::FaultController;
use crate::http::{read_until_marker, start_http_server, HttpResponse};
use crate::types::{
    FixtureFaultOutcome, FixtureFaultTarget, IO_POLL_DELAY, IO_TIMEOUT, ODOH_TARGET_CONFIGS_HEX, SOCKS_IO_TIMEOUT,
};
use crate::util;

mod dnscrypt;

pub(crate) use dnscrypt::start_dns_dnscrypt_server;

pub(crate) fn start_dns_udp_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
    answer_ip: String,
) -> io::Result<(JoinHandle<()>, u16)> {
    let answer_ip =
        Ipv4Addr::from_str(&answer_ip).map_err(|err| io::Error::new(ErrorKind::InvalidInput, err.to_string()))?;
    let socket = UdpSocket::bind((bind_host.as_str(), port))?;
    socket.set_read_timeout(Some(IO_TIMEOUT))?;
    let local_port = socket.local_addr()?.port();
    let local = socket.local_addr().ok();
    Ok((
        thread::spawn(move || {
            let mut buf = [0u8; 512];
            while !stop.load(Ordering::Relaxed) {
                match socket.recv_from(&mut buf) {
                    Ok((read, peer)) => {
                        let query_name = parse_dns_question_name(&buf[..read]).unwrap_or_else(|| "unknown".to_string());
                        events.record(event("dns_udp", "udp", peer, local, &query_name, read, None));
                        if let Some(fault) = faults.take_matching(FixtureFaultTarget::DnsUdp, |outcome| {
                            matches!(
                                outcome,
                                FixtureFaultOutcome::DnsNxDomain
                                    | FixtureFaultOutcome::DnsServFail
                                    | FixtureFaultOutcome::DnsTimeout
                            )
                        }) {
                            events.record(event(
                                "dns_udp",
                                "udp",
                                peer,
                                local,
                                &format!("fault:{:?}", fault.outcome),
                                read,
                                None,
                            ));
                            match fault.outcome {
                                FixtureFaultOutcome::DnsTimeout => continue,
                                FixtureFaultOutcome::DnsNxDomain => {
                                    if let Ok(response) = build_udp_dns_error_response(&buf[..read], 3) {
                                        let _ = socket.send_to(&response, peer);
                                    }
                                }
                                FixtureFaultOutcome::DnsServFail => {
                                    if let Ok(response) = build_udp_dns_error_response(&buf[..read], 2) {
                                        let _ = socket.send_to(&response, peer);
                                    }
                                }
                                _ => {}
                            }
                        } else if let Ok(response) = build_udp_dns_answer(&buf[..read], answer_ip) {
                            let _ = socket.send_to(&response, peer);
                        }
                    }
                    Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                    Err(_) => break,
                }
            }
        }),
        local_port,
    ))
}

pub(crate) fn start_dns_http_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
    answer_ip: String,
) -> io::Result<(JoinHandle<()>, u16)> {
    start_http_server(bind_host, port, stop, events.clone(), move |request, peer, local| {
        let path = request.path.clone();
        let binary_query =
            request.method.eq_ignore_ascii_case("POST") && request.path == "/dns-query" && !request.body.is_empty();
        let query = if binary_query {
            parse_dns_question_name(&request.body).unwrap_or_else(|| "unknown".to_string())
        } else {
            request.query_param("name").unwrap_or_else(|| "unknown".to_string())
        };
        events.record(event("dns_http", "http", peer, local, &format!("{path}?name={query}"), request.raw.len(), None));
        if let Some(fault) = faults.take_matching(FixtureFaultTarget::DnsHttp, |outcome| {
            matches!(
                outcome,
                FixtureFaultOutcome::DnsNxDomain | FixtureFaultOutcome::DnsServFail | FixtureFaultOutcome::DnsTimeout
            )
        }) {
            events.record(event(
                "dns_http",
                "http",
                peer,
                local,
                &format!("fault:{:?}", fault.outcome),
                request.raw.len(),
                None,
            ));
            return match fault.outcome {
                FixtureFaultOutcome::DnsTimeout => {
                    thread::sleep(Duration::from_millis(fault.delay_ms.unwrap_or(1_500)));
                    if binary_query {
                        HttpResponse::dns_message(Vec::new())
                    } else {
                        HttpResponse::json("{}".to_string())
                    }
                }
                FixtureFaultOutcome::DnsNxDomain => {
                    if binary_query {
                        match build_udp_dns_error_response(&request.body, 3) {
                            Ok(body) => HttpResponse::dns_message(body),
                            Err(err) => HttpResponse::bad_request(&err),
                        }
                    } else {
                        HttpResponse::json(r#"{"Status":3,"Answer":[]}"#.to_string())
                    }
                }
                FixtureFaultOutcome::DnsServFail => {
                    if binary_query {
                        match build_udp_dns_error_response(&request.body, 2) {
                            Ok(body) => HttpResponse::dns_message(body),
                            Err(err) => HttpResponse::bad_request(&err),
                        }
                    } else {
                        HttpResponse::json(r#"{"Status":2,"Answer":[]}"#.to_string())
                    }
                }
                _ => HttpResponse::not_found(),
            };
        }
        if binary_query {
            match build_udp_dns_answer(&request.body, answer_ip.parse().unwrap_or(Ipv4Addr::new(198, 18, 0, 10))) {
                Ok(body) => HttpResponse::dns_message(body),
                Err(err) => HttpResponse::bad_request(&err),
            }
        } else {
            let body = format!(r#"{{"Answer":[{{"type":1,"data":"{answer_ip}"}}]}}"#);
            HttpResponse::json(body)
        }
    })
}

pub(crate) fn start_dns_odoh_target_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    answer_ip: String,
) -> io::Result<(JoinHandle<()>, u16)> {
    let answer_ip =
        Ipv4Addr::from_str(&answer_ip).map_err(|err| io::Error::new(ErrorKind::InvalidInput, err.to_string()))?;
    let key_pair = odoh_key_pair();
    start_http_server(bind_host, port, stop, events.clone(), move |request, peer, local| {
        if !request.method.eq_ignore_ascii_case("POST") || request.path != "/dns-query" {
            return HttpResponse::not_found();
        }
        match handle_odoh_target_request(&request.body, &key_pair, answer_ip) {
            Ok((query_name, response)) => {
                events.record(event("dns_odoh_target", "odoh", peer, local, &query_name, request.raw.len(), None));
                HttpResponse::odoh_message(response)
            }
            Err(err) => HttpResponse::bad_request(&err),
        }
    })
}

pub(crate) fn start_dns_odoh_proxy_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
) -> io::Result<(JoinHandle<()>, u16)> {
    start_http_server(bind_host, port, stop, events.clone(), move |request, peer, local| {
        if !request.method.eq_ignore_ascii_case("POST") || request.path != "/proxy" {
            return HttpResponse::not_found();
        }
        let target_host = request.query_param("targethost").unwrap_or_default();
        let target_path = request.query_param("targetpath").unwrap_or_default();
        let content_type = http_header(&request.raw, "content-type").unwrap_or_default();
        events.record(event(
            "dns_odoh_proxy",
            "odoh",
            peer,
            local,
            &format!("targethost={target_host} targetpath={target_path} content-type={content_type}"),
            request.raw.len(),
            None,
        ));
        match forward_odoh_proxy_request(&target_host, &target_path, &request.body) {
            Ok(response) => HttpResponse::odoh_message(response),
            Err(err) => HttpResponse::bad_request(&err),
        }
    })
}

pub(crate) fn start_dns_dot_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
    answer_ip: String,
    server_config: Arc<ServerConfig>,
) -> io::Result<(JoinHandle<()>, u16)> {
    let answer_ip =
        Ipv4Addr::from_str(&answer_ip).map_err(|err| io::Error::new(ErrorKind::InvalidInput, err.to_string()))?;
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
                        let config = server_config.clone();
                        thread::spawn(move || {
                            let _ = stream.set_nonblocking(false);
                            let _ = stream.set_read_timeout(Some(SOCKS_IO_TIMEOUT));
                            let _ = stream.set_write_timeout(Some(SOCKS_IO_TIMEOUT));
                            let local = stream.local_addr().ok();
                            events.record(event("dns_dot", "dot", peer, local, "accept", 0, None));
                            if let Some(_fault) = faults.take_matching(FixtureFaultTarget::DnsDot, |outcome| {
                                matches!(outcome, FixtureFaultOutcome::TlsAbort)
                            }) {
                                events.record(event("dns_dot", "dot", peer, local, "fault:tls_abort", 0, None));
                                let _ = stream.shutdown(Shutdown::Both);
                                return;
                            }

                            let Ok(mut connection) = ServerConnection::new(config) else {
                                return;
                            };
                            while connection.is_handshaking() {
                                if let Err(err) = connection.complete_io(&mut stream) {
                                    events.record(event(
                                        "dns_dot",
                                        "dot",
                                        peer,
                                        local,
                                        &format!("handshake_error:{err}"),
                                        0,
                                        None,
                                    ));
                                    return;
                                }
                            }
                            let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
                            let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
                            let sni = connection.server_name().map(ToOwned::to_owned);
                            let mut tls = StreamOwned::new(connection, stream);
                            loop {
                                let query = match read_length_prefixed_frame(&mut tls) {
                                    Ok(query) => query,
                                    Err(err)
                                        if matches!(
                                            err.kind(),
                                            ErrorKind::UnexpectedEof
                                                | ErrorKind::ConnectionReset
                                                | ErrorKind::ConnectionAborted
                                                | ErrorKind::BrokenPipe
                                        ) =>
                                    {
                                        return;
                                    }
                                    Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                                        continue;
                                    }
                                    Err(_) => return,
                                };
                                let _ = handle_streaming_dns_request(
                                    "dns_dot",
                                    "dot",
                                    FixtureFaultTarget::DnsDot,
                                    peer,
                                    local,
                                    &query,
                                    sni.clone(),
                                    &events,
                                    &faults,
                                    answer_ip,
                                    |response| write_length_prefixed_frame(&mut tls, response),
                                );
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

pub(crate) fn start_dns_doq_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
    answer_ip: String,
    server_config: Arc<ServerConfig>,
) -> io::Result<(JoinHandle<()>, u16)> {
    let answer_ip =
        Ipv4Addr::from_str(&answer_ip).map_err(|err| io::Error::new(ErrorKind::InvalidInput, err.to_string()))?;
    let (port_tx, port_rx) = std::sync::mpsc::sync_channel(1);

    let handle = thread::spawn(move || {
        let port_tx_for_runtime = port_tx.clone();
        let runtime = match Builder::new_current_thread().enable_all().build() {
            Ok(runtime) => runtime,
            Err(err) => {
                let _ = port_tx.send(Err(io::Error::other(err.to_string())));
                return;
            }
        };
        let result: io::Result<()> = runtime.block_on(async move {
            let bind_addr = resolve_bind_addr(&bind_host, port)?;
            let mut doq_tls = (*server_config).clone();
            doq_tls.alpn_protocols = vec![b"doq".to_vec()];
            let mut quinn_server_config = quinn::ServerConfig::with_crypto(Arc::new(
                QuicServerConfig::try_from(doq_tls).map_err(util::other_io)?,
            ));
            let transport_config = Arc::get_mut(&mut quinn_server_config.transport).expect("fixture transport config");
            transport_config.max_concurrent_uni_streams(0u8.into());
            let endpoint = quinn::Endpoint::server(quinn_server_config, bind_addr).map_err(util::other_io)?;
            let local_port = endpoint.local_addr().map_err(util::other_io)?.port();
            let _ = port_tx_for_runtime.send(Ok(local_port));

            {
                let local = endpoint.local_addr().ok();
                loop {
                    if stop.load(Ordering::Relaxed) {
                        endpoint.close(0u32.into(), b"fixture stop");
                        break;
                    }

                    tokio::select! {
                        incoming = endpoint.accept() => {
                            let Some(incoming) = incoming else {
                                break;
                            };
                            let events = events.clone();
                            let faults = faults.clone();
                            tokio::spawn(async move {
                                handle_doq_connection(incoming, local, events, faults, answer_ip).await;
                            });
                        }
                        _ = tokio::time::sleep(Duration::from_millis(50)) => {}
                    }
                }
            }

            Ok(())
        });

        if let Err(err) = result {
            let _ = port_tx.send(Err(err));
        }
    });

    let local_port =
        port_rx.recv().map_err(|_| io::Error::new(ErrorKind::BrokenPipe, "fixture doq startup channel closed"))??;
    Ok((handle, local_port))
}

pub(crate) fn build_udp_dns_answer(request: &[u8], answer_ip: Ipv4Addr) -> Result<Vec<u8>, String> {
    if request.len() < 12 {
        return Err("short request".to_string());
    }
    let mut answer = Vec::new();
    answer.extend(&request[0..2]);
    answer.extend(0x8180u16.to_be_bytes());
    answer.extend(1u16.to_be_bytes());
    answer.extend(1u16.to_be_bytes());
    answer.extend(0u16.to_be_bytes());
    answer.extend(0u16.to_be_bytes());
    answer.extend(&request[12..]);
    answer.extend([0xc0, 0x0c]);
    answer.extend(1u16.to_be_bytes());
    answer.extend(1u16.to_be_bytes());
    answer.extend(60u32.to_be_bytes());
    answer.extend(4u16.to_be_bytes());
    answer.extend(answer_ip.octets());
    Ok(answer)
}

pub(crate) fn build_udp_dns_error_response(request: &[u8], rcode: u16) -> Result<Vec<u8>, String> {
    if request.len() < 12 {
        return Err("short request".to_string());
    }
    let mut answer = Vec::new();
    answer.extend(&request[0..2]);
    answer.extend((0x8180u16 | (rcode & 0x000f)).to_be_bytes());
    answer.extend(1u16.to_be_bytes());
    answer.extend(0u16.to_be_bytes());
    answer.extend(0u16.to_be_bytes());
    answer.extend(0u16.to_be_bytes());
    answer.extend(&request[12..]);
    Ok(answer)
}

fn handle_odoh_target_request(
    request_body: &[u8],
    key_pair: &ObliviousDoHKeyPair,
    answer_ip: Ipv4Addr,
) -> Result<(String, Vec<u8>), String> {
    let mut request_bytes: Bytes = request_body.to_vec().into();
    let query: ObliviousDoHMessage = parse(&mut request_bytes).map_err(|err| err.to_string())?;
    let (plain_query, response_secret) = decrypt_query(&query, key_pair).map_err(|err| err.to_string())?;
    let query_bytes = plain_query.clone().into_msg().to_vec();
    let query_name = parse_dns_question_name(&query_bytes).unwrap_or_else(|| "unknown".to_string());
    let response_bytes = build_udp_dns_answer(&query_bytes, answer_ip)?;
    let response_plaintext = ObliviousDoHMessagePlaintext::new(response_bytes, 0);
    let response =
        encrypt_response(&plain_query, &response_plaintext, response_secret, [0; 16]).map_err(|err| err.to_string())?;
    let wire = compose(&response).map_err(|err| err.to_string())?;
    Ok((query_name, wire.to_vec()))
}

fn forward_odoh_proxy_request(target_host: &str, target_path: &str, body: &[u8]) -> Result<Vec<u8>, String> {
    if target_host.is_empty() || target_path.is_empty() {
        return Err("missing ODoH targethost or targetpath".to_string());
    }
    let mut stream = TcpStream::connect(target_host).map_err(|err| err.to_string())?;
    stream.set_read_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    stream.set_write_timeout(Some(IO_TIMEOUT)).map_err(|err| err.to_string())?;
    let request = format!(
        "POST {target_path} HTTP/1.1\r\nHost: {target_host}\r\nContent-Type: application/oblivious-dns-message\r\nAccept: application/oblivious-dns-message\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    stream.write_all(request.as_bytes()).map_err(|err| err.to_string())?;
    stream.write_all(body).map_err(|err| err.to_string())?;
    stream.flush().map_err(|err| err.to_string())?;
    let headers = read_until_marker(&mut stream, b"\r\n\r\n");
    let headers_text = String::from_utf8_lossy(&headers);
    if !headers_text.starts_with("HTTP/1.1 200") {
        return Err(format!("ODoH target returned {}", headers_text.lines().next().unwrap_or("invalid response")));
    }
    let content_length = headers_text
        .lines()
        .find_map(|line| {
            let (name, value) = line.split_once(':')?;
            name.eq_ignore_ascii_case("content-length").then(|| value.trim().parse::<usize>().ok()).flatten()
        })
        .ok_or_else(|| "ODoH target response missing Content-Length".to_string())?;
    let mut response = vec![0u8; content_length];
    stream.read_exact(&mut response).map_err(|err| err.to_string())?;
    Ok(response)
}

fn http_header(raw_request: &[u8], name: &str) -> Option<String> {
    let request = String::from_utf8_lossy(raw_request);
    request.lines().find_map(|line| {
        let (header, value) = line.split_once(':')?;
        header.eq_ignore_ascii_case(name).then(|| value.trim().to_string())
    })
}

fn odoh_key_pair() -> ObliviousDoHKeyPair {
    const KEM_ID: u16 = 32;
    const KDF_ID: u16 = 1;
    const AEAD_ID: u16 = 1;
    const PUBLIC_KEY_SEED: &str = "c9d84d04e6369fccb8a4d5a264001491221f1b97d9b80dd32c35834bb4462383";
    let seed = hex::decode(PUBLIC_KEY_SEED).expect("ODoH key seed hex");
    let configs = hex::decode(ODOH_TARGET_CONFIGS_HEX).expect("ODoH target config hex");
    debug_assert!(!configs.is_empty());
    ObliviousDoHKeyPair::from_parameters(KEM_ID, KDF_ID, AEAD_ID, &seed)
}

pub(crate) fn parse_dns_question_name(request: &[u8]) -> Option<String> {
    let mut cursor = 12usize;
    let mut labels = Vec::new();
    while cursor < request.len() {
        let len = request[cursor] as usize;
        cursor += 1;
        if len == 0 {
            break;
        }
        if cursor + len > request.len() {
            return None;
        }
        labels.push(String::from_utf8_lossy(&request[cursor..cursor + len]).to_string());
        cursor += len;
    }
    (!labels.is_empty()).then(|| labels.join("."))
}

#[allow(clippy::too_many_arguments)]
fn handle_streaming_dns_request<W>(
    service: &str,
    protocol: &str,
    target: FixtureFaultTarget,
    peer: SocketAddr,
    local: Option<SocketAddr>,
    query: &[u8],
    sni: Option<String>,
    events: &EventLog,
    faults: &FaultController,
    answer_ip: Ipv4Addr,
    mut write_response: W,
) -> io::Result<()>
where
    W: FnMut(&[u8]) -> io::Result<()>,
{
    let query_name = parse_dns_question_name(query).unwrap_or_else(|| "unknown".to_string());
    events.record(event(service, protocol, peer, local, &query_name, query.len(), sni.clone()));
    if let Some(fault) = faults.take_matching(target, |outcome| {
        matches!(
            outcome,
            FixtureFaultOutcome::DnsNxDomain
                | FixtureFaultOutcome::DnsServFail
                | FixtureFaultOutcome::DnsTimeout
                | FixtureFaultOutcome::TlsAbort
        )
    }) {
        events.record(event(service, protocol, peer, local, &format!("fault:{:?}", fault.outcome), query.len(), sni));
        match fault.outcome {
            FixtureFaultOutcome::DnsTimeout => {
                thread::sleep(Duration::from_millis(fault.delay_ms.unwrap_or(1_500)));
                Ok(())
            }
            FixtureFaultOutcome::DnsNxDomain => {
                let response = build_udp_dns_error_response(query, 3).map_err(io::Error::other)?;
                write_response(&response)
            }
            FixtureFaultOutcome::DnsServFail => {
                let response = build_udp_dns_error_response(query, 2).map_err(io::Error::other)?;
                write_response(&response)
            }
            FixtureFaultOutcome::TlsAbort => Ok(()),
            _ => Ok(()),
        }
    } else {
        let response = build_udp_dns_answer(query, answer_ip).map_err(io::Error::other)?;
        write_response(&response)
    }
}

async fn handle_doq_connection(
    incoming: quinn::Incoming,
    local: Option<SocketAddr>,
    events: EventLog,
    faults: FaultController,
    answer_ip: Ipv4Addr,
) {
    let Ok(connection) = incoming.await else {
        return;
    };
    let peer = connection.remote_address();
    loop {
        let (mut send, mut recv) = match connection.accept_bi().await {
            Ok(stream) => stream,
            Err(quinn::ConnectionError::ApplicationClosed { .. }) => return,
            Err(_) => return,
        };

        let mut len_buf = [0u8; 2];
        if recv.read_exact(&mut len_buf).await.is_err() {
            return;
        }
        let response = async {
            let request_len = u16::from_be_bytes(len_buf) as usize;
            let mut query = vec![0u8; request_len];
            recv.read_exact(&mut query).await.map_err(util::other_io)?;
            let query_name = parse_dns_question_name(&query).unwrap_or_else(|| "unknown".to_string());
            events.record(event("dns_doq", "doq", peer, local, &query_name, query.len(), None));

            if let Some(fault) = faults.take_matching(FixtureFaultTarget::DnsDoq, |outcome| {
                matches!(
                    outcome,
                    FixtureFaultOutcome::DnsNxDomain
                        | FixtureFaultOutcome::DnsServFail
                        | FixtureFaultOutcome::DnsTimeout
                        | FixtureFaultOutcome::TlsAbort
                )
            }) {
                events.record(event(
                    "dns_doq",
                    "doq",
                    peer,
                    local,
                    &format!("fault:{:?}", fault.outcome),
                    query.len(),
                    None,
                ));
                match fault.outcome {
                    FixtureFaultOutcome::DnsTimeout => {
                        tokio::time::sleep(Duration::from_millis(fault.delay_ms.unwrap_or(1_500))).await;
                        return Ok::<(), io::Error>(());
                    }
                    FixtureFaultOutcome::DnsNxDomain => {
                        let response = build_udp_dns_error_response(&query, 3).map_err(io::Error::other)?;
                        send_dns_over_quic_response(&mut send, &response).await?;
                        return Ok(());
                    }
                    FixtureFaultOutcome::DnsServFail => {
                        let response = build_udp_dns_error_response(&query, 2).map_err(io::Error::other)?;
                        send_dns_over_quic_response(&mut send, &response).await?;
                        return Ok(());
                    }
                    FixtureFaultOutcome::TlsAbort => {
                        connection.close(0u32.into(), b"tls_abort");
                        return Ok(());
                    }
                    _ => {}
                }
            }

            let response = build_udp_dns_answer(&query, answer_ip).map_err(io::Error::other)?;
            send_dns_over_quic_response(&mut send, &response).await
        }
        .await;

        if response.is_err() {
            return;
        }
    }
}

async fn send_dns_over_quic_response(send: &mut quinn::SendStream, response: &[u8]) -> io::Result<()> {
    let len_prefix = (response.len() as u16).to_be_bytes();
    send.write_all(&len_prefix).await.map_err(util::other_io)?;
    send.write_all(response).await.map_err(util::other_io)?;
    send.finish().map_err(util::other_io)
}

fn resolve_bind_addr(bind_host: &str, port: u16) -> io::Result<SocketAddr> {
    (bind_host, port)
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| io::Error::new(ErrorKind::AddrNotAvailable, "fixture bind address did not resolve"))
}

fn read_length_prefixed_frame(reader: &mut impl Read) -> io::Result<Vec<u8>> {
    let mut len_buf = [0u8; 2];
    reader.read_exact(&mut len_buf)?;
    let size = u16::from_be_bytes(len_buf) as usize;
    if size == 0 {
        return Err(io::Error::new(ErrorKind::InvalidData, "zero-length dns frame"));
    }
    let mut frame = vec![0u8; size];
    reader.read_exact(&mut frame)?;
    Ok(frame)
}

fn write_length_prefixed_frame(writer: &mut impl Write, body: &[u8]) -> io::Result<()> {
    writer.write_all(&(body.len() as u16).to_be_bytes())?;
    writer.write_all(body)?;
    writer.flush()
}
