use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener};
use std::time::Duration;

use super::support::proxy::{ephemeral_proxy_config, start_proxy};
use super::support::socks5::socks_connect;
use super::{
    DestinationDomainMatcher, DestinationDomainMatcherKind, DestinationRoutingAction, DestinationRoutingNetwork,
    DestinationRoutingRule,
};

#[test]
fn http_authority_policy_blocks_target_instead_of_decoy_host() {
    check_authority_policy("real.example", true);
}

#[test]
fn http_authority_policy_does_not_block_a_decoy_host() {
    check_authority_policy("decoy.example", false);
}

fn check_authority_policy(blocked_host: &str, blocked: bool) {
    let _guard = super::test_guard();
    for request in [
        &b"GET http://real.example/path HTTP/1.1\r\nHost: decoy.example\r\n\r\n"[..],
        &b"CONNECT real.example:443 HTTP/1.1\r\nHost: decoy.example\r\n\r\n"[..],
    ] {
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
        config.destination_routing.rules = vec![DestinationRoutingRule {
            action: DestinationRoutingAction::Block,
            network: DestinationRoutingNetwork::Both,
            domains: vec![DestinationDomainMatcher {
                kind: DestinationDomainMatcherKind::Exact,
                value: blocked_host.into(),
            }],
            ip_ranges: Vec::new(),
            destination_ports: Vec::new(),
        }];
        config.destination_routing.canonical_digest = "http-authority-regression".into();
        let proxy = start_proxy(config, None);
        let mut client = socks_connect(proxy.port, origin.local_addr().unwrap().port());
        // IP/default policy permits the SYN; payload authority gates application bytes.
        let (mut peer, _) = origin.accept().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        client.write_all(request).unwrap();
        if blocked {
            assert_eq!(peer.read(&mut [0; 512]).unwrap(), 0, "blocked target received application bytes");
        } else {
            let mut received = vec![0; request.len()];
            peer.read_exact(&mut received).unwrap();
            assert_eq!(received, request);
        }
        drop(peer);
        drop(client);
        drop(proxy);
    }
}
