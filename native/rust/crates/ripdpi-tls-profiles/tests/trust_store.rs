use std::fs;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::Path;
use std::process::Command;
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use boring::pkey::PKey;
use boring::ssl::{SslAcceptor, SslConnector, SslMethod};
use boring::x509::X509;
use rcgen::{BasicConstraints, CertificateParams, DnType, IsCa, Issuer, KeyPair};
use ripdpi_tls_profiles::configure_builder;

const FIXTURE_ENV: &str = "RIPDPI_TRUST_STORE_FIXTURE";

#[test]
fn standard_builder_uses_only_bundled_and_explicit_roots() {
    if let Some(directory) = std::env::var_os(FIXTURE_ENV) {
        check_environment_root(Path::new(&directory));
        return;
    }

    let nonce = SystemTime::now().duration_since(UNIX_EPOCH).expect("clock").as_nanos();
    let directory = std::env::temp_dir().join(format!("ripdpi-trust-store-{}-{nonce}", std::process::id()));
    fs::create_dir_all(directory.join("certs")).expect("fixture directory");
    let mut params = CertificateParams::new(Vec::<String>::new()).expect("CA parameters");
    params.distinguished_name.push(DnType::CommonName, "Trust store regression CA");
    params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    let ca_key = KeyPair::generate().expect("CA key");
    let ca = params.self_signed(&ca_key).expect("CA certificate");
    let issuer = Issuer::new(params, ca_key);
    let leaf_key = KeyPair::generate().expect("leaf key");
    let leaf = CertificateParams::new(vec!["fixture.test".into()])
        .expect("leaf parameters")
        .signed_by(&leaf_key, &issuer)
        .expect("leaf certificate");
    fs::write(directory.join("root.pem"), ca.pem()).expect("root PEM");
    fs::write(directory.join("leaf.der"), leaf.der()).expect("leaf DER");
    fs::write(directory.join("key.der"), leaf_key.serialize_der()).expect("leaf key DER");
    let ca = X509::from_der(ca.der()).expect("root X509");
    fs::write(directory.join("certs").join(format!("{:08x}.0", ca.subject_name_hash())), ca.to_pem().expect("CA PEM"))
        .expect("hashed root file");

    let mut failures = Vec::new();
    for (source, value) in [("SSL_CERT_FILE", directory.join("root.pem")), ("SSL_CERT_DIR", directory.join("certs"))] {
        let output = Command::new(std::env::current_exe().expect("test executable"))
            .args(["--exact", "standard_builder_uses_only_bundled_and_explicit_roots", "--nocapture"])
            .env_remove("SSL_CERT_FILE")
            .env_remove("SSL_CERT_DIR")
            .env(source, value)
            .env(FIXTURE_ENV, &directory)
            .output()
            .expect("child process");
        if !output.status.success() {
            failures.push(format!(
                "{source}: {}\n{}",
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr),
            ));
        }
    }
    fs::remove_dir_all(directory).expect("remove fixture directory");
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

fn check_environment_root(directory: &Path) {
    let plain = SslConnector::builder(SslMethod::tls()).expect("plain connector").build();
    assert!(handshake(&plain, directory), "the environment trust source must work with plain BoringSSL");

    let standard = configure_builder("chrome_stable").expect("standard connector").build();
    assert!(!handshake(&standard, directory), "environment roots must not extend the bundled trust store");

    let mut explicit = configure_builder("chrome_stable").expect("explicit connector");
    let root = X509::from_pem(&fs::read(directory.join("root.pem")).expect("root PEM")).expect("root certificate");
    explicit.cert_store_mut().add_cert(root).expect("explicit root");
    assert!(handshake(&explicit.build(), directory), "explicit caller roots must remain supported");
}

fn handshake(connector: &SslConnector, directory: &Path) -> bool {
    let certificate = X509::from_der(&fs::read(directory.join("leaf.der")).expect("leaf DER")).expect("leaf X509");
    let key = PKey::private_key_from_der(&fs::read(directory.join("key.der")).expect("key DER")).expect("private key");
    let mut acceptor = SslAcceptor::mozilla_intermediate_v5(SslMethod::tls()).expect("acceptor");
    acceptor.set_certificate(&certificate).expect("server certificate");
    acceptor.set_private_key(&key).expect("server key");
    let acceptor = acceptor.build();
    let listener = TcpListener::bind("127.0.0.1:0").expect("loopback listener");
    let address = listener.local_addr().expect("listener address");
    let server = thread::spawn(move || {
        let (socket, _) = listener.accept().expect("accept client");
        socket.set_read_timeout(Some(Duration::from_secs(3))).expect("server read timeout");
        socket.set_write_timeout(Some(Duration::from_secs(3))).expect("server write timeout");
        if let Ok(mut tls) = acceptor.accept(socket) {
            let _ = tls.write_all(b"ok");
        }
    });
    let socket = TcpStream::connect(address).expect("connect fixture");
    socket.set_read_timeout(Some(Duration::from_secs(3))).expect("client read timeout");
    socket.set_write_timeout(Some(Duration::from_secs(3))).expect("client write timeout");
    let accepted = match connector.connect("fixture.test", socket) {
        Ok(mut tls) => {
            let mut response = [0; 2];
            tls.read_exact(&mut response).is_ok() && response == *b"ok"
        }
        Err(_) => false,
    };
    server.join().expect("server thread");
    accepted
}
