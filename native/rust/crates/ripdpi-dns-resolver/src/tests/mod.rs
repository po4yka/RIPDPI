use super::*;

use crypto_box::aead::Aead;
use crypto_box::{ChaChaBox, PublicKey as CryptoPublicKey, SecretKey as CryptoSecretKey};
use hickory_proto::op::{Message, MessageType, OpCode, Query, ResponseCode};
use hickory_proto::rr::rdata::{A, TXT};
use hickory_proto::rr::{Name, RData, Record, RecordType};
use rcgen::generate_simple_self_signed;
use ring::signature::{Ed25519KeyPair, KeyPair};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

#[derive(Clone)]
struct DnsCryptTestServer {
    provider_public_key_hex: String,
    provider_name: String,
    certificate: DnsCryptCachedCertificate,
    resolver_secret: CryptoSecretKey,
}

fn build_query(name: &str) -> Vec<u8> {
    build_dns_query(name, RecordType::A).expect("query serializes")
}

fn build_response(query: &[u8], answer_ip: Ipv4Addr) -> Vec<u8> {
    let request = Message::from_vec(query).expect("query parses");
    let mut response = Message::new();
    response
        .set_id(request.id())
        .set_message_type(MessageType::Response)
        .set_op_code(OpCode::Query)
        .set_recursion_desired(request.recursion_desired())
        .set_recursion_available(true)
        .set_response_code(ResponseCode::NoError);
    for query in request.queries() {
        response.add_query(query.clone());
        if query.query_type() == RecordType::A {
            response.add_answer(Record::from_rdata(query.name().clone(), 60, RData::A(A(answer_ip))));
        }
    }
    response.to_vec().expect("response serializes")
}

#[tokio::test]
async fn doh_exchange_uses_direct_bootstrap_over_https() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 10);
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let server_config = Arc::new(
        ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![certificate_der.clone()], key_der)
            .expect("server config"),
    );
    let response_body = build_response(&query, answer_ip);
    let server_query = query.clone();
    let server_response = response_body.clone();
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_https_doh(stream, server_config, &server_query, &server_response);
    });

    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("fixture".to_string()),
            host: "fixture.test".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: Some(format!("https://fixture.test:{port}/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
    )
    .expect("resolver builds");

    let response = resolver.exchange(&query).await.expect("DoH response");
    let answers = extract_ip_answers(&response).expect("answers parse");
    assert_eq!(answers, vec![answer_ip.to_string()]);
    server.join().expect("server joins");
}

#[tokio::test]
async fn exchange_with_metadata_reports_endpoint_and_latency() {
    let query = build_query("fixture.test");
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let server_config = Arc::new(
        ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![certificate_der.clone()], key_der)
            .expect("server config"),
    );
    let response_body = build_response(&query, Ipv4Addr::new(198, 18, 0, 42));
    let server_query = query.clone();
    let server_response = response_body.clone();
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_https_doh(stream, server_config, &server_query, &server_response);
    });

    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("fixture".to_string()),
            host: "fixture.test".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: Some(format!("https://fixture.test:{port}/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
    )
    .expect("resolver builds");

    let success = resolver.exchange_with_metadata(&query).await.expect("exchange metadata");
    assert_eq!(success.endpoint_label, format!("https://fixture.test:{port}/dns-query"));
    assert!(success.latency_ms <= 4_000);
    assert_eq!(extract_ip_answers(&success.response_bytes).expect("answers"), vec!["198.18.0.42".to_string()]);
    server.join().expect("server joins");
}

#[tokio::test]
async fn direct_doh_connect_hooks_are_used_for_manual_transport() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 44);
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let server_config = Arc::new(
        ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![certificate_der.clone()], key_der)
            .expect("server config"),
    );
    let server_query = query.clone();
    let server_response = build_response(&query, answer_ip);
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_https_doh(stream, server_config, &server_query, &server_response);
    });

    let connects = Arc::new(AtomicUsize::new(0));
    let connect_hook_calls = connects.clone();
    let resolver = EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("fixture".to_string()),
            host: "fixture.test".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: Some(format!("https://fixture.test:{port}/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
        EncryptedDnsConnectHooks::new().with_direct_tcp_connector(move |target, timeout| {
            connect_hook_calls.fetch_add(1, Ordering::Relaxed);
            TcpStream::connect_timeout(&target, timeout)
        }),
    )
    .expect("resolver builds");

    let response = resolver.exchange(&query).await.expect("DoH response");
    assert_eq!(extract_ip_answers(&response).expect("answers parse"), vec![answer_ip.to_string()]);
    assert!(connects.load(Ordering::Relaxed) >= 1, "DoH should use the direct TCP hook");
    server.join().expect("server joins");
}

