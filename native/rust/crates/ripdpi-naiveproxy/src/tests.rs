use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;

use base64::prelude::{Engine as _, BASE64_STANDARD};
use rcgen::{CertificateParams, DistinguishedName, DnType, IsCa, KeyPair};
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::{ClientConfig as RustlsClientConfig, RootCertStore, ServerConfig as RustlsServerConfig};
use tokio::io::{copy_bidirectional, AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;
use tokio_rustls::TlsAcceptor;

use crate::config::NaiveProxyConfig;
use crate::connect_tunnel::{build_connect_request, find_header_end, parse_status_code};
use crate::relay::serve_listener;
use crate::socks5::SocksTarget;
use crate::tls::{default_tls_config, ensure_rustls_provider};

#[tokio::test]
async fn socks5_tunnel_round_trip_reaches_target_via_https_proxy() {
    let target = start_echo_server().await;
    let (proxy_config, proxy_auth_seen) = start_test_https_proxy("naive-user", "naive-pass").await;
    let local_listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
    let local_addr = local_listener.local_addr().expect("local addr");
    let mut config = proxy_config;
    config.listen = local_addr.to_string();

    let server = tokio::spawn(async move {
        serve_listener(local_listener, Arc::new(config)).await.expect("serve listener");
    });

    let mut client = TcpStream::connect(local_addr).await.expect("connect local socks");
    client.write_all(&[0x05, 0x01, 0x00]).await.expect("write greeting");
    let mut auth_reply = [0u8; 2];
    client.read_exact(&mut auth_reply).await.expect("read greeting reply");
    assert_eq!(auth_reply, [0x05, 0x00]);

    let connect_request = build_socks_connect_request("127.0.0.1", target.port());
    client.write_all(&connect_request).await.expect("write connect request");
    let mut connect_reply = [0u8; 10];
    client.read_exact(&mut connect_reply).await.expect("read connect reply");
    assert_eq!(connect_reply[1], 0x00);

    client.write_all(b"ping-through-naive").await.expect("write tunneled payload");
    let mut echoed = vec![0u8; "ping-through-naive".len()];
    client.read_exact(&mut echoed).await.expect("read echo");
    assert_eq!(&echoed, b"ping-through-naive");

    let seen_auth = proxy_auth_seen.await.expect("proxy auth seen");
    assert_eq!(seen_auth.as_deref(), Some("Basic bmFpdmUtdXNlcjpuYWl2ZS1wYXNz"));

    server.abort();
}

#[test]
fn parse_status_code_accepts_200_response() {
    let status =
        parse_status_code(b"HTTP/1.1 200 Connection Established\r\nProxy-Agent: test\r\n\r\n").expect("parse status");
    assert_eq!(status, 200);
}

#[test]
fn build_connect_request_emits_basic_auth_header() {
    let config = NaiveProxyConfig {
        listen: "127.0.0.1:11980".to_owned(),
        server: "proxy.example".to_owned(),
        server_port: 443,
        server_name: "proxy.example".to_owned(),
        username: Some("user".to_owned()),
        password: Some("pass".to_owned()),
        path: Some("/proxy".to_owned()),
        tls_config: default_tls_config(),
    };

    let request = build_connect_request(&config, &SocksTarget::Domain("example.com".to_owned(), 443));

    assert!(request.contains("CONNECT example.com:443 HTTP/1.1"));
    assert!(request.contains("Proxy-Authorization: Basic dXNlcjpwYXNz"));
    assert!(request.contains("X-Naive-Path: /proxy"));
}

async fn start_echo_server() -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind echo");
    let address = listener.local_addr().expect("echo addr");
    tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else {
                return;
            };
            tokio::spawn(async move {
                let mut buf = [0u8; 2048];
                loop {
                    let Ok(read) = socket.read(&mut buf).await else {
                        return;
                    };
                    if read == 0 {
                        return;
                    }
                    if socket.write_all(&buf[..read]).await.is_err() {
                        return;
                    }
                }
            });
        }
    });
    address
}

