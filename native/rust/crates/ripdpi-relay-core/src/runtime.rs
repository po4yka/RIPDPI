use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use ripdpi_tls_profiles::profile_catalog_version;
use tokio::io::{copy_bidirectional, AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::time::timeout;

use crate::backend::{build_backend, RelayBackend};
use crate::config::ResolvedRelayRuntimeConfig;
use crate::runtime_validation::{
    describe_runtime_health, describe_upstream, planned_backend_capabilities, planned_backend_fallback_mode,
    validate_runtime_config,
};
use crate::socks::{decode_udp_frame, encode_udp_frame, read_target, write_reply, RelayTargetAddr};
use crate::telemetry::{now_ms, RelayTelemetry};

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);
const UDP_BUFFER_SIZE: usize = 65_536;

fn emit_runtime_ready(bind_addr: &str) {
    tracing::info!(
        ring = "relay",
        subsystem = "relay",
        source = "relay",
        kind = "runtime_ready",
        "listener started addr={bind_addr}"
    );
}

fn emit_runtime_stopped() {
    tracing::info!(ring = "relay", subsystem = "relay", source = "relay", kind = "runtime_stopped", "listener stopped");
}

pub struct RelayRuntime {
    config: ResolvedRelayRuntimeConfig,
    stop_requested: AtomicBool,
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    backend: Mutex<Option<Arc<RelayBackend>>>,
    listener_address: Mutex<Option<String>>,
    last_target: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
    last_handshake_error: Mutex<Option<String>>,
}

impl RelayRuntime {
    pub fn new(config: ResolvedRelayRuntimeConfig) -> Arc<Self> {
        Arc::new(Self {
            config,
            stop_requested: AtomicBool::new(false),
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            backend: Mutex::new(None),
            listener_address: Mutex::new(None),
            last_target: Mutex::new(None),
            last_error: Mutex::new(None),
            last_handshake_error: Mutex::new(None),
        })
    }

    pub fn stop(&self) {
        self.stop_requested.store(true, Ordering::SeqCst);
    }

    pub fn telemetry(&self) -> RelayTelemetry {
        let backend = self.backend.lock().expect("relay backend").clone();
        let capabilities =
            backend.as_deref().map_or_else(|| planned_backend_capabilities(&self.config), RelayBackend::capabilities);
        let (quic_migration_status, quic_migration_reason) =
            backend.as_deref().map_or((None, None), RelayBackend::quic_migration_snapshot);
        let is_running = self.running.load(Ordering::SeqCst);
        let state = if is_running { "running" } else { "idle" };

        RelayTelemetry {
            source: "relay",
            state: state.to_string(),
            health: describe_runtime_health(state, backend.as_deref()),
            active_sessions: self.active_sessions.load(Ordering::SeqCst),
            total_sessions: self.total_sessions.load(Ordering::SeqCst),
            listener_address: self.listener_address.lock().expect("listener address").clone(),
            upstream_address: Some(describe_upstream(&self.config)),
            last_target: self.last_target.lock().expect("last target").clone(),
            last_error: self.last_error.lock().expect("last error").clone(),
            profile_id: Some(self.config.profile_id.clone()),
            protocol_kind: Some(self.config.kind.clone()),
            tcp_capable: Some(capabilities.tcp),
            udp_capable: Some(capabilities.udp),
            fallback_mode: planned_backend_fallback_mode(&self.config),
            last_handshake_error: self.last_handshake_error.lock().expect("handshake error").clone(),
            chain_entry_state: if self.config.kind == "chain_relay" {
                Some(if is_running { "connected" } else { "idle" }.to_string())
            } else {
                None
            },
            chain_exit_state: if self.config.kind == "chain_relay" {
                Some(if is_running { "connected" } else { "idle" }.to_string())
            } else {
                None
            },
            strategy_pack_id: None,
            strategy_pack_version: None,
            tls_profile_id: Some(self.config.tls_fingerprint_profile.clone()),
            tls_profile_catalog_version: Some(profile_catalog_version().to_string()),
            morph_policy_id: None,
            quic_migration_status,
            quic_migration_reason,
            pt_runtime_kind: None,
            pt_runtime_state: None,
            captured_at: now_ms(),
        }
    }