#[tokio::test]
async fn doh_exchange_supports_socks_transport() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 10);
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let server_config = Arc::new(
        ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![certificate_der.clone()], key_der)
            .expect("server config"),
    );
    let response_body = build_response(&query, answer_ip);
    let server_query = query.clone();
    let server_response = response_body.clone();
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_https_doh(stream, server_config, &server_query, &server_response);
    });
    let (proxy_port, proxy_handle) = start_socks_proxy("fixture.test", port, 1);

    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("fixture".to_string()),
            host: "fixture.test".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: Vec::new(),
            doh_url: Some(format!("https://fixture.test:{port}/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Socks5 { host: "127.0.0.1".to_string(), port: proxy_port },
        DEFAULT_TIMEOUT,
        vec![certificate_der],
    )
    .expect("resolver builds");

    let response = resolver.exchange(&query).await.expect("DoH response");
    let answers = extract_ip_answers(&response).expect("answers parse");
    assert_eq!(answers, vec![answer_ip.to_string()]);
    proxy_handle.join().expect("proxy thread completes");
    server.join().expect("server thread completes");
}

#[test]
fn dot_exchange_supports_direct_and_tls_validation() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 11);
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let server_config = Arc::new(
        ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![certificate_der.clone()], key_der)
            .expect("server config"),
    );
    let server_query = query.clone();
    let server_response = build_response(&query, answer_ip);
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_dot(stream, server_config, &server_query, &server_response);
    });

    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: Some("fixture".to_string()),
            host: "fixture.test".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
    )
    .expect("resolver builds");

    let response = resolver.exchange_blocking(&query).expect("DoT response");
    let answers = extract_ip_answers(&response).expect("answers parse");
    assert_eq!(answers, vec![answer_ip.to_string()]);
    server.join().expect("server thread completes");
}

#[test]
fn direct_dot_connect_hooks_are_used() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 45);
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let server_config = Arc::new(
        ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![certificate_der.clone()], key_der)
            .expect("server config"),
    );
    let server_query = query.clone();
    let server_response = build_response(&query, answer_ip);
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_dot(stream, server_config, &server_query, &server_response);
    });

    let connects = Arc::new(AtomicUsize::new(0));
    let connect_hook_calls = connects.clone();
    let resolver = EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: Some("fixture".to_string()),
            host: "fixture.test".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
        EncryptedDnsConnectHooks::new().with_direct_tcp_connector(move |target, timeout| {
            connect_hook_calls.fetch_add(1, Ordering::Relaxed);
            TcpStream::connect_timeout(&target, timeout)
        }),
    )
    .expect("resolver builds");

    let response = resolver.exchange_blocking(&query).expect("DoT response");
    assert_eq!(extract_ip_answers(&response).expect("answers parse"), vec![answer_ip.to_string()]);
    assert!(connects.load(Ordering::Relaxed) >= 1, "DoT should use the direct TCP hook");
    server.join().expect("server thread completes");
}

#[test]
fn dot_exchange_supports_socks_transport() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 12);
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let server_config = Arc::new(
        ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![certificate_der.clone()], key_der)
            .expect("server config"),
    );
    let server_query = query.clone();
    let server_response = build_response(&query, answer_ip);
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_dot(stream, server_config, &server_query, &server_response);
    });
    let (proxy_port, proxy_handle) = start_socks_proxy("fixture.test", port, 1);

    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: Some("fixture".to_string()),
            host: "fixture.test".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: Vec::new(),
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Socks5 { host: "127.0.0.1".to_string(), port: proxy_port },
        DEFAULT_TIMEOUT,
        vec![certificate_der],
    )
    .expect("resolver builds");

    let response = resolver.exchange_blocking(&query).expect("DoT response");
    let answers = extract_ip_answers(&response).expect("answers parse");
    assert_eq!(answers, vec![answer_ip.to_string()]);
    drop(resolver);
    proxy_handle.join().expect("proxy thread completes");
    server.join().expect("server thread completes");
}

#[tokio::test]
async fn dot_exchange_reuses_pooled_tls_connection() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 21);
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let server_config = Arc::new(
        ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![certificate_der.clone()], key_der)
            .expect("server config"),
    );
    let response = build_response(&query, answer_ip);
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_dot_sequence(
            stream,
            server_config,
            &[query.clone(), query.clone()],
            &[response.clone(), response.clone()],
        );
    });

    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: Some("fixture".to_string()),
            host: "fixture.test".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
    )
    .expect("resolver builds");

    let first = resolver.exchange(&build_query("fixture.test")).await.expect("first DoT response");
    let second = resolver.exchange(&build_query("fixture.test")).await.expect("second DoT response");
    let expected_answers = vec![answer_ip.to_string()];
    assert_eq!(extract_ip_answers(&first).expect("first answers parse"), expected_answers);
    assert_eq!(extract_ip_answers(&second).expect("second answers parse"), expected_answers);
    server.join().expect("server thread completes");
}

