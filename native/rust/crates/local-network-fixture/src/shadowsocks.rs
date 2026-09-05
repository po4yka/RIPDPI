use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::thread::{self, JoinHandle};
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_shadowsocks::{
    Aead2022UdpPacketType, Aead2022UdpSession, Cipher, PresharedKey, SecretString, TcpStream as ShadowsocksTcpCodec,
    UdpPacket,
};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::oneshot;

// Drop order: shutdown sends before thread join; fixture tasks need the shutdown signal before their runtime thread is joined.
pub struct ShadowsocksLoopback {
    address: SocketAddr,
    target_address: SocketAddr,
    udp_target_address: SocketAddr,
    shutdown: Option<oneshot::Sender<()>>,
    thread: Option<JoinHandle<()>>,
}

impl ShadowsocksLoopback {
    /// # Cancel safety:
    /// This future has no await points. Startup returns the owner of all fixture resources.
    // cancel-safe: cancellation before polling allocates no resources.
    pub async fn start(method: &str, password: &str) -> io::Result<Self> {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0))?;
        let (listener, udp) = bind_udp_pair(listener)?;
        listener.set_nonblocking(true)?;
        udp.set_nonblocking(true)?;
        let address = listener.local_addr()?;
        let method = method.to_string();
        let password = password.to_string();
        let (addr_tx, addr_rx) = std::sync::mpsc::channel();
        let (shutdown_tx, shutdown_rx) = oneshot::channel();

        let thread = thread::spawn(move || {
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
                .expect("build shadowsocks fixture runtime");
            runtime.block_on(async move {
                let sockets = TcpListener::from_std(listener)
                    .and_then(|listener| UdpSocket::from_std(udp).map(|udp| (listener, udp)));
                let (listener, udp) = match sockets {
                    Ok(sockets) => sockets,
                    Err(error) => {
                        let _ = addr_tx.send(Err(error));
                        return;
                    }
                };
                let echo_listener =
                    TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind shadowsocks target echo");
                let udp_echo =
                    UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind shadowsocks UDP target echo");
                let target_address = echo_listener.local_addr().expect("shadowsocks echo local addr");
                let udp_target_address = udp_echo.local_addr().expect("shadowsocks UDP echo local addr");
                addr_tx.send(Ok((address, target_address, udp_target_address))).ok();
                let echo_task = tokio::spawn(serve_echo(echo_listener));
                let udp_echo_task = tokio::spawn(serve_udp_echo(udp_echo));
                tokio::select! {
                    _ = shutdown_rx => {}
                    _ = serve_shadowsocks(listener, udp, method, password) => {}
                }
                echo_task.abort();
                udp_echo_task.abort();
            });
        });

        let (address, target_address, udp_target_address) =
            addr_rx.recv().map_err(|error| io::Error::other(format!("fixture failed to start: {error}")))??;
        Ok(Self { address, target_address, udp_target_address, shutdown: Some(shutdown_tx), thread: Some(thread) })
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
}

// A TCP ephemeral port can already be occupied by UDP. Keep each TCP
// candidate reserved until the next candidate is bound; return both sockets.
fn bind_udp_pair(mut listener: std::net::TcpListener) -> io::Result<(std::net::TcpListener, std::net::UdpSocket)> {
    let mut retries_left = 31;
    loop {
        match std::net::UdpSocket::bind(listener.local_addr()?) {
            Ok(udp) => return Ok((listener, udp)),
            Err(error) if error.kind() == io::ErrorKind::AddrInUse && retries_left > 0 => {
                retries_left -= 1;
                listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0))?;
            }
            Err(error) => return Err(error),
        }
    }
}

impl Drop for ShadowsocksLoopback {
    fn drop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            shutdown.send(()).ok();
        }
        if let Some(thread) = self.thread.take() {
            thread.join().ok();
        }
    }
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

async fn serve_udp_echo(socket: UdpSocket) {
    let mut buffer = [0_u8; 4096];
    loop {
        let Ok((read, peer)) = socket.recv_from(&mut buffer).await else {
            break;
        };
        if socket.send_to(&buffer[..read], peer).await.is_err() {
            break;
        }
    }
}

async fn serve_shadowsocks(listener: TcpListener, udp: UdpSocket, method: String, password: String) {
    let Ok(cipher) = Cipher::from_name(&method) else {
        return;
    };
    let tcp_password = password.clone();
    let tcp_task = tokio::spawn(async move {
        loop {
            let Ok((socket, _)) = listener.accept().await else {
                break;
            };
            let password = tcp_password.clone();
            tokio::spawn(async move {
                let _ = handle_tcp_connection(socket, cipher, password).await;
            });
        }
    });
    let udp_task = tokio::spawn(async move {
        let _ = handle_udp_packets(udp, cipher, password).await;
    });
    let _ = tokio::join!(tcp_task, udp_task);
}