    pub async fn run(self: Arc<Self>) -> io::Result<()> {
        let backend = Arc::new(build_backend(&self.config).await?);
        validate_runtime_config(&self.config, &backend)?;
        *self.backend.lock().expect("relay backend") = Some(Arc::clone(&backend));

        let bind_addr = format!("{}:{}", self.config.local_socks_host, self.config.local_socks_port);
        let listener = TcpListener::bind(&bind_addr).await?;
        *self.listener_address.lock().expect("listener address") = Some(bind_addr);
        self.running.store(true, Ordering::SeqCst);
        emit_runtime_ready(self.listener_address.lock().expect("listener address").as_deref().unwrap_or_default());

        while !self.stop_requested.load(Ordering::SeqCst) {
            match timeout(ACCEPT_POLL_INTERVAL, listener.accept()).await {
                Ok(Ok((stream, _))) => {
                    let runtime = Arc::clone(&self);
                    let backend = Arc::clone(&backend);
                    tokio::spawn(async move {
                        runtime.active_sessions.fetch_add(1, Ordering::SeqCst);
                        runtime.total_sessions.fetch_add(1, Ordering::SeqCst);
                        if let Err(error) = runtime.handle_client(stream, backend).await {
                            *runtime.last_error.lock().expect("last error") = Some(error.to_string());
                        }
                        runtime.active_sessions.fetch_sub(1, Ordering::SeqCst);
                    });
                }
                Ok(Err(error)) => {
                    *self.last_error.lock().expect("last error") = Some(error.to_string());
                }
                Err(_) => {}
            }
        }

        self.running.store(false, Ordering::SeqCst);
        emit_runtime_stopped();
        *self.backend.lock().expect("relay backend") = None;
        Ok(())
    }

    async fn handle_client(&self, mut client: TcpStream, backend: Arc<RelayBackend>) -> io::Result<()> {
        let mut greeting = [0u8; 2];
        client.read_exact(&mut greeting).await?;
        if greeting[0] != 0x05 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 version"));
        }

        let method_count = usize::from(greeting[1]);
        let mut methods = vec![0u8; method_count];
        client.read_exact(&mut methods).await?;
        client.write_all(&[0x05, 0x00]).await?;

        let mut request_header = [0u8; 4];
        client.read_exact(&mut request_header).await?;
        if request_header[0] != 0x05 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 request"));
        }

        let command = request_header[1];
        let target = read_target(&mut client, request_header[3]).await?;
        *self.last_target.lock().expect("last target") = Some(target.to_string());

        match command {
            0x01 => self.handle_connect(client, backend, target).await,
            0x03 => self.handle_udp_associate(client, backend).await,
            _ => {
                write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                Err(io::Error::new(io::ErrorKind::Unsupported, format!("SOCKS5 command {command:#x} is not supported")))
            }
        }
    }

    async fn handle_connect(
        &self,
        mut client: TcpStream,
        backend: Arc<RelayBackend>,
        target: RelayTargetAddr,
    ) -> io::Result<()> {
        let mut upstream = match backend.connect_tcp(&target).await {
            Ok(stream) => stream,
            Err(error) => {
                *self.last_handshake_error.lock().expect("handshake error") = Some(error.to_string());
                write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                return Err(error);
            }
        };

        write_reply(&mut client, 0x00, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
        let _ = copy_bidirectional(&mut client, &mut upstream).await?;
        Ok(())
    }

    async fn handle_udp_associate(&self, mut client: TcpStream, backend: Arc<RelayBackend>) -> io::Result<()> {
        if !backend.udp_capable() {
            write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                format!("relay backend {} does not support UDP ASSOCIATE", self.config.kind),
            ));
        }

        let mut udp_session = match backend.open_udp_session().await {
            Ok(session) => session,
            Err(error) => {
                *self.last_handshake_error.lock().expect("handshake error") = Some(error.to_string());
                write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
                return Err(error);
            }
        };

        let udp_socket = UdpSocket::bind(format!("{}:0", self.config.local_socks_host)).await?;
        let bound = udp_socket.local_addr()?;
        write_reply(&mut client, 0x00, bound).await?;

        let control_ip = client.peer_addr()?.ip();
        let mut associated_client = None;
        let mut udp_buffer = vec![0u8; UDP_BUFFER_SIZE];
        let mut control_probe = [0u8; 1];
        let control_closed = async {
            let _ = client.read(&mut control_probe).await;
        };
        tokio::pin!(control_closed);

        loop {
            tokio::select! {
                _ = &mut control_closed => break,
                recv = udp_socket.recv_from(&mut udp_buffer) => {
                    let (received, source) = recv?;
                    if source.ip() != control_ip {
                        continue;
                    }
                    associated_client = Some(source);
                let (target, payload) = decode_udp_frame(&udp_buffer[..received])?;
                *self.last_target.lock().expect("last target") = Some(target.to_string());
                    if let Err(error) = udp_session.send_to(&target, payload).await {
                        *self.last_handshake_error.lock().expect("handshake error") = Some(error.to_string());
                        return Err(error);
                    }
                }
                result = udp_session.recv_from() => {
                    let (target, payload) = result?;
                    let Some(destination) = associated_client else {
                        continue;
                    };
                    let frame = encode_udp_frame(&target, &payload)?;
                    udp_socket.send_to(&frame, destination).await?;
                }
            }
        }

        Ok(())
    }
}