#[test]
fn dnscrypt_exchange_supports_direct_transport() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 13);
    let server = DnsCryptTestServer::new("resolver.test");
    let (port, handle) = start_dnscrypt_server(server.clone(), build_response(&query, answer_ip));

    let resolver = EncryptedDnsResolver::new(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::DnsCrypt,
            resolver_id: Some("fixture".to_string()),
            host: "resolver.test".to_string(),
            port,
            tls_server_name: None,
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: None,
            dnscrypt_provider_name: Some(server.provider_name.clone()),
            dnscrypt_public_key: Some(server.provider_public_key_hex.clone()),
        },
        EncryptedDnsTransport::Direct,
    )
    .expect("resolver builds");

    let response = resolver.exchange_blocking(&query).expect("DNSCrypt response");
    let answers = extract_ip_answers(&response).expect("answers parse");
    assert_eq!(answers, vec![answer_ip.to_string()]);
    handle.join().expect("server thread completes");
}

#[test]
fn direct_dnscrypt_connect_hooks_are_used() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 46);
    let server = DnsCryptTestServer::new("resolver.test");
    let (port, handle) = start_dnscrypt_server(server.clone(), build_response(&query, answer_ip));
    let connects = Arc::new(AtomicUsize::new(0));
    let connect_hook_calls = connects.clone();
    let resolver = EncryptedDnsResolver::with_connect_hooks(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::DnsCrypt,
            resolver_id: Some("fixture-dnscrypt".to_string()),
            host: "127.0.0.1".to_string(),
            port,
            tls_server_name: None,
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: None,
            dnscrypt_provider_name: Some(server.provider_name.clone()),
            dnscrypt_public_key: Some(server.provider_public_key_hex.clone()),
        },
        EncryptedDnsTransport::Direct,
        EncryptedDnsConnectHooks::new().with_direct_tcp_connector(move |target, timeout| {
            connect_hook_calls.fetch_add(1, Ordering::Relaxed);
            TcpStream::connect_timeout(&target, timeout)
        }),
    )
    .expect("resolver builds");

    let response = resolver.exchange_blocking(&query).expect("DNSCrypt response");
    assert_eq!(extract_ip_answers(&response).expect("answers parse"), vec![answer_ip.to_string()]);
    assert!(connects.load(Ordering::Relaxed) >= 1, "DNSCrypt should use the direct TCP hook");
    handle.join().expect("server thread completes");
}

#[tokio::test]
async fn direct_doq_udp_bind_hooks_are_used() {
    let binds = Arc::new(AtomicUsize::new(0));
    let bind_hook_calls = binds.clone();
    let resolver = EncryptedDnsResolver::with_connect_hooks(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doq,
            resolver_id: Some("fixture-doq".to_string()),
            host: "127.0.0.1".to_string(),
            port: 853,
            tls_server_name: Some("127.0.0.1".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Direct,
        EncryptedDnsConnectHooks::new().with_direct_udp_binder(move |bind_addr| {
            bind_hook_calls.fetch_add(1, Ordering::Relaxed);
            std::net::UdpSocket::bind(bind_addr)
        }),
    )
    .expect("resolver builds");

    let _ = resolver.endpoint();
    assert_eq!(binds.load(Ordering::Relaxed), 1, "DoQ should use the direct UDP bind hook");
}

#[test]
fn dnscrypt_exchange_supports_socks_transport() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 14);
    let server = DnsCryptTestServer::new("resolver.test");
    let (port, handle) = start_dnscrypt_server(server.clone(), build_response(&query, answer_ip));
    let (proxy_port, proxy_handle) = start_socks_proxy("resolver.test", port, 2);

    let resolver = EncryptedDnsResolver::new(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::DnsCrypt,
            resolver_id: Some("fixture".to_string()),
            host: "resolver.test".to_string(),
            port,
            tls_server_name: None,
            bootstrap_ips: Vec::new(),
            doh_url: None,
            dnscrypt_provider_name: Some(server.provider_name.clone()),
            dnscrypt_public_key: Some(server.provider_public_key_hex.clone()),
        },
        EncryptedDnsTransport::Socks5 { host: "127.0.0.1".to_string(), port: proxy_port },
    )
    .expect("resolver builds");

    let response = resolver.exchange_blocking(&query).expect("DNSCrypt response");
    let answers = extract_ip_answers(&response).expect("answers parse");
    assert_eq!(answers, vec![answer_ip.to_string()]);
    drop(resolver);
    proxy_handle.join().expect("proxy thread completes");
    handle.join().expect("server thread completes");
}

