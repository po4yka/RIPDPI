use std::sync::mpsc::{self, Receiver};
use std::time::Duration;

use hickory_resolver::config::{ConnectionConfig, NameServerConfig, ResolverConfig};
use tokio::net::UdpSocket;
use tokio::task::JoinHandle;

use super::dns;

fn block_on<F: std::future::Future>(future: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread().enable_all().build().expect("test runtime").block_on(future)
}

#[test]
fn clean_https_nodata_is_no_ech_config() {
    block_on(async {
        let (config, server, received) = dns_fixture(0, Vec::new()).await;
        let result = dns::lookup("nodata.example", Duration::from_millis(100), config).await;
        server.abort();
        assert!(received.try_recv().is_ok(), "the resolver must send a real HTTPS query");
        assert_eq!(result.expect("clean NODATA is not a resolver failure"), None);
    });
}

#[test]
fn nxdomain_remains_distinct_from_clean_nodata() {
    block_on(async {
        let (config, server, received) = dns_fixture(3, Vec::new()).await;
        let result = dns::lookup("nxdomain.example", Duration::from_millis(100), config).await;
        server.abort();
        assert!(received.try_recv().is_ok());
        assert!(result.is_err(), "a nonexistent name is not a clean NODATA answer");
    });
}

#[test]
fn dns_server_failure_remains_an_error() {
    block_on(async {
        let (config, server, received) = dns_fixture(2, Vec::new()).await;
        let result = dns::lookup("servfail.example", Duration::from_millis(100), config).await;
        server.abort();
        assert!(received.try_recv().is_ok());
        assert!(result.is_err(), "SERVFAIL must not report missing ECH publication");
    });
}

#[test]
fn https_record_without_ech_is_no_ech_config() {
    block_on(async {
        let (config, server, received) = dns_fixture(0, vec![vec![0, 1, 0]]).await;
        let result = dns::lookup("no-ech.example", Duration::from_millis(100), config).await;
        server.abort();
        assert!(received.try_recv().is_ok());
        assert_eq!(result.expect("valid HTTPS ServiceMode record"), None);
    });
}

/// # Cancel safety:
/// cancel-safe: no task is spawned before the final await; each completed
/// fixture returns its task handle, which the test aborts before returning.
async fn dns_fixture(rcode: u8, records: Vec<Vec<u8>>) -> (ResolverConfig, JoinHandle<()>, Receiver<Vec<u8>>) {
    let socket = UdpSocket::bind("127.0.0.1:0").await.expect("loopback DNS bind");
    let address = socket.local_addr().expect("loopback DNS address");
    let (sent, received) = mpsc::channel();
    let server = tokio::spawn(async move {
        let mut query = [0_u8; 4096];
        loop {
            let (len, peer) = socket.recv_from(&mut query).await.expect("DNS query");
            let packet = &query[..len];
            if sent.send(packet.to_vec()).is_err() {
                return;
            }
            let response = dns_response(packet, rcode, &records);
            socket.send_to(&response, peer).await.expect("DNS response");
        }
    });
    let mut connection = ConnectionConfig::udp();
    connection.port = address.port();
    let config = ResolverConfig::from_name_servers(vec![NameServerConfig::new(address.ip(), true, vec![connection])]);
    (config, server, received)
}

fn dns_response(query: &[u8], rcode: u8, records: &[Vec<u8>]) -> Vec<u8> {
    assert!(query.len() >= 17, "DNS query header and question");
    let mut end = 12;
    while query[end] != 0 {
        let label_len = usize::from(query[end]);
        end += 1 + label_len;
        assert!(end < query.len());
    }
    end += 5;
    assert!(end <= query.len());
    assert_eq!(&query[end - 4..end], &[0, 65, 0, 1], "HTTPS IN question");
    let mut response = query[..2].to_vec();
    response.extend([0x81, 0x80 | rcode, 0, 1]);
    response.extend_from_slice(&u16::try_from(records.len()).expect("fixture answer count").to_be_bytes());
    response.extend([0, 0, 0, 0]);
    response.extend_from_slice(&query[12..end]);
    for record in records {
        response.extend([0xc0, 0x0c, 0, 65, 0, 1, 0, 0, 0, 60]);
        response.extend_from_slice(&u16::try_from(record.len()).expect("fixture RDATA length").to_be_bytes());
        response.extend_from_slice(record);
    }
    response
}