/// # Cancel safety:
/// Not cancel-safe after pumps start: cancellation leaves those tasks running.
/// The caller must stop the fixture runtime when cancelling this handler.
// NOT cancel-safe: spawned fixture pumps can outlive the handler future.
async fn handle_tcp_connection(mut socket: TcpStream, cipher: Cipher, password: String) -> io::Result<()> {
    let secret = SecretString::new(password.clone());
    let mut request_salt = vec![0_u8; cipher.salt_len()];
    let mut encrypted = Vec::new();
    if cipher.is_aead_2022() {
        let mut first = vec![0; cipher.salt_len() + 27];
        if socket.read(&mut first).await? != first.len() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "short SIP022 initial request"));
        }
        request_salt.copy_from_slice(&first[..cipher.salt_len()]);
        encrypted.extend_from_slice(&first[cipher.salt_len()..]);
    } else {
        socket.read_exact(&mut request_salt).await?;
    }
    let mut request_decoder =
        ShadowsocksTcpCodec::new_decrypt(cipher, &secret, &request_salt, cipher.is_aead_2022()).map_err(to_io)?;
    let first = if cipher.is_aead_2022() {
        loop {
            if let Some((plain, consumed)) =
                request_decoder.decrypt_request_header(&encrypted, unix_timestamp()).map_err(to_io)?
            {
                encrypted.drain(..consumed);
                break Some(plain);
            }
            let mut buffer = [0; 4096];
            let read = socket.read(&mut buffer).await?;
            if read == 0 {
                break None;
            }
            encrypted.extend_from_slice(&buffer[..read]);
        }
    } else {
        read_chunk(&mut socket, &mut request_decoder, &mut encrypted).await?
    };
    let Some(first) = first else {
        return Ok(());
    };
    let (target, initial_payload) = decode_address(&first)?;
    let mut upstream = TcpStream::connect(target).await?;
    if !initial_payload.is_empty() {
        upstream.write_all(initial_payload).await?;
    }

    let (mut response_encoder, response_salt) =
        ShadowsocksTcpCodec::new_encrypt(cipher, &secret, cipher.is_aead_2022()).map_err(to_io)?;
    let (mut socket_reader, mut socket_writer) = socket.into_split();
    let (mut upstream_reader, mut upstream_writer) = upstream.into_split();

    let client_to_target = tokio::spawn(async move {
        loop {
            match read_chunk(&mut socket_reader, &mut request_decoder, &mut encrypted).await {
                Ok(Some(plain)) if upstream_writer.write_all(&plain).await.is_ok() => {}
                _ => return,
            }
        }
    });
    let target_to_client = tokio::spawn(async move {
        let mut first_salt = Some(response_salt);
        let mut buffer = [0_u8; 4096];
        loop {
            let Ok(read) = upstream_reader.read(&mut buffer).await else {
                return;
            };
            if read == 0 {
                return;
            }
            let encrypted = if cipher.is_aead_2022() && first_salt.is_some() {
                response_encoder.encrypt_response_header(unix_timestamp(), &request_salt, &buffer[..read])
            } else {
                response_encoder.encrypt_payload(&buffer[..read])
            };
            let Ok(mut encrypted) = encrypted else {
                return;
            };
            if let Some(mut salt) = first_salt.take() {
                salt.extend(encrypted);
                encrypted = salt;
            }
            if socket_writer.write_all(&encrypted).await.is_err() {
                return;
            }
        }
    });
    let _ = tokio::join!(client_to_target, target_to_client);
    Ok(())
}

async fn read_chunk<R>(
    reader: &mut R,
    decoder: &mut ShadowsocksTcpCodec,
    encrypted: &mut Vec<u8>,
) -> io::Result<Option<Vec<u8>>>
where
    R: AsyncRead + Unpin,
{
    let mut buffer = [0_u8; 4096];
    loop {
        if let Some((plain, consumed)) = decoder.decrypt_chunk(encrypted, 0).map_err(to_io)? {
            encrypted.drain(..consumed);
            return Ok(Some(plain));
        }
        let read = reader.read(&mut buffer).await?;
        if read == 0 {
            return Ok(None);
        }
        encrypted.extend_from_slice(&buffer[..read]);
    }
}

