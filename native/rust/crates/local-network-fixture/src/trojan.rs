use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use rcgen::{CertificateParams, DistinguishedName, DnType, IsCa, KeyPair};
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::ServerConfig;
use sha2::{Digest, Sha224};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;
use tokio_rustls::TlsAcceptor;

const SERVER_NAME: &str = "trojan.fixture.test";

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct TrojanLoopbackObserved {
    pub sni: Option<String>,
    pub alpn: Option<String>,
    pub command: Option<u8>,
    pub target_addr: Option<IpAddr>,
    pub target_port: Option<u16>,
    pub initial_payload_len: usize,
}

pub struct TrojanLoopback {
    address: SocketAddr,
    target_address: SocketAddr,
    certificate_pem: String,
    observed: Arc<Mutex<TrojanLoopbackObserved>>,
    shutdown: Option<oneshot::Sender<()>>,
    thread: Option<JoinHandle<()>>,
}

impl TrojanLoopback {
    pub async fn start(password: &str) -> io::Result<Self> {
        let tls = tls_config()?;
        let expected_password_hash = hash_password(password);
        let observed = Arc::new(Mutex::new(TrojanLoopbackObserved::default()));
        let (addr_tx, addr_rx) = std::sync::mpsc::channel();
        let (shutdown_tx, shutdown_rx) = oneshot::channel();
        let observed_for_thread = Arc::clone(&observed);
        let server_config = Arc::clone(&tls.server);

        let thread = thread::spawn(move || {
            let runtime =
                tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("build trojan fixture runtime");
            runtime.block_on(async move {
                let trojan_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind trojan fixture");
                let echo_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind trojan target echo");
                let trojan_address = trojan_listener.local_addr().expect("trojan fixture local addr");
                let target_address = echo_listener.local_addr().expect("trojan echo local addr");
                addr_tx.send((trojan_address, target_address)).ok();
                let echo_task = tokio::spawn(serve_echo(echo_listener));
                tokio::select! {
                    _ = shutdown_rx => {}
                    _ = serve_trojan(trojan_listener, server_config, expected_password_hash, observed_for_thread) => {}
                }
                echo_task.abort();
            });
        });

        let (address, target_address) =
            addr_rx.recv().map_err(|error| io::Error::other(format!("fixture failed to start: {error}")))?;
        Ok(Self {
            address,
            target_address,
            certificate_pem: tls.certificate_pem,
            observed,
            shutdown: Some(shutdown_tx),
            thread: Some(thread),
        })
    }

    pub fn port(&self) -> u16 {
        self.address.port()
    }

    pub fn target_port(&self) -> u16 {
        self.target_address.port()
    }

    pub fn server_name(&self) -> &'static str {
        SERVER_NAME
    }

    pub fn certificate_pem(&self) -> &str {
        &self.certificate_pem
    }

    pub fn observed(&self) -> TrojanLoopbackObserved {
        self.observed.lock().expect("fixture observations").clone()
    }
}

impl Drop for TrojanLoopback {
    fn drop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            shutdown.send(()).ok();
        }
        if let Some(thread) = self.thread.take() {
            thread.join().ok();
        }
    }
}

struct TlsConfig {
    server: Arc<ServerConfig>,
    certificate_pem: String,
}

fn tls_config() -> io::Result<TlsConfig> {
    let key_pair = KeyPair::generate().map_err(to_io)?;
    let mut params = CertificateParams::new(vec![SERVER_NAME.to_owned(), "127.0.0.1".to_owned()]).map_err(to_io)?;
    params.is_ca = IsCa::NoCa;
    let mut dn = DistinguishedName::new();
    dn.push(DnType::CommonName, SERVER_NAME);
    params.distinguished_name = dn;
    let cert = params.self_signed(&key_pair).map_err(to_io)?;
    let certificate_pem = cert.pem();
    let cert_der = cert.der().clone();
    let key_der = key_pair.serialize_der();

    let provider = rustls::crypto::aws_lc_rs::default_provider();
    let mut server = ServerConfig::builder_with_provider(provider.into())
        .with_safe_default_protocol_versions()
        .expect("aws-lc provider supports default TLS versions")
        .with_no_client_auth()
        .with_single_cert(vec![cert_der], PrivateKeyDer::from(PrivatePkcs8KeyDer::from(key_der)))
        .map_err(to_io)?;
    server.alpn_protocols = vec![b"http/1.1".to_vec()];

    Ok(TlsConfig { server: Arc::new(server), certificate_pem })
}

