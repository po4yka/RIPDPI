use std::io;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use arc_swap::ArcSwapOption;
use ripdpi_tls_profiles::profile_catalog_version;
use tokio::net::TcpListener;
use tokio::time::timeout;

use crate::backend::{build_backend, RelayBackend};
use crate::config::{RelayKind, ResolvedRelayRuntimeConfig};
use crate::runtime_validation::{
    describe_runtime_health, describe_upstream, planned_backend_capabilities, planned_backend_fallback_mode,
    validate_runtime_config,
};
use crate::socks::{handle_client, SocksSessionConfig, SocksTelemetry};
use crate::telemetry::{now_ms, RelayTelemetry};

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);

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
    backend: OnceLock<Arc<RelayBackend>>,
    listener_address: OnceLock<String>,
    last_target: ArcSwapOption<String>,
    last_error: ArcSwapOption<String>,
    last_handshake_error: ArcSwapOption<String>,
}

impl RelayRuntime {
    pub fn new(config: ResolvedRelayRuntimeConfig) -> Arc<Self> {
        Arc::new(Self {
            config,
            stop_requested: AtomicBool::new(false),
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            backend: OnceLock::new(),
            listener_address: OnceLock::new(),
            last_target: ArcSwapOption::empty(),
            last_error: ArcSwapOption::empty(),
            last_handshake_error: ArcSwapOption::empty(),
        })
    }

    pub fn stop(&self) {
        self.stop_requested.store(true, Ordering::SeqCst);
    }

    pub fn telemetry(&self) -> RelayTelemetry {
        let backend = self.backend.get();
        let capabilities =
            backend.map_or_else(|| planned_backend_capabilities(&self.config), |backend| backend.capabilities());
        let (quic_migration_status, quic_migration_reason) =
            backend.map_or((None, None), |backend| backend.quic_migration_snapshot());
        let is_running = self.running.load(Ordering::SeqCst);
        let state = if is_running { "running" } else { "idle" };

        RelayTelemetry {
            source: "relay",
            state: state.to_string(),
            health: describe_runtime_health(state, backend.map(Arc::as_ref)),
            active_sessions: self.active_sessions.load(Ordering::SeqCst),
            total_sessions: self.total_sessions.load(Ordering::SeqCst),
            listener_address: self.listener_address.get().cloned(),
            upstream_address: Some(describe_upstream(&self.config)),
            last_target: load_optional_string(&self.last_target),
            last_error: load_optional_string(&self.last_error),
            profile_id: Some(self.config.common.profile_id.clone()),
            protocol_kind: Some(self.config.kind_id().to_string()),
            tcp_capable: Some(capabilities.tcp),
            udp_capable: Some(capabilities.udp),
            fallback_mode: planned_backend_fallback_mode(&self.config),
            last_handshake_error: load_optional_string(&self.last_handshake_error),
            chain_entry_state: if matches!(RelayKind::from_config(&self.config), RelayKind::ChainRelay) {
                Some(if is_running { "connected" } else { "idle" }.to_string())
            } else {
                None
            },
            chain_exit_state: if matches!(RelayKind::from_config(&self.config), RelayKind::ChainRelay) {
                Some(if is_running { "connected" } else { "idle" }.to_string())
            } else {
                None
            },
            strategy_pack_id: None,
            strategy_pack_version: None,
            tls_profile_id: Some(self.config.common.tls_fingerprint_profile.clone()),
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
        self.backend
            .set(Arc::clone(&backend))
            .map_err(|_| io::Error::new(io::ErrorKind::AlreadyExists, "relay backend was already initialized"))?;

        let bind_addr = format!("{}:{}", self.config.common.local_socks_host, self.config.common.local_socks_port);
        let listener = TcpListener::bind(&bind_addr).await?;
        self.listener_address.set(bind_addr.clone()).map_err(|_| {
            io::Error::new(io::ErrorKind::AlreadyExists, "relay listener address was already initialized")
        })?;
        self.running.store(true, Ordering::SeqCst);
        emit_runtime_ready(&bind_addr);

        while !self.stop_requested.load(Ordering::SeqCst) {
            match timeout(ACCEPT_POLL_INTERVAL, listener.accept()).await {
                Ok(Ok((stream, _))) => {
                    let runtime = Arc::clone(&self);
                    let backend = Arc::clone(&backend);
                    tokio::spawn(async move {
                        runtime.active_sessions.fetch_add(1, Ordering::SeqCst);
                        runtime.total_sessions.fetch_add(1, Ordering::SeqCst);
                        let socks_config = SocksSessionConfig {
                            local_socks_host: runtime.config.common.local_socks_host.clone(),
                            backend_kind: runtime.config.kind_id().to_string(),
                        };
                        if let Err(error) = handle_client(stream, backend, socks_config, runtime.as_ref()).await {
                            runtime.last_error.store(Some(Arc::new(error.to_string())));
                        }
                        runtime.active_sessions.fetch_sub(1, Ordering::SeqCst);
                    });
                }
                Ok(Err(error)) => {
                    self.last_error.store(Some(Arc::new(error.to_string())));
                }
                Err(_) => {}
            }
        }

        self.running.store(false, Ordering::SeqCst);
        emit_runtime_stopped();
        Ok(())
    }
}

impl SocksTelemetry for RelayRuntime {
    fn record_target(&self, target: String) {
        self.last_target.store(Some(Arc::new(target)));
    }

    fn record_handshake_error(&self, error: String) {
        self.last_handshake_error.store(Some(Arc::new(error)));
    }
}

fn load_optional_string(slot: &ArcSwapOption<String>) -> Option<String> {
    slot.load_full().as_deref().cloned()
}
