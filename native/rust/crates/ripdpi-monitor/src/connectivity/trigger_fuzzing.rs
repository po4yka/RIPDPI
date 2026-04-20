use std::collections::{BTreeMap, BTreeSet};
use std::io::{ErrorKind, Read, Write};

use ripdpi_packets::{
    change_tls_sni_seeded_like_c, is_tls_server_hello, mod_http_like_c, padencap_tls_like_c,
    remove_tls_key_share_group_like_c, DEFAULT_FAKE_TLS, MH_HMIX, MH_HOSTEXTRASPACE, MH_UNIXEOL,
};

use crate::dns::{build_dns_query_with_type, parse_dns_response};
use crate::http::{classify_http_response, read_http_response};
use crate::tls::{open_probe_stream_targets, TlsClientProfile};
use crate::transport::{
    connect_transport_observed, domain_connect_targets, relay_udp_direct, relay_udp_via_socks5,
    resolve_first_socket_addr, TransportConfig,
};
use crate::types::{DnsTarget, DomainTarget, ProbeDetail};
use crate::util::{now_ms, IO_TIMEOUT};

const MAX_HTTP_FUZZ_VARIANTS: usize = 3;
const MAX_TLS_FUZZ_VARIANTS: usize = 3;
const MAX_DNS_FUZZ_VARIANTS: usize = 3;

struct TriggerFuzzOutcome {
    id: &'static str,
    field: &'static str,
    outcome: String,
    detail: String,
}

pub(super) fn append_http_trigger_fuzzing_details(
    details: &mut Vec<ProbeDetail>,
    target: &DomainTarget,
    transport: &TransportConfig,
    baseline_status: &str,
) {
    let base_request = format!(
        "GET {} HTTP/1.1\r\nHost: {}\r\nUser-Agent: ripdpi-monitor/1\r\nAccept: */*\r\nConnection: close\r\n\r\n",
        target.http_path, target.host
    )
    .into_bytes();
    let connect_targets = domain_connect_targets(target);
    let variants = [
        ("host_case_mix", "host_header_format", MH_HMIX),
        ("host_extra_space", "host_header_format", MH_HOSTEXTRASPACE),
        ("unix_eol", "line_endings", MH_UNIXEOL),
    ];
    let mut outcomes = Vec::new();
    for (id, field, flags) in variants.into_iter().take(MAX_HTTP_FUZZ_VARIANTS) {
        let mut request = base_request.clone();
        let mutation = mod_http_like_c(&request, flags);
        if mutation.rc != 0 {
            continue;
        }
        request = mutation.bytes;
        let (outcome, detail) =
            execute_http_variant(&connect_targets, target.http_port.unwrap_or(80), transport, &request);
        outcomes.push(TriggerFuzzOutcome { id, field, outcome, detail });
    }
    append_trigger_fuzzing_summary(details, "httpFuzz", baseline_status, &outcomes);
}

pub(super) fn append_tls_trigger_fuzzing_details(
    details: &mut Vec<ProbeDetail>,
    target: &DomainTarget,
    transport: &TransportConfig,
    baseline_status: &str,
) {
    let Some(base_client_hello) = build_fake_client_hello(&target.host) else {
        return;
    };
    let connect_targets = domain_connect_targets(target);
    let variants = [
        (
            "uppercase_sni",
            "sni_name",
            change_tls_sni_seeded_like_c(
                &base_client_hello,
                target.host.to_ascii_uppercase().as_bytes(),
                base_client_hello.len() + 32,
                11,
            ),
        ),
        ("drop_x25519", "key_share", remove_tls_key_share_group_like_c(&base_client_hello, 0x001d)),
        ("expand_padding", "padding_extension", padencap_tls_like_c(&base_client_hello, 24)),
    ];
    let mut outcomes = Vec::new();
    for (id, field, mutation) in variants.into_iter().take(MAX_TLS_FUZZ_VARIANTS) {
        if mutation.rc != 0 {
            continue;
        }
        let (outcome, detail) =
            execute_tls_variant(&connect_targets, target.https_port.unwrap_or(443), transport, &mutation.bytes);
        outcomes.push(TriggerFuzzOutcome { id, field, outcome, detail });
    }
    append_trigger_fuzzing_summary(details, "tlsFuzz", baseline_status, &outcomes);
}

