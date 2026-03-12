use std::collections::BTreeSet;
use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

use hickory_proto::op::Message;
use hickory_proto::rr::RData;
use once_cell::sync::Lazy;
use reqwest::header::{ACCEPT, CONTENT_TYPE};
use reqwest::{Client, Proxy};
use thiserror::Error;
use tokio::runtime::{Builder, Runtime};
use url::Url;

static BLOCKING_RUNTIME: Lazy<Runtime> = Lazy::new(|| {
    Builder::new_current_thread()
        .enable_all()
        .thread_name("ripdpi-doh-blocking")
        .build()
        .expect("blocking DoH runtime must initialize")
});

const DNS_MESSAGE_MEDIA_TYPE: &str = "application/dns-message";
const DEFAULT_TIMEOUT: Duration = Duration::from_secs(4);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DohEndpoint {
    pub url: String,
    pub bootstrap_ips: Vec<IpAddr>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DohTransport {
    Direct,
    Socks5 { host: String, port: u16 },
}

#[derive(Debug, Clone)]
pub struct DohResolver {
    endpoint: DohEndpoint,
    client: Client,
}

#[derive(Debug, Error)]
pub enum DohError {
    #[error("invalid DoH URL: {0}")]
    InvalidUrl(String),
    #[error("DoH URL must include a host")]
    MissingHost,
    #[error("bootstrap IPs are required for direct transport")]
    MissingBootstrapIps,
    #[error("request build failed: {0}")]
    ClientBuild(String),
    #[error("request failed: {0}")]
    Request(String),
    #[error("DoH server returned HTTP {0}")]
    HttpStatus(reqwest::StatusCode),
    #[error("response body read failed: {0}")]
    BodyRead(String),
    #[error("DNS response parse failed: {0}")]
    DnsParse(String),
}

impl DohResolver {
    pub fn new(endpoint: DohEndpoint, transport: DohTransport) -> Result<Self, DohError> {
        Self::with_timeout(endpoint, transport, DEFAULT_TIMEOUT)
    }

    pub fn with_timeout(
        endpoint: DohEndpoint,
        transport: DohTransport,
        timeout: Duration,
    ) -> Result<Self, DohError> {
        let parsed = Url::parse(&endpoint.url).map_err(|err| DohError::InvalidUrl(err.to_string()))?;
        let host = parsed.host_str().ok_or(DohError::MissingHost)?.to_string();
        let port = parsed.port_or_known_default().ok_or_else(|| DohError::InvalidUrl("missing port".to_string()))?;

        let mut builder = Client::builder().use_rustls_tls().timeout(timeout).connect_timeout(timeout);

        match &transport {
            DohTransport::Direct => {
                if endpoint.bootstrap_ips.is_empty() {
                    return Err(DohError::MissingBootstrapIps);
                }
                let addresses = endpoint
                    .bootstrap_ips
                    .iter()
                    .copied()
                    .map(|ip| SocketAddr::new(ip, port))
                    .collect::<Vec<_>>();
                builder = builder.resolve_to_addrs(host.as_str(), &addresses);
            }
            DohTransport::Socks5 { host, port } => {
                let proxy = Proxy::all(format!("socks5h://{host}:{port}"))
                    .map_err(|err| DohError::ClientBuild(err.to_string()))?;
                builder = builder.proxy(proxy);
            }
        }

        let client = builder.build().map_err(|err| DohError::ClientBuild(err.to_string()))?;
        Ok(Self { endpoint, client })
    }

    pub async fn exchange(&self, query_bytes: &[u8]) -> Result<Vec<u8>, DohError> {
        let response = self
            .client
            .post(&self.endpoint.url)
            .header(CONTENT_TYPE, DNS_MESSAGE_MEDIA_TYPE)
            .header(ACCEPT, DNS_MESSAGE_MEDIA_TYPE)
            .body(query_bytes.to_vec())
            .send()
            .await
            .map_err(|err| DohError::Request(format_error_chain(&err)))?;

        let status = response.status();
        if !status.is_success() {
            return Err(DohError::HttpStatus(status));
        }

        let body = response
            .bytes()
            .await
            .map_err(|err| DohError::BodyRead(err.to_string()))?;
        Ok(body.to_vec())
    }

    pub fn exchange_blocking(&self, query_bytes: &[u8]) -> Result<Vec<u8>, DohError> {
        BLOCKING_RUNTIME.block_on(self.exchange(query_bytes))
    }
}

fn format_error_chain(error: &(dyn std::error::Error + 'static)) -> String {
    let mut message = error.to_string();
    let mut current = error.source();
    while let Some(source) = current {
        message.push_str(": ");
        message.push_str(&source.to_string());
        current = source.source();
    }
    message
}

pub fn extract_ip_answers(packet: &[u8]) -> Result<Vec<String>, DohError> {
    let message = Message::from_vec(packet).map_err(|err| DohError::DnsParse(err.to_string()))?;
    let mut answers = BTreeSet::new();
    for record in message.answers() {
        match record.data() {
            Some(RData::A(address)) => {
                answers.insert(IpAddr::V4(address.0).to_string());
            }
            Some(RData::AAAA(address)) => {
                answers.insert(IpAddr::V6(address.0).to_string());
            }
            _ => {}
        }
    }
    Ok(answers.into_iter().collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    use hickory_proto::op::{Message, MessageType, OpCode, Query};
    use hickory_proto::rr::rdata::A;
    use hickory_proto::rr::{Name, RData, Record, RecordType};
    use local_network_fixture::{FixtureConfig, FixtureStack};
    use once_cell::sync::Lazy;
    use reqwest::Certificate;
    use rcgen::generate_simple_self_signed;
    use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    use rustls::{ServerConfig, ServerConnection, StreamOwned};
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener, TcpStream, UdpSocket};
    use std::sync::Arc;
    use std::thread;
    use std::time::Duration;

    static FIXTURE_TEST_LOCK: Lazy<std::sync::Mutex<()>> = Lazy::new(|| std::sync::Mutex::new(()));

    fn build_query(name: &str) -> Vec<u8> {
        let mut message = Message::new();
        message
            .add_query(Query::query(Name::from_ascii(name).expect("valid DNS name"), RecordType::A))
            .set_id(0x1234)
            .set_message_type(MessageType::Query)
            .set_op_code(OpCode::Query)
            .set_recursion_desired(true);
        message.to_vec().expect("query serializes")
    }

    fn build_response(query: &[u8], answer_ip: Ipv4Addr) -> Vec<u8> {
        let request = Message::from_vec(query).expect("query parses");
        let mut response = Message::new();
        response
            .set_id(request.id())
            .set_message_type(MessageType::Response)
            .set_op_code(OpCode::Query)
            .set_recursion_desired(request.recursion_desired())
            .set_recursion_available(true);
        for query in request.queries() {
            response.add_query(query.clone());
            if query.query_type() == RecordType::A {
                response.add_answer(Record::from_rdata(
                    query.name().clone(),
                    60,
                    RData::A(A(answer_ip)),
                ));
            }
        }
        response.to_vec().expect("response serializes")
    }

    fn free_port() -> u16 {
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind free port probe");
        socket.local_addr().expect("probe local addr").port()
    }

    fn ephemeral_fixture_config() -> FixtureConfig {
        FixtureConfig {
            tcp_echo_port: free_port(),
            udp_echo_port: free_port(),
            tls_echo_port: free_port(),
            dns_udp_port: free_port(),
            dns_http_port: free_port(),
            socks5_port: free_port(),
            control_port: free_port(),
            ..FixtureConfig::default()
        }
    }

    #[tokio::test]
    async fn exchange_uses_direct_bootstrap_over_http() {
        let _guard = FIXTURE_TEST_LOCK.lock().expect("fixture test lock");
        let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("fixture starts");
        let query = build_query("fixture.test");
        let resolver = DohResolver::new(
            DohEndpoint {
                url: format!("http://{}:{}/dns-query", fixture.manifest().bind_host, fixture.manifest().dns_http_port),
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            },
            DohTransport::Direct,
        )
        .expect("resolver builds");

        let response = resolver.exchange(&query).await.expect("DoH response");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![fixture.manifest().dns_answer_ipv4.clone()]);
    }

    #[tokio::test]
    async fn exchange_supports_socks_transport() {
        let query = build_query("fixture.test");
        let answer_ip = Ipv4Addr::new(198, 18, 0, 10);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let port = listener.local_addr().expect("local addr").port();
        let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
        let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.key_pair.serialize_der()));
        let server_config = Arc::new(
            ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(vec![certificate_der], key_der)
                .expect("server config"),
        );
        let response_body = build_response(&query, answer_ip);
        let server_query = query.clone();
        let server_response = response_body.clone();
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            serve_https_doh(stream, server_config, &server_query, &server_response);
        });
        let (proxy_port, proxy_handle) = start_socks_proxy("fixture.test", port);

        let root_certificate =
            Certificate::from_pem(certificate.cert.pem().as_bytes()).expect("reqwest certificate");
        let proxy = Proxy::all(format!("socks5h://127.0.0.1:{proxy_port}")).expect("proxy");
        let client = Client::builder()
            .use_rustls_tls()
            .timeout(DEFAULT_TIMEOUT)
            .connect_timeout(DEFAULT_TIMEOUT)
            .add_root_certificate(root_certificate)
            .proxy(proxy)
            .build()
            .expect("client");
        let resolver = DohResolver {
            endpoint: DohEndpoint {
                url: format!("https://fixture.test:{port}/dns-query"),
                bootstrap_ips: Vec::new(),
            },
            client,
        };

        let response = resolver.exchange(&query).await.expect("DoH response");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![answer_ip.to_string()]);
        proxy_handle.join().expect("proxy thread completes");
        server.join().expect("server thread completes");
    }

    #[test]
    fn exchange_blocking_works() {
        let _guard = FIXTURE_TEST_LOCK.lock().expect("fixture test lock");
        let fixture = FixtureStack::start(ephemeral_fixture_config()).expect("fixture starts");
        let query = build_query("fixture.test");
        let resolver = DohResolver::new(
            DohEndpoint {
                url: format!("http://{}:{}/dns-query", fixture.manifest().bind_host, fixture.manifest().dns_http_port),
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            },
            DohTransport::Direct,
        )
        .expect("resolver builds");

        let response = resolver.exchange_blocking(&query).expect("blocking exchange");
        let answers = extract_ip_answers(&response).expect("answers parse");
        assert_eq!(answers, vec![fixture.manifest().dns_answer_ipv4.clone()]);
    }

    #[test]
    #[ignore = "requires local TLS trust bootstrap"]
    fn h2_only_tls_server_is_supported() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        listener
            .set_nonblocking(false)
            .expect("listener remains blocking");
        let port = listener.local_addr().expect("local addr").port();
        let certificate = generate_simple_self_signed(vec!["localhost".to_string()]).expect("certificate");
        let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.key_pair.serialize_der()));
        let mut server_config = ServerConfig::builder()
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

        let resolver = DohResolver::new(
            DohEndpoint {
                url: format!("https://localhost:{port}/dns-query"),
                bootstrap_ips: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            },
            DohTransport::Direct,
        )
        .expect("resolver builds");

        let response = resolver.exchange_blocking(&expected_query).expect("blocking exchange");
        assert_eq!(response, expected_response);
        server.join().expect("server thread completes");
    }

    fn serve_https_doh(
        stream: TcpStream,
        config: Arc<ServerConfig>,
        expected_query: &[u8],
        response_body: &[u8],
    ) {
        let connection = ServerConnection::new(config).expect("server connection");
        let mut tls_stream = StreamOwned::new(connection, stream);
        while tls_stream.conn.is_handshaking() {
            tls_stream
                .conn
                .complete_io(&mut tls_stream.sock)
                .expect("TLS handshake completes");
        }

        let (request_line, body) = read_http_request(&mut tls_stream);
        assert_eq!(request_line, "POST /dns-query HTTP/1.1");
        assert_eq!(body, expected_query);

        let response = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: {DNS_MESSAGE_MEDIA_TYPE}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            response_body.len()
        );
        tls_stream
            .write_all(response.as_bytes())
            .expect("write headers");
        tls_stream.write_all(response_body).expect("write body");
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

    fn start_socks_proxy(expected_host: &str, target_port: u16) -> (u16, thread::JoinHandle<()>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("proxy listener");
        let port = listener.local_addr().expect("proxy addr").port();
        let expected_host = expected_host.to_string();
        let handle = thread::spawn(move || {
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
            client
                .write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, p1, p2])
                .expect("proxy success reply");
            relay_proxy_streams(client, upstream);
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
                (
                    String::from_utf8(host).expect("valid domain"),
                    u16::from_be_bytes(port),
                )
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

    fn serve_h2_doh(
        stream: TcpStream,
        config: Arc<ServerConfig>,
        expected_query: &[u8],
        response_body: &[u8],
    ) {
        let connection = ServerConnection::new(config).expect("server connection");
        let mut tls_stream = StreamOwned::new(connection, stream);
        while tls_stream.conn.is_handshaking() {
            tls_stream
                .conn
                .complete_io(&mut tls_stream.sock)
                .expect("TLS handshake completes");
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
            let stream_id =
                u32::from_be_bytes([header[5] & 0x7f, header[6], header[7], header[8]]);
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

        let settings_ack = [0u8, 0u8, 0u8, 0x4, 0x1, 0, 0, 0, 0];
        tls_stream.write_all(&settings_ack).expect("settings ack");
        let headers_frame = {
            let mut payload = Vec::new();
            payload.push(0x88);
            payload.extend_from_slice(&[
                0x5f, 0x8b, 0x1d, 0x75, 0xd0, 0x62, 0x0d, 0x26, 0x3d, 0x4c, 0x74, 0x41, 0xea,
                0x5c, 0x82, 0x10, 0x7f,
            ]);
            let length = payload.len();
            let mut frame = Vec::with_capacity(length + 9);
            frame.extend_from_slice(&[((length >> 16) & 0xff) as u8, ((length >> 8) & 0xff) as u8, (length & 0xff) as u8]);
            frame.extend_from_slice(&[0x1, 0x4, 0, 0, 0, 1]);
            frame.extend_from_slice(&payload);
            frame
        };
        tls_stream.write_all(&headers_frame).expect("headers frame");
        let data_frame = {
            let length = response_body.len();
            let mut frame = Vec::with_capacity(length + 9);
            frame.extend_from_slice(&[((length >> 16) & 0xff) as u8, ((length >> 8) & 0xff) as u8, (length & 0xff) as u8]);
            frame.extend_from_slice(&[0x0, 0x1, 0, 0, 0, 1]);
            frame.extend_from_slice(response_body);
            frame
        };
        tls_stream.write_all(&data_frame).expect("data frame");
        tls_stream.flush().expect("flush response");
    }
}
