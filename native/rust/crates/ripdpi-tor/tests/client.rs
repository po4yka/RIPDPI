use std::future::Future;
use std::io;

use ripdpi_tor::{BoxedIo, TorRelayClient, TorTarget};

fn _connect_tcp_returns_boxed_io<'a>(
    client: &'a TorRelayClient,
    target: &'a TorTarget,
) -> impl Future<Output = io::Result<BoxedIo>> + Send + 'a {
    client.connect_tcp(target)
}

#[test]
fn target_preserves_domain_and_onion_authorities() {
    let domain = TorTarget::new("example.com", 443).expect("domain target");
    assert_eq!(domain.host(), "example.com");
    assert_eq!(domain.port(), 443);
    assert_eq!(domain.as_arti_target(), ("example.com", 443));

    let onion =
        TorTarget::new("2gzyxa5ihm7ru6h2k6vzuzul4qoftkornhmcq4w4y7f2pla55atbdpqd.onion", 80).expect("onion target");
    assert_eq!(onion.port(), 80);
    assert!(onion.host().ends_with(".onion"));
}

#[test]
fn target_rejects_zero_port_before_arti_connect() {
    let error = TorTarget::new("example.com", 0).expect_err("zero port must be rejected");
    assert!(error.to_string().contains("invalid Tor target"));
}
