use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener};
use std::time::Duration;

use crate::transport::direct_transport;
use crate::types::TelegramDcEndpoint;

use super::*;

fn target(endpoints: Vec<TelegramDcEndpoint>) -> TelegramTarget {
    TelegramTarget {
        media_url: "https://unused.invalid/".into(),
        upload_ip: "unused.invalid".into(),
        upload_port: 443,
        dc_endpoints: endpoints,
        stall_timeout_ms: 10,
        total_timeout_ms: 10,
        upload_size_bytes: 1,
    }
}

#[test]
fn tcp_probe_only_connects_to_the_configured_address_and_port() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = listener.local_addr().unwrap();
    let dc = TelegramDcEndpoint { ip: address.ip().to_string(), label: "configured DC".into(), port: address.port() };
    let result = telegram_dc_probe_with_abort(&target(vec![dc.clone(), dc]), &direct_transport(), &|| None);
    assert_eq!((result.planned, result.total, result.reachable), (1, 1, 1));
    let row = &result.results[0];
    assert!(row.starts_with("configured DC:ok:"));
    assert!(row.ends_with(&format!("address={address};family=ipv4;path=direct")));
    let (mut peer, _) = listener.accept().unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
    assert_eq!(peer.read(&mut [0]).unwrap(), 0);
}

#[test]
fn socks_probe_reports_the_result_of_each_configured_port() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = listener.local_addr().unwrap();
    let worker = std::thread::spawn(move || {
        for (port, response) in [(443u16, 5u8), (80u16, 0)] {
            let (mut peer, _) = listener.accept().unwrap();
            peer.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
            let mut greeting = [0; 3];
            peer.read_exact(&mut greeting).unwrap();
            assert_eq!(greeting, [5, 1, 0]);
            peer.write_all(&[5, 0]).unwrap();
            let mut request = [0; 10];
            peer.read_exact(&mut request).unwrap();
            assert_eq!(&request[..8], &[5, 1, 0, 1, 149, 154, 175, 50]);
            assert_eq!(u16::from_be_bytes([request[8], request[9]]), port);
            peer.write_all(&[5, response, 0, 1, 127, 0, 0, 1, 0, 1]).unwrap();
            if response == 0 {
                assert_eq!(peer.read(&mut [0]).unwrap(), 0);
            }
        }
    });
    let target = target(
        [443, 80]
            .into_iter()
            .map(|port| TelegramDcEndpoint { ip: "149.154.175.50".into(), label: "DC1".into(), port })
            .collect(),
    );
    let transport = TransportConfig::Socks5 { host: address.ip().to_string(), port: address.port(), credentials: None };
    let result = telegram_dc_probe_with_abort(&target, &transport, &|| None);
    worker.join().unwrap();
    assert_eq!((result.planned, result.total, result.reachable), (2, 2, 1));
    assert_eq!(
        result.results[0],
        "DC1:fail::address=149.154.175.50:443;family=ipv4;path=socks5;bootstrap=production;stage=socks5_negotiation"
    );
    assert!(result.results[1].starts_with("DC1:ok:"));
    assert!(result.results[1].ends_with("address=149.154.175.50:80;family=ipv4;path=socks5;bootstrap=production"));
}

#[test]
fn cancelled_rows_keep_the_ipv6_endpoint_and_planned_coverage() {
    let dc = TelegramDcEndpoint { ip: "::1".into(), label: "DC6".into(), port: 443 };
    let result = telegram_dc_probe_with_abort(&target(vec![dc]), &direct_transport(), &|| Some("cancelled"));
    assert_eq!((result.planned, result.total, result.reachable), (1, 0, 0));
    assert_eq!(result.abort_reason, Some("cancelled"));
    assert_eq!(result.results, ["DC6:not_run::address=[::1]:443;family=ipv6;path=direct"]);
}
