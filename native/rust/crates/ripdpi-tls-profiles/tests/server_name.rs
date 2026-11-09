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

fn identity_params(names: &[&str], common_name: &str) -> rcgen::CertificateParams {
    let mut params = rcgen::CertificateParams::new(names.iter().map(|name| (*name).to_owned()).collect::<Vec<_>>())
        .expect("certificate params");
    params.distinguished_name.push(rcgen::DnType::CommonName, common_name);
    params
}

fn identity_handshake(
    params: rcgen::CertificateParams,
    server_name: &str,
    certificate_name: Option<&str>,
    version: &'static rustls::SupportedProtocolVersion,
) -> bool {
    use std::io::{Read, Write};
    use std::sync::Arc;

    use boring::x509::X509;
    use rcgen::{BasicConstraints, IsCa, Issuer, KeyPair};
    use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};

    let mut ca_params = identity_params(&[], "test CA");
    ca_params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    let ca_key = KeyPair::generate().expect("CA key");
    let ca = ca_params.self_signed(&ca_key).expect("CA certificate");
    let issuer = Issuer::new(ca_params, ca_key);
    let key = KeyPair::generate().expect("leaf key");
    let certificate = params.signed_by(&key, &issuer).expect("leaf certificate");
    let server_config = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_protocol_versions(&[version])
        .expect("protocol version")
        .with_no_client_auth()
        .with_single_cert(
            vec![certificate.der().clone()],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key.serialize_der())),
        )
        .expect("server certificate");
    let listener = TcpListener::bind("127.0.0.1:0").expect("loopback listener");
    let address = listener.local_addr().expect("listener address");
    let server = thread::spawn(move || {
        let (socket, _) = listener.accept().expect("client socket");
        socket.set_read_timeout(Some(Duration::from_secs(2))).expect("server read timeout");
        socket.set_write_timeout(Some(Duration::from_secs(2))).expect("server write timeout");
        let connection = rustls::ServerConnection::new(Arc::new(server_config)).expect("server connection");
        let mut stream = rustls::StreamOwned::new(connection, socket);
        let mut payload = [0];
        if stream.read_exact(&mut payload).is_err() {
            return false;
        }
        assert_eq!(&payload, b"x");
        stream.write_all(&payload).expect("echo payload");
        true
    });

    let mut builder = configure_builder("chrome_stable").expect("TLS profile");
    builder.cert_store_mut().add_cert(X509::from_der(ca.der()).expect("CA DER")).expect("explicit root");
    let connector = builder.build();
    let mut connection = connector.configure().expect("connection config");
    if let Some(name) = certificate_name {
        connection.set_verify_hostname(false);
        match name.parse() {
            Ok(ip) => connection.param_mut().set_ip(ip),
            Err(_) => connection.param_mut().set_host(name),
        }
        .expect("certificate identity override");
    }
    let socket = TcpStream::connect(address).expect("loopback connection");
    socket.set_read_timeout(Some(Duration::from_secs(2))).expect("client read timeout");
    socket.set_write_timeout(Some(Duration::from_secs(2))).expect("client write timeout");
    let accepted = connection
        .connect(server_name, socket)
        .map(|mut stream| {
            stream.write_all(b"x").expect("send payload after verified handshake");
            let mut payload = [0];
            stream.read_exact(&mut payload).expect("echoed payload");
            assert_eq!(&payload, b"x");
        })
        .is_ok();
    assert_eq!(server.join().expect("server thread"), accepted, "only verified peers may receive application data");
    accepted
}

#[test]
fn standard_tls_requires_san_for_dns_and_ip_identities() {
    for version in [&rustls::version::TLS12, &rustls::version::TLS13] {
        let mut email_only = identity_params(&[], "fixture.test");
        email_only.subject_alt_names.push(rcgen::SanType::Rfc822Name("admin@fixture.test".try_into().expect("email")));
        for (label, params, server_name, certificate_name, expected) in [
            ("DNS SAN", identity_params(&["fixture.test"], "unused"), "fixture.test", None, true),
            ("CN only", identity_params(&[], "fixture.test"), "fixture.test", None, false),
            ("wrong DNS SAN", identity_params(&["other.test"], "fixture.test"), "fixture.test", None, false),
            ("email SAN only", email_only, "fixture.test", None, false),
            ("IPv4 SAN", identity_params(&["127.0.0.1"], "unused"), "127.0.0.1", None, true),
            ("IPv6 SAN", identity_params(&["::1"], "unused"), "::1", None, true),
            ("wrong IP SAN", identity_params(&["127.0.0.2"], "127.0.0.1"), "127.0.0.1", None, false),
            ("manual DNS SAN", identity_params(&["fixture.test"], "unused"), "cover.test", Some("fixture.test"), true),
            ("manual CN only", identity_params(&[], "fixture.test"), "cover.test", Some("fixture.test"), false),
            ("manual IP SAN", identity_params(&["127.0.0.1"], "unused"), "cover.test", Some("127.0.0.1"), true),
        ] {
            assert_eq!(
                identity_handshake(params, server_name, certificate_name, version),
                expected,
                "{version:?}: {label}"
            );
        }
    }
}