pub(super) fn append_dns_trigger_fuzzing_details(
    details: &mut Vec<ProbeDetail>,
    target: &DnsTarget,
    transport: &TransportConfig,
    baseline_outcome: &str,
    encrypted_result: &Result<Vec<String>, String>,
) {
    let udp_server = target.udp_server.as_deref().unwrap_or(crate::util::DEFAULT_DNS_SERVER);
    let variants = [
        (
            "uppercase_qname",
            "qname_case",
            build_dns_query_with_type(&target.domain.to_ascii_uppercase(), dns_query_id(1), 1),
            dns_query_id(1),
        ),
        (
            "mixedcase_qname",
            "qname_case",
            build_dns_query_with_type(&alternating_case(&target.domain), dns_query_id(2), 1),
            dns_query_id(2),
        ),
        ("edns0_opt", "edns0", build_dns_query_with_edns0(&target.domain, dns_query_id(3)), dns_query_id(3)),
    ];
    let mut outcomes = Vec::new();
    for (id, field, packet, query_id) in variants.into_iter().take(MAX_DNS_FUZZ_VARIANTS) {
        let Ok(packet) = packet else {
            continue;
        };
        let variant_result = execute_dns_variant(udp_server, transport, &packet, query_id);
        let outcome = classify_dns_variant_outcome(&variant_result, encrypted_result);
        let detail = variant_result.as_ref().map(|addresses| addresses.join("|")).unwrap_or_else(Clone::clone);
        outcomes.push(TriggerFuzzOutcome { id, field, outcome, detail });
    }
    append_trigger_fuzzing_summary(details, "dnsFuzz", baseline_outcome, &outcomes);
}

fn append_trigger_fuzzing_summary(
    details: &mut Vec<ProbeDetail>,
    prefix: &str,
    baseline: &str,
    outcomes: &[TriggerFuzzOutcome],
) {
    if outcomes.is_empty() {
        return;
    }
    let grouped = outcomes.iter().fold(BTreeMap::<&str, Vec<&TriggerFuzzOutcome>>::new(), |mut acc, outcome| {
        acc.entry(outcome.field).or_default().push(outcome);
        acc
    });
    let changed_fields = grouped
        .iter()
        .filter_map(|(field, entries)| entries.iter().any(|entry| entry.outcome != baseline).then_some(*field))
        .collect::<BTreeSet<_>>();

    details.push(ProbeDetail { key: format!("{prefix}Baseline"), value: baseline.to_string() });
    details.push(ProbeDetail { key: format!("{prefix}VariantCount"), value: outcomes.len().to_string() });
    details.push(ProbeDetail {
        key: format!("{prefix}Outcomes"),
        value: outcomes
            .iter()
            .map(|outcome| {
                format!("{}={}:{}", outcome.id, outcome.outcome, sanitize_detail_value(outcome.detail.as_str()))
            })
            .collect::<Vec<_>>()
            .join("|"),
    });
    details.push(ProbeDetail {
        key: format!("{prefix}FieldOutcomes"),
        value: grouped
            .iter()
            .map(|(field, entries)| {
                format!(
                    "{field}={}",
                    entries.iter().map(|entry| format!("{}:{}", entry.id, entry.outcome)).collect::<Vec<_>>().join(",")
                )
            })
            .collect::<Vec<_>>()
            .join(";"),
    });
    details.push(ProbeDetail {
        key: format!("{prefix}ChangedFields"),
        value: if changed_fields.is_empty() {
            "none".to_string()
        } else {
            changed_fields.iter().copied().collect::<Vec<_>>().join("|")
        },
    });
    details.push(ProbeDetail { key: format!("{prefix}ChangedCount"), value: changed_fields.len().to_string() });
}

