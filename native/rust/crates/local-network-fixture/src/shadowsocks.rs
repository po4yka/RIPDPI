use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::thread::{self, JoinHandle};

use shadowsocks::config::{ReplayAttackPolicy, ServerConfig, ServerType, ServerUser, ServerUserManager};
use shadowsocks::context::{Context, SharedContext};
use shadowsocks::crypto::CipherKind;
use shadowsocks::relay::socks5::Address;
use shadowsocks::relay::tcprelay::ProxyServerStream;
use shadowsocks::relay::udprelay::ProxySocket;
use shadowsocks::relay::udprelay::proxy_socket::{ProxySocketError, UdpSocketType};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::oneshot;
use tokio::task::JoinSet;

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
    /// cancel-safe: this future has no await points. Startup returns the owner of all fixture resources.
    pub async fn start(method: &str, password: &str) -> io::Result<Self> {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0))?;
        let (listener, udp) = bind_udp_pair(listener)?;
        listener.set_nonblocking(true)?;
        udp.set_nonblocking(true)?;
        let address = listener.local_addr()?;
        let config = Arc::new(server_config(address, method, password)?);
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
                    _ = serve_shadowsocks(listener, udp, config) => {}
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

/// # Cancel safety:
/// cancel-safe: dropping the service drops its JoinSet and aborts all connections.
async fn serve_echo(listener: TcpListener) {
    let mut connections = JoinSet::new();
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let Ok((mut socket, _)) = accepted else { break };
                connections.spawn(async move {
                    let (mut reader, mut writer) = socket.split();
                    let _ = tokio::io::copy(&mut reader, &mut writer).await;
                });
            }
            _ = connections.join_next(), if !connections.is_empty() => {}
        }
    }
}

/// # Cancel safety:
/// cancel-safe: dropping the service closes the owned socket.
async fn serve_udp_echo(socket: UdpSocket) {
    let mut buffer = [0_u8; 4096];
    loop {
        let Ok((read, peer)) = socket.recv_from(&mut buffer).await else { break };
        if socket.send_to(&buffer[..read], peer).await.is_err() {
            break;
        }
    }
}

fn server_config(address: SocketAddr, method: &str, password: &str) -> io::Result<ServerConfig> {
    let cipher: CipherKind = method.parse().map_err(to_io)?;
    let client = ServerConfig::new(address, password, cipher).map_err(to_io)?;
    match client.identity_keys().len() {
        0 => Ok(client),
        1 => {
            let identity = password.split(':').next().ok_or_else(|| io::Error::other("missing identity key"))?;
            let mut server = ServerConfig::new(address, identity, cipher).map_err(to_io)?;
            let mut users = ServerUserManager::new();
            users.add_user(ServerUser::new("fixture", client.key().to_vec()));
            server.set_user_manager(users);
            Ok(server)
        }
        _ => Err(io::Error::new(io::ErrorKind::InvalidInput, "fixture supports one identity server")),
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops both services and their owned connections.
async fn serve_shadowsocks(listener: TcpListener, udp: UdpSocket, config: Arc<ServerConfig>) {
    let mut context = Context::new(ServerType::Server);
    context.set_replay_attack_policy(ReplayAttackPolicy::Reject);
    let context = Arc::new(context);
    let _ = tokio::join!(
        serve_tcp(listener, Arc::clone(&config), Arc::clone(&context)),
        handle_udp_packets(udp, config, context),
    );
}

/// # Cancel safety:
/// cancel-safe: dropping the service aborts all owned connection tasks.
async fn serve_tcp(listener: TcpListener, config: Arc<ServerConfig>, context: SharedContext) {
    let mut connections = JoinSet::new();
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let Ok((socket, _)) = accepted else { break };
                let config = Arc::clone(&config);
                let context = Arc::clone(&context);
                connections.spawn(async move {
                    let _ = handle_tcp_connection(socket, &config, context).await;
                });
            }
            _ = connections.join_next(), if !connections.is_empty() => {}
        }
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops both owned stream directions and peer sockets.
async fn handle_tcp_connection(socket: TcpStream, config: &ServerConfig, context: SharedContext) -> io::Result<()> {
    let mut client = ProxyServerStream::from_stream_with_user_manager(
        context,
        socket,
        config.method(),
        config.key(),
        config.clone_user_manager(),
    );
    let target = client.handshake().await?;
    let mut upstream = TcpStream::connect(socket_address(&target)?).await?;
    tokio::io::copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}

/// # Cancel safety:
/// cancel-safe: cancellation drops both owned UDP sockets and the packet counter.
async fn handle_udp_packets(socket: UdpSocket, config: Arc<ServerConfig>, context: SharedContext) -> io::Result<()> {
    if !config.method().is_aead_2022() {
        return Err(io::Error::new(io::ErrorKind::Unsupported, "Shadowsocks UDP requires SS2022"));
    }
    let mut session_id = [0_u8; 8];
    context.generate_nonce(config.method(), &mut session_id, false);
    let server_session_id = u64::from_be_bytes(session_id);
    let socket =
        ProxySocket::from_socket(UdpSocketType::Server, context, &config, shadowsocks::net::UdpSocket::from(socket));
    let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let mut buffer = vec![0_u8; 65_536];
    let mut response = vec![0_u8; 65_536];
    let mut packet_id = 0_u64;
    loop {
        let (read, peer, target, _, control) = match socket.recv_from_with_ctrl(&mut buffer).await {
            Ok(packet) => packet,
            Err(ProxySocketError::ProtocolError(_) | ProxySocketError::ProtocolErrorWithPeer(_, _)) => continue,
            Err(error) => return Err(error.into()),
        };
        let Some(mut control) = control else { continue };
        relay.send_to(&buffer[..read], socket_address(&target)?).await?;
        let (response_len, source) = relay.recv_from(&mut response).await?;
        control.server_session_id = server_session_id;
        control.packet_id = packet_id;
        packet_id = packet_id.checked_add(1).ok_or_else(|| io::Error::other("UDP packet ID exhausted"))?;
        socket.send_to_with_ctrl(peer, &Address::SocketAddress(source), &control, &response[..response_len]).await?;
    }
}

fn socket_address(address: &Address) -> io::Result<SocketAddr> {
    match address {
        Address::SocketAddress(address) => Ok(*address),
        Address::DomainNameAddress(host, port) => host
            .parse::<IpAddr>()
            .map(|ip| SocketAddr::new(ip, *port))
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "fixture only supports IP domain targets")),
    }
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
