use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use rcgen::{CertificateParams, DistinguishedName, DnType, IsCa, KeyPair};
use rustls::ServerConfig;
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;
use tokio_rustls::TlsAcceptor;

const SERVER_NAME: &str = "anytls.fixture.test";

#[derive(Debug, Clone, Default)]
pub struct AnyTlsLoopbackConfig {
    pub reject_next_synack: Option<String>,
    pub server_padding_scheme: Option<String>,
    pub close_after_update: bool,
    pub send_heart_request_after_settings: bool,
    pub send_alert_after_first_syn: Option<String>,
    pub flood_first_stream_frames: usize,
    pub tls_handshake_delay: Duration,
    pub synack_delay: Duration,
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct AnyTlsLoopbackObserved {
    pub tls_session_count: usize,
    pub auth_packet: Vec<u8>,
    pub settings_padding_md5: Vec<String>,
    pub syn_stream_ids: Vec<u32>,
    pub synack_successes: Vec<u32>,
    pub fin_stream_ids: Vec<u32>,
    pub tcp_targets: Vec<String>,
    pub udp_magic_targets: Vec<String>,
    pub udp_requests: Vec<(bool, String)>,
    pub update_padding_scheme_count: usize,
    pub heart_responses: usize,
}

// Drop order: shutdown sends before thread join; fixture tasks need the shutdown signal before their runtime thread is joined.
pub struct AnyTlsLoopback {
    address: SocketAddr,
    target_address: SocketAddr,
    udp_target_address: SocketAddr,
    certificate_pem: String,
    observed: Arc<Mutex<AnyTlsLoopbackObserved>>,
    shutdown: Option<oneshot::Sender<()>>,
    thread: Option<JoinHandle<()>>,
}

impl AnyTlsLoopback {
    pub async fn start(password: &str, config: AnyTlsLoopbackConfig) -> io::Result<Self> {
        let tls = tls_config()?;
        let password_hash = Sha256::digest(password.as_bytes()).to_vec();
        let observed = Arc::new(Mutex::new(AnyTlsLoopbackObserved::default()));
        let (addr_tx, addr_rx) = std::sync::mpsc::channel();
        let (shutdown_tx, shutdown_rx) = oneshot::channel();
        let server = Arc::clone(&tls.server);
        let observed_for_thread = Arc::clone(&observed);

        let thread = thread::spawn(move || {
            let runtime =
                tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("build anytls fixture runtime");
            runtime.block_on(async move {
                let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind anytls fixture");
                let target = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind target placeholder");
                let udp_target = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind udp placeholder");
                let address = listener.local_addr().expect("anytls listener address");
                let target_address = target.local_addr().expect("target address");
                let udp_target_address = udp_target.local_addr().expect("udp target address");
                drop(target);
                drop(udp_target);
                addr_tx.send((address, target_address, udp_target_address)).ok();
                tokio::select! {
                    _ = shutdown_rx => {}
                    _ = serve_anytls(listener, server, password_hash, config, observed_for_thread) => {}
                }
            });
        });

        let (address, target_address, udp_target_address) =
            addr_rx.recv().map_err(|error| io::Error::other(format!("fixture failed to start: {error}")))?;
        Ok(Self {
            address,
            target_address,
            udp_target_address,
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

    pub fn udp_target_port(&self) -> u16 {
        self.udp_target_address.port()
    }

    pub fn server_name(&self) -> &'static str {
        SERVER_NAME
    }

    pub fn certificate_pem(&self) -> &str {
        &self.certificate_pem
    }

    pub fn observed(&self) -> AnyTlsLoopbackObserved {
        self.observed.lock().expect("anytls observed").clone()
    }
}

impl Drop for AnyTlsLoopback {
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
    let provider = rustls::crypto::aws_lc_rs::default_provider();
    let mut server = ServerConfig::builder_with_provider(provider.into())
        .with_safe_default_protocol_versions()
        .expect("aws-lc provider supports default TLS versions")
        .with_no_client_auth()
        .with_single_cert(
            vec![cert.der().clone()],
            PrivateKeyDer::from(PrivatePkcs8KeyDer::from(key_pair.serialize_der())),
        )
        .map_err(to_io)?;
    server.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(TlsConfig { server: Arc::new(server), certificate_pem: cert.pem() })
}

async fn serve_anytls(
    listener: TcpListener,
    server: Arc<ServerConfig>,
    password_hash: Vec<u8>,
    config: AnyTlsLoopbackConfig,
    observed: Arc<Mutex<AnyTlsLoopbackObserved>>,
) {
    let acceptor = TlsAcceptor::from(server);
    loop {
        let Ok((socket, _)) = listener.accept().await else {
            break;
        };
        let acceptor = acceptor.clone();
        let password_hash = password_hash.clone();
        let config = config.clone();
        let observed = Arc::clone(&observed);
        tokio::spawn(async move {
            observed.lock().expect("anytls observed").tls_session_count += 1;
            tokio::time::sleep(config.tls_handshake_delay).await;
            let Ok(tls) = acceptor.accept(socket).await else {
                return;
            };
            let _ = handle_connection(tls, &password_hash, config, observed).await;
        });
    }
}

async fn handle_connection(
    mut tls: tokio_rustls::server::TlsStream<TcpStream>,
    password_hash: &[u8],
    config: AnyTlsLoopbackConfig,
    observed: Arc<Mutex<AnyTlsLoopbackObserved>>,
) -> io::Result<()> {
    let mut auth_prefix = [0_u8; 34];
    tls.read_exact(&mut auth_prefix).await?;
    let padding_len = usize::from(u16::from_be_bytes([auth_prefix[32], auth_prefix[33]]));
    let mut padding = vec![0_u8; padding_len];
    tls.read_exact(&mut padding).await?;
    let mut auth_packet = auth_prefix.to_vec();
    auth_packet.extend_from_slice(&padding);
    observed.lock().expect("anytls observed").auth_packet = auth_packet;
    if &auth_prefix[..32] != password_hash {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "invalid anytls auth"));
    }

