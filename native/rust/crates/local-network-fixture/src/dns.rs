use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, UdpSocket};
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use crate::event::{event, EventLog};
use crate::fault::FaultController;
use crate::http::{HttpResponse, start_http_server};
use crate::types::{FixtureFaultOutcome, FixtureFaultTarget, IO_TIMEOUT};

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
