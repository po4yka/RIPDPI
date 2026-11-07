use super::*;
use ripdpi_proxy_runtime_adapter::model::config::{
    DestinationRoutingAction, DestinationRoutingNetwork, DestinationRoutingRule,
};

fn assert_policy_reply(config: RuntimeConfig, self_target: bool) {
    let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind origin");
    origin.set_nonblocking(true).expect("nonblocking origin");
    let (mut client, server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("bound reply read");
    let target = if self_target {
        server.local_addr().expect("proxy endpoint")
    } else {
        origin.local_addr().expect("origin endpoint")
    };
    // Independent RFC 1928 bytes: NoAuth greeting followed by IPv4 CONNECT.
    let mut request = vec![5, 1, 0, 5, 1, 0, 1, 127, 0, 0, 1];
    request.extend_from_slice(&target.port().to_be_bytes());
    client.write_all(&request).expect("send request");
    let error = super::super::handle_client(server, &runtime_state(config)).expect_err("policy denial");
    assert_eq!(error.kind(), ErrorKind::PermissionDenied);
    let mut response = [0; 12];
    client.read_exact(&mut response).expect("read negotiation and refusal");
    assert_eq!(response, [5, 0, 5, 2, 0, 1, 0, 0, 0, 0, 0, 0]);
    assert_eq!(client.read(&mut [0]).expect("read closure"), 0);
    assert_eq!(origin.accept().expect_err("no blocked origin connection").kind(), ErrorKind::WouldBlock);
}

#[test]
fn self_connect_returns_ruleset_denied() {
    assert_policy_reply(RuntimeConfig::default(), true);
}

#[test]
fn destination_block_returns_ruleset_denied() {
    let mut config = RuntimeConfig::default();
    config.destination_routing.rules.push(DestinationRoutingRule {
        action: DestinationRoutingAction::Block,
        network: DestinationRoutingNetwork::Tcp,
        domains: vec![],
        ip_ranges: vec![],
        destination_ports: vec![],
    });
    assert_policy_reply(config, false);
}

#[test]
fn unclassified_permission_error_remains_general_failure() {
    let (mut client, mut server) = connected_pair();
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("bound reply read");
    let error = super::super::connect_relay::ConnectRelayError::from(std::io::Error::new(
        ErrorKind::PermissionDenied,
        "unclassified OS error",
    ));
    let result = super::super::handle_socks5_connect_error(&mut server, error).expect_err("failure remains an error");
    assert_eq!(result.kind(), ErrorKind::PermissionDenied);
    let mut response = [0; 10];
    client.read_exact(&mut response).expect("read failure");
    assert_eq!(response, [5, 1, 0, 1, 0, 0, 0, 0, 0, 0]);
}