#[tokio::test]
async fn dnscrypt_exchange_reuses_pooled_tcp_connection() {
    let query = build_query("fixture.test");
    let answer_ip = Ipv4Addr::new(198, 18, 0, 22);
    let server = DnsCryptTestServer::new("resolver.test");
    let (port, handle) = start_reusable_dnscrypt_server(server.clone(), build_response(&query, answer_ip), 2);

    let resolver = EncryptedDnsResolver::new(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::DnsCrypt,
            resolver_id: Some("fixture".to_string()),
            host: "resolver.test".to_string(),
            port,
            tls_server_name: None,
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: None,
            dnscrypt_provider_name: Some(server.provider_name.clone()),
            dnscrypt_public_key: Some(server.provider_public_key_hex.clone()),
        },
        EncryptedDnsTransport::Direct,
    )
    .expect("resolver builds");

    let first = resolver.exchange(&build_query("fixture.test")).await.expect("first DNSCrypt response");
    let second = resolver.exchange(&build_query("fixture.test")).await.expect("second DNSCrypt response");
    let expected_answers = vec![answer_ip.to_string()];
    assert_eq!(extract_ip_answers(&first).expect("first answers parse"), expected_answers);
    assert_eq!(extract_ip_answers(&second).expect("second answers parse"), expected_answers);
    handle.join().expect("server thread completes");
}

#[test]
fn h2_only_doh_server_is_supported() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("local addr").port();
    let certificate = generate_simple_self_signed(vec!["localhost".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let mut server_config = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions")
        .with_no_client_auth()
        .with_single_cert(vec![certificate_der.clone()], key_der)
        .expect("server config");
    server_config.alpn_protocols = vec![b"h2".to_vec()];
    let server_config = Arc::new(server_config);

    let expected_query = build_query("fixture.test");
    let expected_response = expected_query.clone();
    let server_query = expected_query.clone();
    let server_response = expected_response.clone();
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        serve_h2_doh(stream, server_config, &server_query, &server_response);
    });

    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doh,
            resolver_id: Some("fixture".to_string()),
            host: "localhost".to_string(),
            port,
            tls_server_name: Some("localhost".to_string()),
            bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            doh_url: Some(format!("https://localhost:{port}/dns-query")),
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Direct,
        DEFAULT_TIMEOUT,
        vec![certificate_der],
    )
    .expect("resolver builds");

    let response = resolver.exchange_blocking(&expected_query).expect("blocking exchange");
    assert_eq!(response, expected_response);
    server.join().expect("server thread completes");
}

#[test]
fn error_kind_maps_common_failures() {
    assert_eq!(EncryptedDnsError::MissingBootstrapIps.kind(), EncryptedDnsErrorKind::Bootstrap);
    assert_eq!(EncryptedDnsError::Tls("handshake failed".to_string()).kind(), EncryptedDnsErrorKind::Tls,);
    assert_eq!(EncryptedDnsError::DnsCryptDecrypt("bad nonce".to_string()).kind(), EncryptedDnsErrorKind::DnsCrypt,);
    assert_eq!(EncryptedDnsError::DnsParse("bad packet".to_string()).kind(), EncryptedDnsErrorKind::Decode,);
}

impl DnsCryptTestServer {
    fn new(provider_suffix: &str) -> Self {
        let provider_key_pair = Ed25519KeyPair::from_seed_unchecked(&[7u8; 32]).expect("ed25519 key pair from seed");
        let provider_public_bytes: [u8; 32] =
            provider_key_pair.public_key().as_ref().try_into().expect("ed25519 public key is 32 bytes");
        let resolver_secret = CryptoSecretKey::from([9u8; 32]);
        let resolver_public = resolver_secret.public_key();
        let valid_from = unix_time_secs().saturating_sub(60);
        let valid_until = valid_from.saturating_add(86_400);
        let mut client_magic = [0u8; 8];
        client_magic.copy_from_slice(&resolver_public.as_bytes()[..8]);

        let mut inner = [0u8; 52];
        inner[..32].copy_from_slice(resolver_public.as_bytes());
        inner[32..40].copy_from_slice(&client_magic);
        inner[40..44].copy_from_slice(&1u32.to_be_bytes());
        inner[44..48].copy_from_slice(&valid_from.to_be_bytes());
        inner[48..52].copy_from_slice(&valid_until.to_be_bytes());
        let signature = provider_key_pair.sign(&inner);

        let mut cert_bytes = Vec::with_capacity(DNSCRYPT_CERT_SIZE);
        cert_bytes.extend_from_slice(&DNSCRYPT_CERT_MAGIC);
        cert_bytes.extend_from_slice(&DNSCRYPT_ES_VERSION.to_be_bytes());
        cert_bytes.extend_from_slice(&0u16.to_be_bytes());
        cert_bytes.extend_from_slice(signature.as_ref());
        cert_bytes.extend_from_slice(&inner);
        let certificate = parse_dnscrypt_certificate(
            &cert_bytes,
            &provider_public_bytes,
            &format!("2.dnscrypt-cert.{provider_suffix}"),
        )
        .expect("certificate parses");

        Self {
            provider_public_key_hex: hex::encode(provider_public_bytes),
            provider_name: format!("2.dnscrypt-cert.{provider_suffix}"),
            certificate,
            resolver_secret,
        }
    }

