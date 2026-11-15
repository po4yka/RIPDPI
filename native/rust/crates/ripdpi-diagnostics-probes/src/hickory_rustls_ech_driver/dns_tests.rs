use std::sync::mpsc::{self, Receiver};
use std::time::Duration;

use hickory_resolver::config::{ConnectionConfig, NameServerConfig, ResolverConfig};
use tokio::net::UdpSocket;
use tokio::task::JoinHandle;

use super::dns;

const ECH_CONFIG: &[u8] = &[
    0x00, 0x3e, 0xfe, 0x0d, 0x00, 0x3a, 0x00, 0x00, 0x20, 0x00, 0x20, 0xbb, 0x2f, 0x29, 0xe3, 0xe3, 0x05, 0x7e, 0x04,
    0x19, 0xd5, 0x2f, 0xc5, 0xf4, 0x41, 0x18, 0x77, 0x6f, 0x8d, 0xb6, 0x1c, 0xea, 0x4f, 0xdf, 0x76, 0x07, 0x9b, 0x93,
    0x60, 0x6c, 0x5a, 0x62, 0x48, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00, 0x07, 0x65, 0x63,
    0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
];

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

#[test]
fn service_mode_ech_is_selected() {
    assert_eq!(lookup_records(vec![service_record(1, &[(5, ECH_CONFIG)])]).unwrap(), Some(ECH_CONFIG.to_vec()));
}

#[test]
fn alias_mode_parameters_are_not_ech_evidence() {
    let result = lookup_records(vec![service_record(0, &[(5, ECH_CONFIG)])]);
    assert_eq!(result.unwrap_err(), "ech-dns-binding-unsupported");
}

#[test]
fn alias_rrset_does_not_fall_through_to_service_mode() {
    let alias = service_record(0, &[]);
    let service = service_record(1, &[(5, ECH_CONFIG)]);
    for records in [vec![alias.clone(), service.clone()], vec![service.clone(), alias.clone()]] {
        assert_eq!(lookup_records(records).unwrap_err(), "ech-dns-binding-unsupported");
    }
}

#[test]
fn unknown_mandatory_parameter_is_not_ech_evidence() {
    let record = service_record(1, &[(0, &[0xfd, 0xe8]), (5, ECH_CONFIG), (65000, &[])]);
    assert_eq!(lookup_records(vec![record]).unwrap_err(), "ech-dns-binding-unsupported");
}

#[test]
fn incompatible_record_does_not_hide_later_compatible_ech() {
    let incompatible = service_record(1, &[(0, &[0xfd, 0xe8]), (5, ECH_CONFIG), (65000, &[])]);
    let mut selected = ECH_CONFIG.to_vec();
    selected[6] = 1; // A distinct config_id in the independent wire fixture.
    let compatible = service_record(2, &[(5, &selected)]);
    assert_eq!(lookup_records(vec![incompatible, compatible]).unwrap(), Some(selected));
}

#[test]
fn supported_mandatory_ech_requires_its_value() {
    let valid = service_record(1, &[(0, &[0, 5]), (5, ECH_CONFIG)]);
    assert_eq!(lookup_records(vec![valid]).unwrap(), Some(ECH_CONFIG.to_vec()));
    let missing = service_record(1, &[(0, &[0, 5])]);
    assert_eq!(lookup_records(vec![missing]).unwrap_err(), "ech-dns-binding-unsupported");
}

#[test]
fn mandatory_self_reference_and_duplicates_are_not_compatible() {
    for mandatory in [&[0, 0][..], &[0, 5, 0, 5][..]] {
        let record = service_record(1, &[(0, mandatory), (5, ECH_CONFIG)]);
        assert_eq!(lookup_records(vec![record]).unwrap_err(), "ech-dns-binding-unsupported");
    }
}

#[test]
fn mandatory_alpn_is_not_supported_by_the_tls_only_driver() {
    let record = service_record(1, &[(0, &[0, 1]), (1, &[2, b'h', b'2']), (5, ECH_CONFIG)]);
    assert_eq!(lookup_records(vec![record]).unwrap_err(), "ech-dns-binding-unsupported");
}