    let mut streams = HashMap::<u32, StreamState>::new();
    loop {
        let frame = read_frame(&mut tls).await?;
        match frame.command {
            CMD_WASTE => {}
            CMD_SETTINGS => {
                let padding_md5 = parse_padding_md5(&frame.data).unwrap_or_default();
                let mut should_update = false;
                if let Some(scheme) = &config.server_padding_scheme {
                    let expected = format!("{:x}", md5::compute(scheme.as_bytes()));
                    should_update = expected != padding_md5;
                }
                observed.lock().expect("anytls observed").settings_padding_md5.push(padding_md5);
                write_frame(&mut tls, CMD_SERVER_SETTINGS, 0, b"v=2").await?;
                if config.send_heart_request_after_settings {
                    write_frame(&mut tls, CMD_HEART_REQUEST, 0, &[]).await?;
                    observe_heart_response_before_close(&mut tls, &observed).await;
                }
                if should_update && let Some(scheme) = &config.server_padding_scheme {
                    observed.lock().expect("anytls observed").update_padding_scheme_count += 1;
                    write_frame(&mut tls, CMD_UPDATE_PADDING_SCHEME, 0, scheme.as_bytes()).await?;
                    if config.close_after_update {
                        tls.shutdown().await?;
                        return Ok(());
                    }
                }
            }
            CMD_HEART_RESPONSE => {
                observed.lock().expect("anytls observed").heart_responses += 1;
            }
            CMD_SYN => {
                observed.lock().expect("anytls observed").syn_stream_ids.push(frame.stream_id);
                streams.insert(frame.stream_id, StreamState::default());
            }
            CMD_PSH => {
                let state = streams.entry(frame.stream_id).or_default();
                if !state.target_received {
                    state.target_received = true;
                    let mut cursor = 0;
                    let target = decode_target(&frame.data, &mut cursor)?;
                    if target == "sp.v2.udp-over-tcp.arpa:0" {
                        let is_connect = read_u8(&frame.data, &mut cursor)?;
                        if is_connect != 0 {
                            return Err(io::Error::new(
                                io::ErrorKind::InvalidData,
                                "fixture requires UoT datagram mode",
                            ));
                        }
                        let destination = decode_target(&frame.data, &mut cursor)?;
                        observed.lock().expect("anytls observed").udp_requests.push((false, destination));
                        state.udp = true;
                        observed.lock().expect("anytls observed").udp_magic_targets.push(target);
                    } else {
                        observed.lock().expect("anytls observed").tcp_targets.push(target);
                    }
                    if let Some(message) = &config.send_alert_after_first_syn {
                        write_frame(&mut tls, CMD_ALERT, 0, message.as_bytes()).await?;
                        observe_heart_response_before_close(&mut tls, &observed).await;
                        return Ok(());
                    }
                    if let Some(message) = &config.reject_next_synack {
                        write_frame(&mut tls, CMD_SYNACK, frame.stream_id, message.as_bytes()).await?;
                    } else {
                        tokio::time::sleep(config.synack_delay).await;
                        observed.lock().expect("anytls observed").synack_successes.push(frame.stream_id);
                        write_frame(&mut tls, CMD_SYNACK, frame.stream_id, &[]).await?;
                        if frame.stream_id == 1 {
                            for _ in 0..config.flood_first_stream_frames {
                                write_frame(&mut tls, CMD_PSH, frame.stream_id, b"stalled-stream").await?;
                            }
                        }
                    }
                } else if state.udp {
                    state.udp_buffer.extend_from_slice(&frame.data);
                    while let Some(length) = complete_uot_packet(&state.udp_buffer)? {
                        let packet: Vec<u8> = state.udp_buffer.drain(..length).collect();
                        for chunk in packet.chunks(usize::from(u16::MAX)) {
                            write_frame(&mut tls, CMD_PSH, frame.stream_id, chunk).await?;
                        }
                    }
                } else {
                    write_frame(&mut tls, CMD_PSH, frame.stream_id, &frame.data).await?;
                }
            }
            CMD_FIN => {
                streams.remove(&frame.stream_id);
                observed.lock().expect("anytls observed").fin_stream_ids.push(frame.stream_id);
            }
            _ => {}
        }
    }
}

#[derive(Default)]
struct StreamState {
    target_received: bool,
    udp: bool,
    udp_buffer: Vec<u8>,
}

struct FixtureFrame {
    command: u8,
    stream_id: u32,
    data: Vec<u8>,
}

const CMD_WASTE: u8 = 0;
const CMD_SYN: u8 = 1;
const CMD_PSH: u8 = 2;
const CMD_FIN: u8 = 3;
const CMD_SETTINGS: u8 = 4;
const CMD_ALERT: u8 = 5;
const CMD_UPDATE_PADDING_SCHEME: u8 = 6;
const CMD_SYNACK: u8 = 7;
const CMD_HEART_REQUEST: u8 = 8;
const CMD_HEART_RESPONSE: u8 = 9;
const CMD_SERVER_SETTINGS: u8 = 10;

async fn read_frame(stream: &mut tokio_rustls::server::TlsStream<TcpStream>) -> io::Result<FixtureFrame> {
    let mut header = [0_u8; 7];
    stream.read_exact(&mut header).await?;
    let stream_id = u32::from_be_bytes([header[1], header[2], header[3], header[4]]);
    let data_len = usize::from(u16::from_be_bytes([header[5], header[6]]));
    let mut data = vec![0_u8; data_len];
    stream.read_exact(&mut data).await?;
    Ok(FixtureFrame { command: header[0], stream_id, data })
}

async fn write_frame(
    stream: &mut tokio_rustls::server::TlsStream<TcpStream>,
    command: u8,
    stream_id: u32,
    data: &[u8],
) -> io::Result<()> {
    let data_len =
        u16::try_from(data.len()).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "frame data too long"))?;
    let mut frame = Vec::with_capacity(7 + data.len());
    frame.push(command);
    frame.extend_from_slice(&stream_id.to_be_bytes());
    frame.extend_from_slice(&data_len.to_be_bytes());
    frame.extend_from_slice(data);
    stream.write_all(&frame).await
}