async fn start_test_https_proxy(
    expected_username: &str,
    expected_password: &str,
) -> (NaiveProxyConfig, oneshot::Receiver<Option<String>>) {
    ensure_rustls_provider();
    let key_pair = KeyPair::generate().expect("generate keypair");
    let mut params = CertificateParams::new(vec!["proxy.test".to_owned()]).expect("params");
    params.is_ca = IsCa::NoCa;
    let mut dn = DistinguishedName::new();
    dn.push(DnType::CommonName, "proxy.test");
    params.distinguished_name = dn;
    let cert = params.self_signed(&key_pair).expect("self sign");
    let cert_der = cert.der().clone();
    let key_der = key_pair.serialize_der();

    let mut roots = RootCertStore::empty();
    roots.add(cert_der.clone()).expect("add root");
    let client_tls = Arc::new(RustlsClientConfig::builder().with_root_certificates(roots).with_no_client_auth());

    let server_config = RustlsServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert_der], PrivateKeyDer::from(PrivatePkcs8KeyDer::from(key_der)))
        .expect("server cert");
    let acceptor = TlsAcceptor::from(Arc::new(server_config));
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind proxy");
    let address = listener.local_addr().expect("proxy addr");
    let (auth_tx, auth_rx) = oneshot::channel();
    let expected_auth = format!("Basic {}", BASE64_STANDARD.encode(format!("{expected_username}:{expected_password}")));

    tokio::spawn(async move {
        let (socket, _) = listener.accept().await.expect("accept proxy");
        let mut tls = acceptor.accept(socket).await.expect("tls accept");
        let request = read_http_headers(&mut tls).await.expect("read connect request");
        let auth_header = extract_header(&request, "proxy-authorization");
        auth_tx.send(auth_header.clone()).ok();
        assert_eq!(auth_header.as_deref(), Some(expected_auth.as_str()));
        assert!(request.contains("CONNECT 127.0.0.1:"));

        let target = extract_connect_authority(&request).expect("target authority");
        let mut upstream = TcpStream::connect(target).await.expect("connect target");
        tls.write_all(b"HTTP/1.1 200 Connection Established\r\nProxy-Agent: test\r\n\r\n")
            .await
            .expect("write proxy reply");
        let _ = copy_bidirectional(&mut tls, &mut upstream).await;
    });

    (
        NaiveProxyConfig {
            listen: "127.0.0.1:0".to_owned(),
            server: "127.0.0.1".to_owned(),
            server_port: address.port(),
            server_name: "proxy.test".to_owned(),
            username: Some(expected_username.to_owned()),
            password: Some(expected_password.to_owned()),
            path: Some("/".to_owned()),
            tls_config: client_tls,
        },
        auth_rx,
    )
}

async fn read_http_headers<S>(stream: &mut S) -> io::Result<String>
where
    S: AsyncRead + Unpin,
{
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 256];
    loop {
        let read = stream.read(&mut chunk).await?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "eof before headers"));
        }
        buffer.extend_from_slice(&chunk[..read]);
        if find_header_end(&buffer).is_some() {
            return String::from_utf8(buffer)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()));
        }
    }
}

fn extract_header(request: &str, name: &str) -> Option<String> {
    request.lines().find_map(|line| {
        let (key, value) = line.split_once(':')?;
        if key.trim().eq_ignore_ascii_case(name) {
            Some(value.trim().to_owned())
        } else {
            None
        }
    })
}

fn extract_connect_authority(request: &str) -> Option<String> {
    request.lines().next().and_then(|line| {
        let mut parts = line.split_whitespace();
        let method = parts.next()?;
        if method != "CONNECT" {
            return None;
        }
        parts.next().map(ToOwned::to_owned)
    })
}

fn build_socks_connect_request(host: &str, port: u16) -> Vec<u8> {
    let mut request = vec![0x05, 0x01, 0x00, 0x01];
    let address = host.parse::<Ipv4Addr>().expect("test helper only supports ipv4 literals");
    request.extend_from_slice(&address.octets());
    request.extend_from_slice(&port.to_be_bytes());
    request
}