#[test]
fn no_default_alpn_is_automatically_mandatory() {
    let record = service_record(1, &[(1, &[2, b'h', b'2']), (2, &[]), (5, ECH_CONFIG)]);
    assert_eq!(lookup_records(vec![record]).unwrap_err(), "ech-dns-binding-unsupported");
}

#[test]
fn unknown_optional_parameter_does_not_hide_ech() {
    let record = service_record(1, &[(5, ECH_CONFIG), (65000, &[])]);
    assert_eq!(lookup_records(vec![record]).unwrap(), Some(ECH_CONFIG.to_vec()));
}

fn lookup_records(records: Vec<Vec<u8>>) -> Result<Option<Vec<u8>>, String> {
    block_on(async {
        let (config, server, received) = dns_fixture(0, records).await;
        let result = dns::lookup("binding.example", Duration::from_millis(100), config).await;
        server.abort();
        assert!(received.try_recv().is_ok(), "an actual DNS query must reach the wire fixture");
        result
    })
}

fn service_record(priority: u16, parameters: &[(u16, &[u8])]) -> Vec<u8> {
    let mut record = priority.to_be_bytes().to_vec();
    if priority == 0 {
        record.extend_from_slice(b"\x05alias\x07example\x00");
    } else {
        record.push(0);
    }
    for (key, value) in parameters {
        record.extend_from_slice(&key.to_be_bytes());
        record.extend_from_slice(&u16::try_from(value.len()).expect("SvcParam fixture length").to_be_bytes());
        record.extend_from_slice(value);
    }
    record
}

#[test]
fn nondefault_port_is_automatically_mandatory() {
    let record = service_record(1, &[(3, &[0x20, 0xfb]), (5, ECH_CONFIG)]);
    assert_eq!(lookup_records(vec![record]).unwrap_err(), "ech-dns-binding-unsupported");
}

#[test]
fn unsupported_port_does_not_hide_later_default_endpoint() {
    let incompatible = service_record(1, &[(3, &[0x20, 0xfb]), (5, ECH_CONFIG)]);
    let mut selected = ECH_CONFIG.to_vec();
    selected[6] = 1;
    let compatible = service_record(2, &[(3, &[0x01, 0xbb]), (5, &selected)]);
    assert_eq!(lookup_records(vec![incompatible, compatible]).unwrap(), Some(selected));
}

#[test]
fn default_https_port_is_supported_as_automatic_and_explicit_mandatory() {
    for mandatory in [None, Some(&[0, 3][..]), Some(&[0, 3, 0, 5][..])] {
        let mut parameters: Vec<(u16, &[u8])> = Vec::new();
        if let Some(keys) = mandatory {
            parameters.push((0, keys));
        }
        parameters.extend([(3, &[0x01, 0xbb][..]), (5, ECH_CONFIG)]);
        let record = service_record(1, &parameters);
        assert_eq!(lookup_records(vec![record]).unwrap(), Some(ECH_CONFIG.to_vec()));
    }
}

#[test]
fn alternate_service_target_is_unsupported_without_endpoint_selection() {
    let mut alternate = service_record(1, &[(5, ECH_CONFIG)]);
    alternate.splice(2..3, b"\x03alt\x07example\x00".iter().copied());
    assert_eq!(lookup_records(vec![alternate.clone()]).unwrap_err(), "ech-dns-binding-unsupported");
    let mut selected = ECH_CONFIG.to_vec();
    selected[6] = 1;
    let origin = service_record(2, &[(5, &selected)]);
    assert_eq!(lookup_records(vec![alternate, origin]).unwrap(), Some(selected));
}

#[test]
fn explicit_origin_target_ignores_case_and_trailing_root() {
    let mut origin = service_record(1, &[(5, ECH_CONFIG)]);
    origin.splice(2..3, b"\x07BiNdInG\x07ExAmPlE\x00".iter().copied());
    assert_eq!(lookup_records(vec![origin.clone()]).unwrap(), Some(ECH_CONFIG.to_vec()));
    block_on(async {
        let (config, server, received) = dns_fixture(0, vec![origin]).await;
        let result = dns::lookup("binding.example.", Duration::from_millis(100), config).await;
        server.abort();
        assert!(received.try_recv().is_ok());
        assert_eq!(result.unwrap(), Some(ECH_CONFIG.to_vec()));
    });
}