fn execute_http_variant(
    connect_targets: &[crate::transport::TargetAddress],
    port: u16,
    transport: &TransportConfig,
    request: &[u8],
) -> (String, String) {
    match open_probe_stream_targets(connect_targets, port, transport, None, false, TlsClientProfile::Auto, None) {
        Ok(mut stream) => {
            if let Err(err) = stream.stream.write_all(request).and_then(|_| stream.stream.flush()) {
                stream.stream.shutdown();
                return ("http_unreachable".to_string(), err.to_string());
            }
            let response = read_http_response(&mut stream.stream, crate::util::MAX_HTTP_BYTES);
            stream.stream.shutdown();
            match response {
                Ok(response) => (classify_http_response(&response), format!("status={}", response.status_code)),
                Err(err) => ("http_unreachable".to_string(), err),
            }
        }
        Err(err) => ("http_unreachable".to_string(), err),
    }
}

fn execute_tls_variant(
    connect_targets: &[crate::transport::TargetAddress],
    port: u16,
    transport: &TransportConfig,
    client_hello: &[u8],
) -> (String, String) {
    match connect_transport_observed(connect_targets, port, transport) {
        Ok(result) => {
            let mut stream = result.stream;
            let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
            let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
            if let Err(err) = stream.write_all(client_hello).and_then(|_| stream.flush()) {
                let _ = stream.shutdown(std::net::Shutdown::Both);
                return ("tls_write_failed".to_string(), err.to_string());
            }
            let mut buf = [0u8; 2048];
            let outcome = match stream.read(&mut buf) {
                Ok(0) => ("tls_close".to_string(), "eof".to_string()),
                Ok(size) => classify_tls_first_response(&buf[..size]),
                Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                    ("tls_timeout".to_string(), err.to_string())
                }
                Err(err) => ("tls_error".to_string(), err.to_string()),
            };
            let _ = stream.shutdown(std::net::Shutdown::Both);
            outcome
        }
        Err(err) => ("tcp_connect_failed".to_string(), err),
    }
}

fn classify_tls_first_response(response: &[u8]) -> (String, String) {
    if is_tls_server_hello(response) {
        return ("tls_server_hello".to_string(), "server_hello".to_string());
    }
    if response.len() >= 7 && response[0] == 0x15 {
        let alert = tls_alert_description(response[6]);
        return (format!("tls_alert_{alert}"), format!("alert={alert}"));
    }
    ("tls_response_other".to_string(), format!("bytes={}", response.len()))
}

fn tls_alert_description(code: u8) -> &'static str {
    match code {
        0 => "close_notify",
        10 => "unexpected_message",
        20 => "bad_record_mac",
        40 => "handshake_failure",
        42 => "bad_certificate",
        47 => "illegal_parameter",
        48 => "unknown_ca",
        70 => "protocol_version",
        80 => "internal_error",
        112 => "unrecognized_name",
        _ => "other",
    }
}

fn build_fake_client_hello(host: &str) -> Option<Vec<u8>> {
    let capacity = DEFAULT_FAKE_TLS.len() + host.len() + 32;
    let mutation = change_tls_sni_seeded_like_c(DEFAULT_FAKE_TLS, host.as_bytes(), capacity, 7);
    if mutation.rc == 0 {
        Some(mutation.bytes)
    } else {
        Some(DEFAULT_FAKE_TLS.to_vec())
    }
}

fn execute_dns_variant(
    server: &str,
    transport: &TransportConfig,
    packet: &[u8],
    query_id: u16,
) -> Result<Vec<String>, String> {
    let server_addr = resolve_first_socket_addr(server)?;
    let response = match transport {
        TransportConfig::Direct { .. } => relay_udp_direct(server_addr, packet).map(|(bytes, _)| bytes),
        TransportConfig::Socks5 { host, port } => {
            relay_udp_via_socks5(host, *port, server_addr, packet).map(|(bytes, _)| bytes)
        }
    }?;
    parse_dns_response(&response, query_id)
}