async fn serve_echo(listener: TcpListener) {
    loop {
        let Ok((mut socket, _)) = listener.accept().await else {
            break;
        };
        tokio::spawn(async move {
            let (mut reader, mut writer) = socket.split();
            let _ = tokio::io::copy(&mut reader, &mut writer).await;
        });
    }
}

async fn serve_trojan(
    listener: TcpListener,
    server_config: Arc<ServerConfig>,
    expected_password_hash: String,
    observed: Arc<Mutex<TrojanLoopbackObserved>>,
) {
    let acceptor = TlsAcceptor::from(server_config);
    loop {
        let Ok((socket, _)) = listener.accept().await else {
            break;
        };
        let acceptor = acceptor.clone();
        let expected_password_hash = expected_password_hash.clone();
        let observed = Arc::clone(&observed);
        tokio::spawn(async move {
            let Ok(tls) = acceptor.accept(socket).await else {
                return;
            };
            let _ = handle_connection(tls, &expected_password_hash, observed).await;
        });
    }
}

async fn handle_connection(
    mut tls: tokio_rustls::server::TlsStream<TcpStream>,
    expected_password_hash: &str,
    observed: Arc<Mutex<TrojanLoopbackObserved>>,
) -> io::Result<()> {
    let sni = tls.get_ref().1.server_name().map(ToOwned::to_owned);
    let alpn = tls.get_ref().1.alpn_protocol().map(|bytes| String::from_utf8_lossy(bytes).into_owned());

    let mut password = [0_u8; 56];
    tls.read_exact(&mut password).await?;
    if password != expected_password_hash.as_bytes() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid trojan password hash"));
    }
    read_crlf(&mut tls).await?;
    let command = read_u8(&mut tls).await?;
    let (target_addr, target_port) = read_address(&mut tls).await?;
    read_crlf(&mut tls).await?;

    {
        let mut guard = observed.lock().expect("fixture observations");
        guard.sni = sni;
        guard.alpn = alpn;
        guard.command = Some(command);
        guard.target_addr = Some(target_addr);
        guard.target_port = Some(target_port);
    }

    let mut upstream = TcpStream::connect(SocketAddr::new(target_addr, target_port)).await?;
    let mut first_payload = [0_u8; 4096];
    let first_payload_len = tls.read(&mut first_payload).await?;
    if first_payload_len > 0 {
        upstream.write_all(&first_payload[..first_payload_len]).await?;
    }
    observed.lock().expect("fixture observations").initial_payload_len = first_payload_len;
    let _ = tokio::io::copy_bidirectional(&mut tls, &mut upstream).await;
    Ok(())
}

async fn read_address(tls: &mut tokio_rustls::server::TlsStream<TcpStream>) -> io::Result<(IpAddr, u16)> {
    let atyp = read_u8(tls).await?;
    let addr = match atyp {
        0x01 => {
            let mut octets = [0_u8; 4];
            tls.read_exact(&mut octets).await?;
            IpAddr::V4(Ipv4Addr::from(octets))
        }
        0x03 => {
            let len = usize::from(read_u8(tls).await?);
            let mut bytes = vec![0_u8; len];
            tls.read_exact(&mut bytes).await?;
            let domain = String::from_utf8(bytes)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, format!("invalid domain: {error}")))?;
            let parsed: IpAddr = domain.parse().map_err(|error| {
                io::Error::new(io::ErrorKind::InvalidData, format!("unsupported domain target: {error}"))
            })?;
            parsed
        }
        0x04 => {
            let mut octets = [0_u8; 16];
            tls.read_exact(&mut octets).await?;
            IpAddr::V6(Ipv6Addr::from(octets))
        }
        other => return Err(io::Error::new(io::ErrorKind::InvalidData, format!("invalid address type 0x{other:02x}"))),
    };
    let mut port = [0_u8; 2];
    tls.read_exact(&mut port).await?;
    Ok((addr, u16::from_be_bytes(port)))
}

async fn read_crlf(tls: &mut tokio_rustls::server::TlsStream<TcpStream>) -> io::Result<()> {
    let mut crlf = [0_u8; 2];
    tls.read_exact(&mut crlf).await?;
    if crlf != *b"\r\n" {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid CRLF"));
    }
    Ok(())
}

async fn read_u8(tls: &mut tokio_rustls::server::TlsStream<TcpStream>) -> io::Result<u8> {
    let mut byte = [0_u8; 1];
    tls.read_exact(&mut byte).await?;
    Ok(byte[0])
}

fn hash_password(password: &str) -> String {
    let mut hasher = Sha224::new();
    hasher.update(password.as_bytes());
    hex::encode(hasher.finalize())
}

fn to_io(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}