    fn certificate_bytes(&self) -> Vec<u8> {
        let mut inner = [0u8; 52];
        inner[..32].copy_from_slice(&self.certificate.resolver_public_key);
        inner[32..40].copy_from_slice(&self.certificate.client_magic);
        inner[40..44].copy_from_slice(&1u32.to_be_bytes());
        inner[44..48].copy_from_slice(&self.certificate.valid_from.to_be_bytes());
        inner[48..52].copy_from_slice(&self.certificate.valid_until.to_be_bytes());
        let signing = Ed25519KeyPair::from_seed_unchecked(&[7u8; 32]).expect("ed25519 key pair from seed");
        let signature = signing.sign(&inner);
        let mut cert_bytes = Vec::with_capacity(DNSCRYPT_CERT_SIZE);
        cert_bytes.extend_from_slice(&DNSCRYPT_CERT_MAGIC);
        cert_bytes.extend_from_slice(&DNSCRYPT_ES_VERSION.to_be_bytes());
        cert_bytes.extend_from_slice(&0u16.to_be_bytes());
        cert_bytes.extend_from_slice(signature.as_ref());
        cert_bytes.extend_from_slice(&inner);
        cert_bytes
    }
}

fn start_dnscrypt_server(server: DnsCryptTestServer, response_packet: Vec<u8>) -> (u16, thread::JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("dnscrypt listener");
    let port = listener.local_addr().expect("dnscrypt local addr").port();
    let handle = thread::spawn(move || {
        for _ in 0..2 {
            let (mut stream, _) = listener.accept().expect("dnscrypt accept");
            let packet = read_length_prefixed_frame(&mut stream).expect("dnscrypt tcp read");
            if let Ok(message) = Message::from_vec(&packet) {
                let is_txt_cert_query = message.query().is_some_and(|query| query.query_type() == RecordType::TXT);
                if is_txt_cert_query {
                    let response =
                        build_dnscrypt_cert_response(&packet, &server.provider_name, &server.certificate_bytes());
                    write_length_prefixed_frame(&mut stream, &response).expect("dnscrypt cert write");
                    continue;
                }
            }

            let mut client_public = [0u8; 32];
            client_public.copy_from_slice(&packet[8..40]);
            let mut nonce = [0u8; DNSCRYPT_NONCE_SIZE];
            nonce[..DNSCRYPT_QUERY_NONCE_HALF].copy_from_slice(&packet[40..52]);
            let crypto_box = ChaChaBox::new(&CryptoPublicKey::from(client_public), &server.resolver_secret);
            serve_dnscrypt_query(&packet, &server, &response_packet, &crypto_box, &nonce, &mut stream);
        }
    });
    (port, handle)
}

fn start_reusable_dnscrypt_server(
    server: DnsCryptTestServer,
    response_packet: Vec<u8>,
    expected_queries: usize,
) -> (u16, thread::JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("dnscrypt listener");
    let port = listener.local_addr().expect("dnscrypt local addr").port();
    let handle = thread::spawn(move || {
        let (mut cert_stream, _) = listener.accept().expect("dnscrypt cert accept");
        let cert_packet = read_length_prefixed_frame(&mut cert_stream).expect("dnscrypt cert read");
        let cert_response =
            build_dnscrypt_cert_response(&cert_packet, &server.provider_name, &server.certificate_bytes());
        write_length_prefixed_frame(&mut cert_stream, &cert_response).expect("dnscrypt cert write");
        drop(cert_stream);

        let (mut query_stream, _) = listener.accept().expect("dnscrypt query accept");
        for _ in 0..expected_queries {
            let packet = read_length_prefixed_frame(&mut query_stream).expect("dnscrypt pooled tcp read");
            let mut client_public = [0u8; 32];
            client_public.copy_from_slice(&packet[8..40]);
            let mut nonce = [0u8; DNSCRYPT_NONCE_SIZE];
            nonce[..DNSCRYPT_QUERY_NONCE_HALF].copy_from_slice(&packet[40..52]);
            let crypto_box = ChaChaBox::new(&CryptoPublicKey::from(client_public), &server.resolver_secret);
            serve_dnscrypt_query(&packet, &server, &response_packet, &crypto_box, &nonce, &mut query_stream);
        }
    });
    (port, handle)
}