fn classify_dns_variant_outcome(
    udp_result: &Result<Vec<String>, String>,
    encrypted_result: &Result<Vec<String>, String>,
) -> String {
    match (udp_result, encrypted_result) {
        (Ok(udp), Ok(encrypted)) => {
            let udp_set = udp.iter().cloned().collect::<BTreeSet<_>>();
            let encrypted_set = encrypted.iter().cloned().collect::<BTreeSet<_>>();
            if udp_set == encrypted_set {
                "dns_match".to_string()
            } else if !udp_set.is_disjoint(&encrypted_set) {
                "dns_compatible_divergence".to_string()
            } else {
                "dns_sinkhole_substitution".to_string()
            }
        }
        (Err(err), Ok(_)) if err == "dns_nxdomain" => "dns_nxdomain_mismatch".to_string(),
        (Err(_), Ok(_)) => "udp_blocked".to_string(),
        (Ok(_), Err(_)) => "dns_oracle_unavailable".to_string(),
        (Err(_), Err(_)) => "dns_unavailable".to_string(),
    }
}

fn build_dns_query_with_edns0(domain: &str, query_id: u16) -> Result<Vec<u8>, String> {
    let mut packet = build_dns_query_with_type(domain, query_id, 1)?;
    packet[10] = 0;
    packet[11] = 1;
    packet.extend_from_slice(&[0, 0, 41, 0x04, 0xD0, 0, 0, 0, 0, 0, 0]);
    Ok(packet)
}

fn alternating_case(value: &str) -> String {
    let mut upper = true;
    value
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphabetic() {
                let mapped = if upper { ch.to_ascii_uppercase() } else { ch.to_ascii_lowercase() };
                upper = !upper;
                mapped
            } else {
                ch
            }
        })
        .collect()
}

fn dns_query_id(offset: u16) -> u16 {
    (((now_ms() as u16).wrapping_add(offset)) & 0xfffe).max(2)
}

fn sanitize_detail_value(value: &str) -> String {
    value.replace('|', "/").replace(';', "/").replace(',', "/").replace('\n', " ").replace('\r', " ")
}

#[cfg(test)]
mod tests {
    use std::io::ErrorKind;
    use std::net::{Ipv4Addr, UdpSocket};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;
    use std::thread::{self, JoinHandle};
    use std::time::Duration;

    use crate::connectivity::{run_dns_probe, run_domain_probe};
    use crate::test_fixtures::{build_udp_dns_answer, HttpTextServer, TlsHttpServer, TlsMode};
    use crate::transport::direct_transport;
    use crate::types::{DnsTarget, DomainTarget, ScanPathMode};

    use super::*;

    #[test]
    fn domain_probe_appends_http_trigger_fuzzing_details() {
        let server = HttpTextServer::start(|request| {
            let host_line = String::from_utf8_lossy(&request).to_string();
            let status_line = if host_line.contains("Host:  blocked.example") {
                "HTTP/1.1 200 OK"
            } else {
                "HTTP/1.1 451 Unavailable For Legal Reasons"
            };
            format!("{status_line}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").into_bytes()
        });
        let target = DomainTarget {
            host: "blocked.example".to_string(),
            connect_ip: Some("127.0.0.1".to_string()),
            connect_ips: vec![],
            https_port: Some(9),
            http_port: Some(server.port()),
            http_path: "/".to_string(),
            is_control: false,
        };

        let result = run_domain_probe(&target, &direct_transport(), None);

        assert_eq!(result.outcome, "http_blockpage");
        let changed = detail_value(&result.details, "httpFuzzChangedFields").expect("http fuzz detail");
        assert!(changed.contains("host_header_format"));
    }

    #[test]
    fn domain_probe_appends_tls_trigger_fuzzing_details() {
        let server = TlsHttpServer::start(
            TlsMode::Single("blocked.example".to_string()),
            crate::test_fixtures::FatServerMode::AlwaysOk,
        );
        let target = DomainTarget {
            host: "blocked.example".to_string(),
            connect_ip: Some("127.0.0.1".to_string()),
            connect_ips: vec![],
            https_port: Some(server.port()),
            http_port: Some(9),
            http_path: "/".to_string(),
            is_control: false,
        };

        let result = run_domain_probe(&target, &direct_transport(), None);

        assert_eq!(result.outcome, "tls_cert_invalid");
        assert!(detail_value(&result.details, "tlsFuzzChangedFields").is_some());
    }

