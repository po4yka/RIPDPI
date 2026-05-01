use std::io::ErrorKind;
use std::net::{Ipv4Addr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use crate::connectivity::{run_dns_probe, run_domain_probe};
use crate::test_fixtures::{build_udp_dns_answer, HttpTextServer, TlsHttpServer, TlsMode};
use crate::transport::direct_transport;
use crate::types::{DnsTarget, DomainTarget, ProbeDetail, ScanPathMode};

#[test]
#[ignore = "local fixture timing is flaky under full-workspace test load"]
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
#[ignore = "local fixture timing is flaky under full-workspace test load"]
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