fn serve_dnscrypt_query(
    packet: &[u8],
    _server: &DnsCryptTestServer,
    response_packet: &[u8],
    crypto_box: &ChaChaBox,
    nonce: &[u8; DNSCRYPT_NONCE_SIZE],
    stream: &mut TcpStream,
) {
    let decrypted = crypto_box.decrypt((&nonce[..]).into(), &packet[52..]).expect("dnscrypt request decrypt");
    let query = dnscrypt_unpad(&decrypted).expect("dnscrypt request unpad");
    let expected = build_query("fixture.test");
    // Compare queries ignoring the 2-byte transaction ID (now randomized).
    assert_eq!(query[2..], expected[2..]);

    let mut response_nonce = *nonce;
    response_nonce[DNSCRYPT_QUERY_NONCE_HALF..].fill(0x11);
    let ciphertext = crypto_box
        .encrypt((&response_nonce).into(), dnscrypt_pad(response_packet).as_slice())
        .expect("dnscrypt response encrypt");
    let mut wrapped = Vec::with_capacity(8 + DNSCRYPT_NONCE_SIZE + ciphertext.len());
    wrapped.extend_from_slice(&DNSCRYPT_RESPONSE_MAGIC);
    wrapped.extend_from_slice(&response_nonce);
    wrapped.extend_from_slice(&ciphertext);
    write_length_prefixed_frame(stream, &wrapped).expect("dnscrypt response write");
}

fn build_dnscrypt_cert_response(query: &[u8], provider_name: &str, cert_bytes: &[u8]) -> Vec<u8> {
    let request = Message::from_vec(query).expect("cert query parses");
    let mut response = Message::new();
    response
        .set_id(request.id())
        .set_message_type(MessageType::Response)
        .set_op_code(OpCode::Query)
        .set_recursion_desired(request.recursion_desired())
        .set_recursion_available(true)
        .set_response_code(ResponseCode::NoError)
        .add_query(Query::query(Name::from_ascii(provider_name).expect("provider name"), RecordType::TXT));
    response.add_answer(Record::from_rdata(
        Name::from_ascii(provider_name).expect("provider name"),
        600,
        RData::TXT(TXT::from_bytes(vec![cert_bytes])),
    ));
    response.to_vec().expect("cert response encodes")
}

fn serve_dot(stream: TcpStream, config: Arc<ServerConfig>, expected_query: &[u8], response_body: &[u8]) {
    let connection = ServerConnection::new(config).expect("server connection");
    let mut tls_stream = StreamOwned::new(connection, stream);
    while tls_stream.conn.is_handshaking() {
        tls_stream.conn.complete_io(&mut tls_stream.sock).expect("TLS handshake completes");
    }
    let query = read_length_prefixed_frame(&mut tls_stream).expect("read DoT query");
    assert_eq!(query, expected_query);
    write_length_prefixed_frame(&mut tls_stream, response_body).expect("write DoT response");
}

fn serve_dot_sequence(
    stream: TcpStream,
    config: Arc<ServerConfig>,
    expected_queries: &[Vec<u8>],
    response_bodies: &[Vec<u8>],
) {
    let connection = ServerConnection::new(config).expect("server connection");
    let mut tls_stream = StreamOwned::new(connection, stream);
    while tls_stream.conn.is_handshaking() {
        tls_stream.conn.complete_io(&mut tls_stream.sock).expect("TLS handshake completes");
    }
    for (expected_query, response_body) in expected_queries.iter().zip(response_bodies) {
        let query = read_length_prefixed_frame(&mut tls_stream).expect("read DoT query");
        // Compare ignoring the 2-byte transaction ID (now randomized).
        assert_eq!(query[2..], expected_query[2..]);
        write_length_prefixed_frame(&mut tls_stream, response_body).expect("write DoT response");
    }
}

fn serve_https_doh(stream: TcpStream, config: Arc<ServerConfig>, expected_query: &[u8], response_body: &[u8]) {
    let response = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: {DNS_MESSAGE_MEDIA_TYPE}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        response_body.len()
    );
    serve_https_doh_raw(stream, config, expected_query, &[response.into_bytes(), response_body.to_vec()]);
}

fn serve_https_doh_raw(
    stream: TcpStream,
    config: Arc<ServerConfig>,
    expected_query: &[u8],
    response_parts: &[Vec<u8>],
) {
    let connection = ServerConnection::new(config).expect("server connection");
    let mut tls_stream = StreamOwned::new(connection, stream);
    while tls_stream.conn.is_handshaking() {
        tls_stream.conn.complete_io(&mut tls_stream.sock).expect("TLS handshake completes");
    }

    let (request_line, body) = read_http_request(&mut tls_stream);
    // DoH spec (RFC 8484) says the request path is the configured URL verbatim.
    // Some DoH servers (e.g., Mullvad) return HTTP 400 for unknown query params
    // like `?_r=...`. Require the exact request line here so any future
    // "randomization" regression is caught by the test suite.
    assert_eq!(
        request_line, "POST /dns-query HTTP/1.1",
        "DoH request line must not carry extra query params (got: {request_line})",
    );
    assert_eq!(body, expected_query);

    for part in response_parts {
        tls_stream.write_all(part).expect("write response part");
    }
    tls_stream.flush().expect("flush response");
}

