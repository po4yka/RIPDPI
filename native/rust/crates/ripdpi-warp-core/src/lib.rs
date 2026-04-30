use std::io;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use tokio::net::TcpListener;
use tokio::time::{timeout, Duration};

mod amnezia;
mod config;
mod endpoint_probe;
mod platform;
mod ports;
mod socks;
mod virtual_iface;
mod wireguard;

pub use config::{
    ResolvedWarpRuntimeConfig, ResolvedWarpRuntimeEndpoint, WarpAmneziaConfig, WarpEndpointProbeRequest,
    WarpEndpointProbeResult, WarpManualEndpoint, WarpTelemetry,
};
pub use endpoint_probe::{probe_endpoint, probe_endpoint_with_platform};
pub use platform::{WarpPlatform, WarpSocketProtector};

use config::{now_ms, parse_ipv4_cidr, resolve_endpoint};
use ports::{PortProtocol, UdpAssociationPool, VirtualPortPool};
use socks::handle_socks_client;
use virtual_iface::{Bus, DynamicTcpInterface, DynamicUdpInterface};
use wireguard::{reserved_bytes_from_client_id, WireGuardTunnel};

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);
const READY_SOURCE: &str = "warp";
pub(crate) const MAX_PACKET: usize = 65_536;

fn emit_runtime_ready(bind_addr: &str) {
    tracing::info!(
        ring = "warp",
        subsystem = "warp",
        source = "warp",
        kind = "runtime_ready",
        "listener started addr={bind_addr}"
    );
}

fn emit_runtime_stopped() {
    tracing::info!(ring = "warp", subsystem = "warp", source = "warp", kind = "runtime_stopped", "listener stopped");
}

pub struct WarpRuntime {
    config: ResolvedWarpRuntimeConfig,
    platform: WarpPlatform,
    stop_requested: AtomicBool,
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    listener_address: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
}

impl WarpRuntime {
    pub fn new(config: ResolvedWarpRuntimeConfig) -> Arc<Self> {
        Self::with_platform(config, WarpPlatform::default())
    }

    pub fn with_platform(config: ResolvedWarpRuntimeConfig, platform: WarpPlatform) -> Arc<Self> {
        Arc::new(Self {
            config,
            platform,
            stop_requested: AtomicBool::new(false),
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            listener_address: Mutex::new(None),
            last_error: Mutex::new(None),
        })
    }

    pub fn stop(&self) {
        self.stop_requested.store(true, Ordering::SeqCst);
    }

    pub fn telemetry(&self) -> WarpTelemetry {
        WarpTelemetry {
            source: READY_SOURCE,
            state: if self.running.load(Ordering::SeqCst) { "running".to_string() } else { "idle".to_string() },
            health: if self.running.load(Ordering::SeqCst) { "running".to_string() } else { "idle".to_string() },
            active_sessions: self.active_sessions.load(Ordering::SeqCst),
            total_sessions: self.total_sessions.load(Ordering::SeqCst),
            listener_address: self.listener_address.lock().expect("listener address").clone(),
            upstream_address: Some(format!("{}:{}", self.config.endpoint.host, self.config.endpoint.port)),
            upstream_rtt_ms: None,
            profile_id: Some(self.config.profile_id.clone()),
            last_error: self.last_error.lock().expect("last error").clone(),
            captured_at: now_ms(),
        }
    }

    pub async fn run(self: Arc<Self>) -> io::Result<()> {
        if !self.config.enabled {
            return Ok(());
        }
        let source_peer_ip = parse_ipv4_cidr(self.config.interface_address_v4.as_deref()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "WARP runtime requires IPv4 interface address")
        })?;
        let endpoint = resolve_endpoint(&self.config.endpoint).await?;
        let reserved = reserved_bytes_from_client_id(self.config.client_id.as_deref());
        let tunnel = Arc::new(
            WireGuardTunnel::new(
                &self.config.private_key,
                &self.config.peer_public_key,
                endpoint,
                reserved,
                source_peer_ip,
                &self.config.amnezia,
                &self.platform,
            )
            .await
            .map_err(to_io_error)?,
        );

        // AmneziaWG junk packets are sent before the first WireGuard handshake
        // to defeat protocol fingerprinting.
        if self.config.amnezia.enabled {
            tunnel.send_amnezia_junk(&self.config.amnezia).await;
        }
        let bus = Bus::new();
        let tcp_pool = Arc::new(VirtualPortPool::new(PortProtocol::Tcp));
        let udp_pool = Arc::new(UdpAssociationPool::new());

        {
            let tunnel = Arc::clone(&tunnel);
            let bus = bus.clone();
            tokio::spawn(async move { tunnel.consume_task(bus).await });
        }
        {
            let tunnel = Arc::clone(&tunnel);
            let bus = bus.clone();
            tokio::spawn(async move { tunnel.produce_task(bus).await });
        }
        {
            let tunnel = Arc::clone(&tunnel);
            tokio::spawn(async move { tunnel.routine_task().await });
        }
        {
            let interface = DynamicTcpInterface::new(bus.clone(), source_peer_ip, self.config.mtu.max(1280) as usize);
            tokio::spawn(async move { interface.run().await });
        }
        {
            let interface = DynamicUdpInterface::new(bus.clone(), source_peer_ip, self.config.mtu.max(1280) as usize);
            tokio::spawn(async move { interface.run().await });
        }

        let bind_addr = format!("{}:{}", self.config.local_socks_host, self.config.local_socks_port);
        let listener = TcpListener::bind(&bind_addr).await?;
        *self.listener_address.lock().expect("listener address") = Some(bind_addr);
        self.running.store(true, Ordering::SeqCst);
        emit_runtime_ready(self.listener_address.lock().expect("listener address").as_deref().unwrap_or_default());

        while !self.stop_requested.load(Ordering::SeqCst) {
            match timeout(ACCEPT_POLL_INTERVAL, listener.accept()).await {
                Ok(Ok((stream, _))) => {
                    self.active_sessions.fetch_add(1, Ordering::SeqCst);
                    self.total_sessions.fetch_add(1, Ordering::SeqCst);
                    let runtime = Arc::clone(&self);
                    let bus = bus.clone();
                    let tcp_pool = Arc::clone(&tcp_pool);
                    let udp_pool = Arc::clone(&udp_pool);
                    tokio::spawn(async move {
                        if let Err(error) = handle_socks_client(stream, bus, tcp_pool, udp_pool).await {
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
        Ok(())
    }
}

fn to_io_error(error: anyhow::Error) -> io::Error {
    io::Error::other(error.to_string())
}