async fn observe_heart_response_before_close(
    stream: &mut tokio_rustls::server::TlsStream<TcpStream>,
    observed: &Arc<Mutex<AnyTlsLoopbackObserved>>,
) {
    if let Ok(Ok(frame)) = tokio::time::timeout(std::time::Duration::from_millis(250), read_frame(stream)).await
        && frame.command == CMD_HEART_RESPONSE
    {
        observed.lock().expect("anytls observed").heart_responses += 1;
    }
}

fn parse_padding_md5(settings: &[u8]) -> Option<String> {
    let settings = std::str::from_utf8(settings).ok()?;
    settings.lines().find_map(|line| line.strip_prefix("padding-md5=").map(ToOwned::to_owned))
}

fn complete_uot_packet(data: &[u8]) -> io::Result<Option<usize>> {
    let Some(family) = data.first() else { return Ok(None) };
    let address_length = match family {
        0 => 5,
        1 => 17,
        2 => {
            let Some(length) = data.get(1) else { return Ok(None) };
            2 + usize::from(*length)
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid UoT address family")),
    };
    let Some(length) = data.get(address_length + 2..address_length + 4) else { return Ok(None) };
    let total = address_length + 4 + usize::from(u16::from_be_bytes([length[0], length[1]]));
    Ok((data.len() >= total).then_some(total))
}

fn decode_target(data: &[u8], cursor: &mut usize) -> io::Result<String> {
    let atyp = read_u8(data, cursor)?;
    let host = match atyp {
        0x01 => {
            let octets = read_exact(data, cursor, 4)?;
            IpAddr::V4(Ipv4Addr::new(octets[0], octets[1], octets[2], octets[3])).to_string()
        }
        0x03 => {
            let len = usize::from(read_u8(data, cursor)?);
            let bytes = read_exact(data, cursor, len)?;
            String::from_utf8_lossy(bytes).into_owned()
        }
        0x04 => {
            let octets = read_exact(data, cursor, 16)?;
            let mut array = [0_u8; 16];
            array.copy_from_slice(octets);
            IpAddr::V6(Ipv6Addr::from(array)).to_string()
        }
        _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid target atyp")),
    };
    let port = read_u16(data, cursor)?;
    Ok(format!("{host}:{port}"))
}

fn read_u8(data: &[u8], cursor: &mut usize) -> io::Result<u8> {
    Ok(read_exact(data, cursor, 1)?[0])
}

fn read_u16(data: &[u8], cursor: &mut usize) -> io::Result<u16> {
    let bytes = read_exact(data, cursor, 2)?;
    Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn read_exact<'a>(data: &'a [u8], cursor: &mut usize, len: usize) -> io::Result<&'a [u8]> {
    let end = cursor.checked_add(len).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "target overflow"))?;
    let bytes =
        data.get(*cursor..end).ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "target truncated"))?;
    *cursor = end;
    Ok(bytes)
}

fn to_io(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}