fn read_http_request(stream: &mut impl Read) -> (String, Vec<u8>) {
    let mut raw = Vec::new();
    let mut chunk = [0u8; 1];
    while !raw.windows(4).any(|window| window == b"\r\n\r\n") {
        stream.read_exact(&mut chunk).expect("read request");
        raw.push(chunk[0]);
    }
    let request = String::from_utf8_lossy(&raw).into_owned();
    let request_line = request.lines().next().expect("request line").to_string();
    let content_length = request
        .lines()
        .find_map(|line| {
            let (name, value) = line.split_once(':')?;
            name.eq_ignore_ascii_case("content-length").then(|| value.trim().parse::<usize>().ok()).flatten()
        })
        .unwrap_or(0);
    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        stream.read_exact(&mut body).expect("read body");
    }
    (request_line, body)
}

fn start_socks_proxy(
    expected_host: &str,
    target_port: u16,
    expected_connections: usize,
) -> (u16, thread::JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("proxy listener");
    let port = listener.local_addr().expect("proxy addr").port();
    let expected_host = expected_host.to_string();
    let handle = thread::spawn(move || {
        for _ in 0..expected_connections {
            let (mut client, _) = listener.accept().expect("proxy accept");

            let mut greeting = [0u8; 3];
            client.read_exact(&mut greeting).expect("proxy greeting");
            assert_eq!(greeting, [0x05, 0x01, 0x00]);
            client.write_all(&[0x05, 0x00]).expect("proxy greeting reply");

            let mut header = [0u8; 4];
            client.read_exact(&mut header).expect("proxy request header");
            assert_eq!(&header[..3], &[0x05, 0x01, 0x00]);
            let (host, port) = read_socks_target(&mut client, header[3]);
            assert_eq!(host, expected_host);
            assert_eq!(port, target_port);

            let upstream = TcpStream::connect((Ipv4Addr::LOCALHOST, target_port)).expect("proxy upstream");
            let [p1, p2] = target_port.to_be_bytes();
            client.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, p1, p2]).expect("proxy success reply");
            relay_proxy_streams(client, upstream);
        }
    });
    (port, handle)
}

fn read_socks_target(stream: &mut TcpStream, atyp: u8) -> (String, u16) {
    match atyp {
        0x01 => {
            let mut host = [0u8; 4];
            let mut port = [0u8; 2];
            stream.read_exact(&mut host).expect("IPv4 host");
            stream.read_exact(&mut port).expect("IPv4 port");
            (IpAddr::V4(Ipv4Addr::from(host)).to_string(), u16::from_be_bytes(port))
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).expect("domain length");
            let mut host = vec![0u8; len[0] as usize];
            let mut port = [0u8; 2];
            stream.read_exact(&mut host).expect("domain host");
            stream.read_exact(&mut port).expect("domain port");
            (String::from_utf8(host).expect("valid domain"), u16::from_be_bytes(port))
        }
        other => panic!("unexpected SOCKS address type: {other}"),
    }
}

fn relay_proxy_streams(client: TcpStream, upstream: TcpStream) {
    let mut client_reader = client.try_clone().expect("client clone");
    let mut client_writer = client;
    let mut upstream_reader = upstream.try_clone().expect("upstream clone");
    let mut upstream_writer = upstream;
    let to_upstream = thread::spawn(move || {
        let _ = std::io::copy(&mut client_reader, &mut upstream_writer);
    });
    let _ = std::io::copy(&mut upstream_reader, &mut client_writer);
    to_upstream.join().expect("relay join");
}

