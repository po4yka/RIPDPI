use super::*;

#[test]
fn socks5_http_transport_accepts_ipv6_proxy_hosts() {
    for host in ["::1", "2001:db8::1234", "[::1]", "127.0.0.1", "proxy.example"] {
        assert!(configure_socks5_transport(Client::builder(), host, 1080, None).is_ok(), "host {host}");
    }
}