async fn handle_udp_packets(socket: UdpSocket, cipher: Cipher, password: String) -> io::Result<()> {
    let secret = SecretString::new(password.clone());
    let mut aead2022 = if cipher.is_aead_2022() {
        let psk = PresharedKey::from_base64(cipher, &password).map_err(to_io)?;
        Some(Aead2022UdpSession::new(cipher, psk, *b"fixture1").map_err(to_io)?)
    } else {
        None
    };
    let legacy = UdpPacket::new(cipher, false);
    let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let mut buffer = vec![0_u8; 65_535];
    loop {
        let (read, peer) = socket.recv_from(&mut buffer).await?;
        let (plain, client_session_id) = match &mut aead2022 {
            Some(codec) => {
                let packet =
                    codec.decrypt(&buffer[..read], Aead2022UdpPacketType::Client, unix_timestamp()).map_err(to_io)?;
                (packet.payload, Some(packet.session_id))
            }
            None => (legacy.decrypt(&secret, &buffer[..read]).map_err(to_io)?, None),
        };
        let (target, payload) = decode_address(&plain)?;
        relay.send_to(payload, target).await?;
        let mut response = vec![0_u8; 65_535];
        let (response_len, source) = relay.recv_from(&mut response).await?;
        let response_plain = encode_address(source, &response[..response_len]);
        let packet = match &mut aead2022 {
            Some(codec) => codec
                .encrypt_server(
                    unix_timestamp(),
                    client_session_id.expect("SIP022 packet carries a client session ID"),
                    &response_plain,
                )
                .map_err(to_io)?,
            None => legacy.encrypt(&secret, &response_plain).map_err(to_io)?,
        };
        socket.send_to(&packet, peer).await?;
    }
}

fn encode_address(target: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload.len() + 32);
    match target {
        SocketAddr::V4(addr) => {
            out.push(0x01);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
        SocketAddr::V6(addr) => {
            out.push(0x04);
            out.extend_from_slice(&addr.ip().octets());
            out.extend_from_slice(&addr.port().to_be_bytes());
        }
    }
    out.extend_from_slice(payload);
    out
}

fn decode_address(data: &[u8]) -> io::Result<(SocketAddr, &[u8])> {
    if data.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "missing Shadowsocks address type"));
    }
    match data[0] {
        0x01 => {
            if data.len() < 7 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated Shadowsocks IPv4 address"));
            }
            let ip = IpAddr::V4(Ipv4Addr::new(data[1], data[2], data[3], data[4]));
            let port = u16::from_be_bytes([data[5], data[6]]);
            Ok((SocketAddr::new(ip, port), &data[7..]))
        }
        0x03 => {
            if data.len() < 2 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated Shadowsocks domain address"));
            }
            let len = usize::from(data[1]);
            if data.len() < 2 + len + 2 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated Shadowsocks domain payload"));
            }
            let host = std::str::from_utf8(&data[2..2 + len])
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid Shadowsocks domain"))?;
            let ip = host
                .parse::<IpAddr>()
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "fixture only supports IP domain targets"))?;
            let port = u16::from_be_bytes([data[2 + len], data[2 + len + 1]]);
            Ok((SocketAddr::new(ip, port), &data[2 + len + 2..]))
        }
        0x04 => {
            if data.len() < 19 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "truncated Shadowsocks IPv6 address"));
            }
            let mut octets = [0_u8; 16];
            octets.copy_from_slice(&data[1..17]);
            let port = u16::from_be_bytes([data[17], data[18]]);
            Ok((SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)), port), &data[19..]))
        }
        atyp => {
            Err(io::Error::new(io::ErrorKind::InvalidInput, format!("unsupported Shadowsocks address type {atyp:#x}")))
        }
    }
}

fn unix_timestamp() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |duration| duration.as_secs())
}

fn to_io(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}

#[cfg(test)]
mod allocation_tests {
    use super::*;

    #[test]
    fn paired_listener_retries_an_occupied_udp_candidate() {
        let first = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("first TCP candidate");
        let (first, occupied_udp) = bind_udp_pair(first).expect("reserve occupied UDP port");
        let occupied_address = occupied_udp.local_addr().expect("occupied address");

        let (listener, udp) = bind_udp_pair(first).expect("retry UDP collision");
        let address = listener.local_addr().expect("new TCP address");
        assert_ne!(address, occupied_address);
        assert_eq!(address, udp.local_addr().expect("paired UDP address"));
        assert_eq!(occupied_udp.local_addr().expect("occupied socket retained"), occupied_address);
    }
}
