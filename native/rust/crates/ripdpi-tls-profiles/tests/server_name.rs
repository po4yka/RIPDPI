use std::net::{TcpListener, TcpStream};
use std::thread;
use std::time::Duration;

use ripdpi_tls_profiles::{configure_builder, normalize_server_name};

#[test]
fn tls_identity_wire_sni_matches_an_independent_rustls_decoder() {
    for (input, expected_sni) in [
        ("fixture.test.", Some("fixture.test")),
        ("xn--bcher-kva.test", Some("xn--bcher-kva.test")),
        ("127.0.0.1", None),
        ("::1", None),
        ("[::1]", None),
    ] {
        let name = normalize_server_name(input).expect("valid identity");
        let listener = TcpListener::bind("127.0.0.1:0").expect("loopback listener");
        let address = listener.local_addr().expect("listener address");
        let server = thread::spawn(move || {
            let (mut socket, _) = listener.accept().expect("client socket");
            socket.set_read_timeout(Some(Duration::from_secs(2))).expect("server timeout");
            let mut acceptor = rustls::server::Acceptor::default();
            loop {
                assert!(acceptor.read_tls(&mut socket).expect("read ClientHello") > 0);
                if let Some(accepted) = acceptor.accept().expect("independent ClientHello decoder") {
                    return accepted.client_hello().server_name().map(str::to_owned);
                }
            }
        });
        let socket = TcpStream::connect(address).expect("loopback connection");
        socket.set_read_timeout(Some(Duration::from_secs(2))).expect("client timeout");
        socket.set_write_timeout(Some(Duration::from_secs(2))).expect("client write timeout");
        let connector = configure_builder("chrome_stable").expect("TLS profile").build();
        let _ = connector.connect(&name, socket);
        assert_eq!(server.join().expect("decoder thread").as_deref(), expected_sni, "{input}");
    }
}