fn serve_h2_doh(stream: TcpStream, config: Arc<ServerConfig>, expected_query: &[u8], response_body: &[u8]) {
    let connection = ServerConnection::new(config).expect("server connection");
    let mut tls_stream = StreamOwned::new(connection, stream);
    while tls_stream.conn.is_handshaking() {
        tls_stream.conn.complete_io(&mut tls_stream.sock).expect("TLS handshake completes");
    }
    assert_eq!(tls_stream.conn.alpn_protocol(), Some(b"h2".as_slice()));

    let mut preface = [0u8; 24];
    tls_stream.read_exact(&mut preface).expect("h2 preface");
    assert_eq!(&preface, b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");

    let mut frames = Vec::new();
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    while std::time::Instant::now() < deadline {
        let mut header = [0u8; 9];
        tls_stream.read_exact(&mut header).expect("frame header");
        let length = ((header[0] as usize) << 16) | ((header[1] as usize) << 8) | header[2] as usize;
        let frame_type = header[3];
        let flags = header[4];
        let stream_id = u32::from_be_bytes([header[5] & 0x7f, header[6], header[7], header[8]]);
        let mut payload = vec![0u8; length];
        tls_stream.read_exact(&mut payload).expect("frame payload");
        frames.push((frame_type, flags, stream_id, payload.clone()));
        if frame_type == 0x0 && flags & 0x1 == 0x1 && stream_id == 1 {
            break;
        }
    }

    let mut body = Vec::new();
    for (frame_type, _, stream_id, payload) in &frames {
        if *frame_type == 0x0 && *stream_id == 1 {
            body.extend_from_slice(payload);
        }
    }
    assert_eq!(body, expected_query);

    let settings_ack = [0x00, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00];
    let headers_frame = [0x00, 0x00, 0x01, 0x01, 0x04, 0x00, 0x00, 0x00, 0x01, 0x88];
    let mut data_frame = vec![
        ((response_body.len() >> 16) & 0xff) as u8,
        ((response_body.len() >> 8) & 0xff) as u8,
        (response_body.len() & 0xff) as u8,
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x01,
    ];
    data_frame.extend_from_slice(response_body);
    tls_stream.write_all(&settings_ack).expect("write settings ack");
    tls_stream.write_all(&headers_frame).expect("write headers frame");
    tls_stream.write_all(&data_frame).expect("write data frame");
    tls_stream.flush().expect("flush h2 response");
}

// ---------------------------------------------------------------------------
// Health registry tests
// ---------------------------------------------------------------------------

#[test]
fn health_ewma_decays_toward_new_observations() {
    let reg = HealthRegistry::new(Duration::from_millis(100));
    reg.record_endpoint_outcome("ep", true, 50);
    std::thread::sleep(Duration::from_millis(150));
    reg.record_endpoint_outcome("ep", false, 500);
    let snap = reg.snapshot("ep").expect("snapshot exists");
    assert!(snap.ewma_success_rate < 1.0, "success rate should decay after failure: {:.3}", snap.ewma_success_rate);
}

#[test]
fn health_latency_score_clamps_at_cap() {
    // Use a very short half-life so EWMA converges quickly to new observations.
    let reg = HealthRegistry::new(Duration::from_millis(1));
    for _ in 0..20 {
        std::thread::sleep(Duration::from_millis(5));
        reg.record_endpoint_outcome("slow", true, 10_000);
    }
    let snap = reg.snapshot("slow").expect("snapshot exists");
    // With a 1ms half-life and 5ms gaps, alpha is ~0.99 per observation,
    // so the EWMA should converge close to 10000ms (well above the 2000ms cap).
    assert!(
        snap.ewma_latency_ms > 1000.0,
        "latency should move significantly toward observations: {:.1}",
        snap.ewma_latency_ms
    );
}

#[test]
fn health_bootstrap_ip_ranking_preserves_all_ips() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    let ip1 = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1));
    let ip2 = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2));
    let ip3 = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 3));
    for _ in 0..10 {
        reg.record_bootstrap_outcome(ip1, false, 800);
    }
    for _ in 0..10 {
        reg.record_bootstrap_outcome(ip2, true, 20);
    }
    for _ in 0..10 {
        reg.record_bootstrap_outcome(ip3, true, 400);
    }
    let ranked = reg.rank_bootstrap_ips(&[ip1, ip2, ip3]);
    assert_eq!(ranked.len(), 3, "all IPs must be returned");
    let mut sorted = ranked.clone();
    sorted.sort();
    let mut expected = vec![ip1, ip2, ip3];
    expected.sort();
    assert_eq!(sorted, expected, "no IPs should be dropped");
}

#[test]
fn health_bootstrap_ip_ranking_with_equal_scores() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    let ips: Vec<IpAddr> = vec![
        Ipv4Addr::new(192, 168, 1, 1).into(),
        Ipv4Addr::new(192, 168, 1, 2).into(),
        Ipv4Addr::new(192, 168, 1, 3).into(),
    ];
    let ranked = reg.rank_bootstrap_ips(&ips);
    assert_eq!(ranked.len(), 3, "all IPs must be returned with equal scores");
}

#[test]
fn health_snapshot_returns_none_for_unknown_endpoint() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    assert!(reg.snapshot("nonexistent").is_none());
}

#[test]
fn health_observation_count_increments() {
    let reg = HealthRegistry::new(Duration::from_secs(60));
    for i in 0..5 {
        reg.record_endpoint_outcome("ep", i % 2 == 0, 100);
    }
    assert_eq!(reg.observation_count("ep"), 5);
}

mod pool;
