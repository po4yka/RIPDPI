use std::io::{BufRead, BufReader, ErrorKind, Read, Write};
use std::net::{TcpListener, UdpSocket};
use std::thread;
use std::time::{Duration, Instant};

use super::{resolve_via_encrypted_dns_with_observations, resolve_via_udp_with_observations};
use crate::transport::TransportConfig;
use ripdpi_diagnostics_contracts::types::DnsResponseOutcome;
use ripdpi_ech_dns::encrypted_dns_endpoint_for_resolver_id;

const QUESTION_END: usize = 29;

fn ede_response(query: &[u8], nxdomain: bool) -> (Vec<u8>, bool) {
    assert_eq!(&query[12..QUESTION_END], b"\x03ede\x07example\x00\x00\x01\x00\x01");
    let advertised_opt = query[10..12] == [0, 1] && query[QUESTION_END..QUESTION_END + 5] == [0, 0, 41, 4, 208];
    let mut response = query[..QUESTION_END].to_vec();
    response[2..4].copy_from_slice(&(if nxdomain { 0x8183u16 } else { 0x8180u16 }).to_be_bytes());
    response[6..10].copy_from_slice(&[0, u8::from(!nxdomain), 0, 0]);
    response[10..12].copy_from_slice(&[0, 1]);
    if !nxdomain {
        response.extend_from_slice(&[0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 1, 1, 1]);
    }
    let ede = b"\x00\x0fprivate diagnostic text";
    response.extend_from_slice(&[0, 0, 41, 4, 208, 0, 0, 0, 0]);
    response.extend_from_slice(&((4 + ede.len()) as u16).to_be_bytes());
    response.extend_from_slice(&15u16.to_be_bytes());
    response.extend_from_slice(&(ede.len() as u16).to_be_bytes());
    response.extend_from_slice(ede);
    (response, advertised_opt)
}

#[test]
fn udp_measurement_requests_and_retains_numeric_ede() {
    for nxdomain in [false, true] {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let address = socket.local_addr().unwrap().to_string();
        let server = thread::spawn(move || {
            let mut query = [0; 2048];
            let (len, peer) = socket.recv_from(&mut query).unwrap();
            let (response, advertised_opt) = ede_response(&query[..len], nxdomain);
            socket.send_to(&response, peer).unwrap();
            advertised_opt
        });
        let result = resolve_via_udp_with_observations(
            "ede.example",
            &address,
            &TransportConfig::Direct { route_experiment: None },
        );
        assert!(server.join().unwrap(), "diagnostic UDP must request EDNS before accepting EDE");
        assert_eq!(result.response.extended_dns_error_codes, vec![15]);
        assert_eq!(
            result.response.outcome,
            if nxdomain { DnsResponseOutcome::Nxdomain } else { DnsResponseOutcome::Answer }
        );
        assert!(!result.response.to_detail_json().unwrap().contains("private diagnostic text"));
    }
}

#[test]
fn encrypted_measurement_retains_numeric_ede_after_padding_restore() {
    for nxdomain in [false, true] {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(2);
            let stream = loop {
                match listener.accept() {
                    Ok((stream, _)) => break stream,
                    Err(error) if error.kind() == ErrorKind::WouldBlock && Instant::now() < deadline => {
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(error) => panic!("accept diagnostic DNS request before deadline: {error}"),
                }
            };
            stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            stream.set_write_timeout(Some(Duration::from_secs(2))).unwrap();
            let mut stream = BufReader::new(stream);
            let mut length = 0;
            let mut header_bytes = 0;
            loop {
                let mut line = String::new();
                let read = stream.read_line(&mut line).unwrap();
                assert!(read > 0, "complete HTTP request headers");
                header_bytes += read;
                assert!(header_bytes <= 8192);
                if line == "\r\n" {
                    break;
                }
                if let Some((name, value)) = line.split_once(':')
                    && name.eq_ignore_ascii_case("content-length")
                {
                    length = value.trim().parse::<usize>().unwrap();
                }
            }
            assert!(length <= 2048);
            let mut query = vec![0; length];
            stream.read_exact(&mut query).unwrap();
            let (response, advertised_opt) = ede_response(&query, nxdomain);
            write!(
                stream.get_mut(),
                "HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                response.len()
            )
            .unwrap();
            stream.get_mut().write_all(&response).unwrap();
            advertised_opt
        });
        let mut endpoint = encrypted_dns_endpoint_for_resolver_id("adguard");
        endpoint.host = address.ip().to_string();
        endpoint.port = address.port();
        endpoint.doh_url = Some(format!("http://{address}/dns-query"));
        endpoint.bootstrap_ips = vec![address.ip()];
        let (_, _, facts) = resolve_via_encrypted_dns_with_observations(
            "ede.example",
            endpoint,
            &TransportConfig::Direct { route_experiment: None },
        );
        assert!(server.join().unwrap());
        assert_eq!(facts.extended_dns_error_codes, vec![15]);
        assert_eq!(facts.outcome, if nxdomain { DnsResponseOutcome::Nxdomain } else { DnsResponseOutcome::Answer });
        assert!(!facts.to_detail_json().unwrap().contains("private diagnostic text"));
    }
}