    #[test]
    fn dns_probe_appends_dns_trigger_fuzzing_details() {
        let server = SelectiveDnsServer::start();
        let doh = HttpTextServer::start_dns_message("198.51.100.77");
        let target = DnsTarget {
            domain: "blocked.example".to_string(),
            udp_server: Some(server.addr()),
            encrypted_resolver_id: None,
            encrypted_protocol: Some("doh".to_string()),
            encrypted_host: Some("127.0.0.1".to_string()),
            encrypted_port: Some(doh.port()),
            encrypted_tls_server_name: None,
            encrypted_bootstrap_ips: vec!["127.0.0.1".to_string()],
            encrypted_doh_url: Some(format!("http://127.0.0.1:{}/dns-query", doh.port())),
            encrypted_dnscrypt_provider_name: None,
            encrypted_dnscrypt_public_key: None,
            expected_ips: vec![],
        };

        let result = run_dns_probe(&target, &direct_transport(), &ScanPathMode::RawPath);

        assert_eq!(result.outcome, "dns_sinkhole_substitution");
        assert_eq!(detail_value(&result.details, "dnsFuzzChangedFields"), Some("edns0|qname_case".to_string()));
    }

    fn detail_value(details: &[ProbeDetail], key: &str) -> Option<String> {
        details.iter().find(|detail| detail.key == key).map(|detail| detail.value.clone())
    }

    struct SelectiveDnsServer {
        addr: String,
        stop: Arc<AtomicBool>,
        handle: Option<JoinHandle<()>>,
    }

    impl SelectiveDnsServer {
        fn start() -> Self {
            let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fuzz dns");
            socket.set_read_timeout(Some(Duration::from_millis(100))).expect("set fuzz dns timeout");
            let addr = socket.local_addr().expect("fuzz dns addr");
            let stop = Arc::new(AtomicBool::new(false));
            let stop_flag = stop.clone();
            let handle = thread::spawn(move || {
                let mut buf = [0u8; 512];
                while !stop_flag.load(Ordering::Relaxed) {
                    match socket.recv_from(&mut buf) {
                        Ok((size, peer)) => {
                            if has_edns0(&buf[..size]) {
                                continue;
                            }
                            if size < 12 {
                                continue;
                            }
                            let answer_ip = if qname_has_uppercase(&buf[..size]) {
                                Ipv4Addr::new(198, 51, 100, 77)
                            } else {
                                Ipv4Addr::new(203, 0, 113, 10)
                            };
                            if let Ok(response) = build_udp_dns_answer(&buf[..size], answer_ip) {
                                let _ = socket.send_to(&response, peer);
                            }
                        }
                        Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                        Err(_) => break,
                    }
                }
            });
            Self { addr: addr.to_string(), stop, handle: Some(handle) }
        }

        fn addr(&self) -> String {
            self.addr.clone()
        }
    }

    impl Drop for SelectiveDnsServer {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
            let wake = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind selective dns wake");
            let _ = wake.send_to(b"wake", self.addr.as_str());
            if let Some(handle) = self.handle.take() {
                handle.join().expect("join selective dns");
            }
        }
    }

    fn has_edns0(packet: &[u8]) -> bool {
        packet.len() > 11 && packet[10] == 0 && packet[11] == 1
    }

    fn qname_has_uppercase(packet: &[u8]) -> bool {
        let mut index = 12usize;
        while index < packet.len() {
            let label_len = packet[index] as usize;
            if label_len == 0 {
                break;
            }
            index += 1;
            let label_end = index + label_len;
            if label_end > packet.len() {
                return false;
            }
            if packet[index..label_end].iter().any(u8::is_ascii_uppercase) {
                return true;
            }
            index = label_end;
        }
        false
    }
}
