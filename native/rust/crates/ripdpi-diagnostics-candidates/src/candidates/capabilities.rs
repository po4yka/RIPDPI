pub fn probe_fake_ttl_capability() -> bool {
    use std::net::TcpListener;
    let Ok(listener) = TcpListener::bind("127.0.0.1:0") else { return false };
    let result = listener.set_ttl(1);
    // Restore a sane TTL regardless of outcome — we borrowed the socket briefly.
    let _ = listener.set_ttl(64);
    result.is_ok()
}
