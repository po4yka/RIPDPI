use std::io;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use tokio::net::TcpListener;
use tokio::sync::mpsc::unbounded_channel;
use tokio::task::{JoinHandle, JoinSet};
use tokio::time::{Duration, timeout};

use crate::amneziawg_runtime::{parse_interface_ipv6, spawn_supervised};
use crate::config::{ResolvedWarpRuntimeConfig, WarpTelemetry, now_ms, parse_ipv4_cidr, resolve_endpoint};
use crate::observe::TcpConnectObservation;
use crate::platform::WarpPlatform;
use crate::ports::{PortProtocol, UdpAssociationPool, VirtualPortPool};
use crate::socks::{MAX_SOCKS_SESSIONS, accept_backoff, handle_socks_client};
use crate::support::to_io_error;
use crate::virtual_iface::{Bus, DynamicTcpInterface, DynamicUdpInterface};
use crate::wireguard::{WireGuardTunnel, WireGuardTunnelParams, reserved_bytes_from_client_id};

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);
const READY_SOURCE: &str = "warp";

pub struct WarpRuntime {
    config: ResolvedWarpRuntimeConfig,
    platform: WarpPlatform,
    stop_requested: AtomicBool,
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    listener_address: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
    quality_observer: Mutex<Option<Arc<dyn Fn(TcpConnectObservation) + Send + Sync>>>,
    readiness_observer: Mutex<Option<Arc<dyn Fn() + Send + Sync>>>,
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
            quality_observer: Mutex::new(None),
            readiness_observer: Mutex::new(None),
        })
    }

    pub fn stop(&self) {
        self.stop_requested.store(true, Ordering::SeqCst);
    }

    /// Install a quality observer invoked after every SOCKS session completes.
    /// Replaces any previously installed observer.
    ///
    /// Note: WARP uses virtual TCP via smoltcp — no real RTT is available at
    /// this layer. The observer receives `rtt_ms = 0` for succeeded sessions;
    /// adapter code should only record failures into the loss counter.
    ///
    /// Cancel-safety: synchronous; no `.await` inside.
    pub fn set_quality_observer(&self, observer: Arc<dyn Fn(TcpConnectObservation) + Send + Sync>) {
        if let Ok(mut guard) = self.quality_observer.lock() {
            *guard = Some(observer);
        }
    }

    /// Fire the quality observer with `obs`. Clone the `Arc` inside the lock,
    /// release the lock, then invoke — reentrancy-safe (mirrors tunnel-core
    /// observer pattern from `stats/observer.rs`).
    ///
    /// Cancel-safety: synchronous; no `.await` inside.
    fn emit_connect_observation(&self, obs: TcpConnectObservation) {
        let observer = match self.quality_observer.lock() {
            Ok(guard) => guard.as_ref().map(Arc::clone),
            Err(_) => None,
        };
        if let Some(observer) = observer {
            observer(obs);
        }
    }

    /// Install a readiness observer fired exactly once when the listener is
    /// bound and WARP is about to serve (immediately after the `runtime_ready`
    /// event). The adapter layer wires this to a native readiness push so
    /// Kotlin no longer polls telemetry (see ADR 0003); install it before
    /// [`WarpRuntime::run`] starts.
    ///
    /// Cancel-safety: synchronous; no `.await` inside.
    pub fn set_readiness_observer(&self, observer: Arc<dyn Fn() + Send + Sync>) {
        if let Ok(mut guard) = self.readiness_observer.lock() {
            *guard = Some(observer);
        }
    }

    /// Fire the readiness observer, if installed. Clone the `Arc` inside the
    /// lock, release the lock, then invoke — reentrancy-safe, mirroring
    /// `emit_connect_observation`.
    ///
    /// Cancel-safety: synchronous; no `.await` inside.
    fn notify_ready(&self) {
        let observer = match self.readiness_observer.lock() {
            Ok(guard) => guard.as_ref().map(Arc::clone),
            Err(_) => None,
        };
        if let Some(observer) = observer {
            observer();
        }
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
        let source_peer_ipv6 = warp_source_ipv6(self.config.interface_address_v6.as_deref());
        let endpoint = resolve_endpoint(&self.config.endpoint).await?;
        let reserved = reserved_bytes_from_client_id(self.config.client_id.as_deref());
        let tunnel = Arc::new(
            WireGuardTunnel::new(
                WireGuardTunnelParams {
                    private_key: &self.config.private_key,
                    peer_public_key: &self.config.peer_public_key,
                    // WARP itself uses no PSK and a 25s keepalive, but a generic
                    // AmneziaWG peer driven through this runtime may override both
                    // via the config's amnezia block. `persistent_keepalive`
                    // defaults to 25 when omitted, preserving WARP's pin.
                    preshared_key: self.config.amnezia.preshared_key_opt(),
                    persistent_keepalive: self.config.amnezia.persistent_keepalive_opt(),
                    persistent_keepalive_max: None,
                    endpoint,
                    reserved,
                    source_peer_ip,
                    source_peer_ipv6,
                    mtu: self.config.mtu.max(1280) as usize,
                    amnezia_cfg: &self.config.amnezia,
                    // AWG 2.0 I1..I5 special-junk frames, sourced from the
                    // resolved WARP config's own `i1..i5` hex fields. Empty
                    // strings mean unset, preserving prior behavior when the
                    // config does not configure them.
                    signature_programs: self.config.amnezia.signature_programs(),
                    // WARP always egresses over plain WireGuard UDP; the
                    // WG-over-WebSocket carrier is a generic-AmneziaWG-profile
                    // opt-in. `None` keeps the tunnel binding its own UDP socket.
                    carrier: None,
                },
                &self.platform,
            )
            .await
            .map_err(to_io_error)?,
        );

        // AmneziaWG handshake prelude (special-junk frames + Jc junk packets)
        // is sent before the first WireGuard handshake to defeat protocol
        // fingerprinting. A no-op when AWG obfuscation is disabled.
        let bus = Bus::new();
        let mut tasks = Vec::<JoinHandle<()>>::new();
        let tcp_pool = Arc::new(VirtualPortPool::new(PortProtocol::Tcp)?);
        let udp_pool = Arc::new(UdpAssociationPool::new());
        crate::socks::ensure_loopback_socks_host(&self.config.local_socks_host)?;
        let bind_addr = format!("{}:{}", self.config.local_socks_host, self.config.local_socks_port);
        let listener = TcpListener::bind(&bind_addr).await?;

        // Every tunnel and interface task is supervised: its exit fails the runtime, so the
        // Kotlin supervisor restarts WARP instead of serving connections that only hang.
        let (failure_tx, mut failure_rx) = unbounded_channel::<String>();
        {
            let (tunnel, bus) = (Arc::clone(&tunnel), bus.clone());
            spawn_supervised(&mut tasks, &failure_tx, "WARP tunnel receive", async move {
                let message = format!("WARP tunnel receive stopped ({})", tunnel.consume_task(bus).await.kind());
                tracing::warn!("{message}");
                Some(message)
            });
        }
        {
            let (tunnel, bus) = (Arc::clone(&tunnel), bus.clone());
            spawn_supervised(&mut tasks, &failure_tx, "WARP packet send", async move {
                tunnel.produce_task(bus).await;
                None
            });
        }
        {
            let tunnel = Arc::clone(&tunnel);
            spawn_supervised(&mut tasks, &failure_tx, "WARP timer", async move {
                tunnel.routine_task().await;
                None
            });
        }
        let mtu = self.config.mtu.max(1280) as usize;
        let tcp = DynamicTcpInterface::new(bus.clone(), source_peer_ip, source_peer_ipv6, mtu);
        spawn_supervised(&mut tasks, &failure_tx, "WARP TCP interface", async move {
            tcp.run().await.err().map(|error| format!("WARP TCP virtual interface stopped: {error}"))
        });
        let udp = DynamicUdpInterface::new(bus.clone(), source_peer_ip, source_peer_ipv6, mtu);
        spawn_supervised(&mut tasks, &failure_tx, "WARP UDP interface", async move {
            udp.run().await.err().map(|error| format!("WARP UDP virtual interface stopped: {error}"))
        });
        drop(failure_tx);

        *self.listener_address.lock().expect("listener address") = Some(bind_addr);
        self.running.store(true, Ordering::SeqCst);
        emit_runtime_ready(self.listener_address.lock().expect("listener address").as_deref().unwrap_or_default());
        // Push readiness to any installed observer (native readiness event,
        // ADR 0003) at the same point the `runtime_ready` telemetry fires, so
        // the Kotlin wrapper need not poll. No-op when no observer is set.
        self.notify_ready();

        // Track client tasks so shutdown closes them and resets the session count.
        let mut clients = JoinSet::new();
        let mut accept_failures = 0u32;
        let mut runtime_failure = None;
        while !self.stop_requested.load(Ordering::SeqCst) {
            while clients.try_join_next().is_some() {}
            let accepted = tokio::select! {
                failure = failure_rx.recv() => {
                    let message = failure.unwrap_or_else(|| "WARP tunnel tasks stopped".to_string());
                    *self.last_error.lock().expect("last error") = Some(message.clone());
                    runtime_failure = Some(io::Error::other(message));
                    break;
                }
                accepted = timeout(ACCEPT_POLL_INTERVAL, listener.accept()) => accepted,
            };
            match accepted {
                Ok(Ok((stream, _))) => {
                    accept_failures = 0;
                    if clients.len() >= MAX_SOCKS_SESSIONS {
                        drop(stream);
                        continue;
                    }
                    self.active_sessions.fetch_add(1, Ordering::SeqCst);
                    self.total_sessions.fetch_add(1, Ordering::SeqCst);
                    let runtime = Arc::clone(&self);
                    let bus = bus.clone();
                    let tcp_pool = Arc::clone(&tcp_pool);
                    let udp_pool = Arc::clone(&udp_pool);
                    clients.spawn(async move {
                        match handle_socks_client(stream, bus, tcp_pool, udp_pool).await {
                            Ok(()) => {
                                runtime.emit_connect_observation(TcpConnectObservation { rtt_ms: 0, succeeded: true });
                            }
                            Err(error) => {
                                runtime.emit_connect_observation(TcpConnectObservation { rtt_ms: 0, succeeded: false });
                                *runtime.last_error.lock().expect("last error") = Some(error.to_string());
                            }
                        }
                        runtime.active_sessions.fetch_sub(1, Ordering::SeqCst);
                    });
                }
                Ok(Err(error)) => {
                    *self.last_error.lock().expect("last error") = Some(error.to_string());
                    accept_failures = accept_failures.saturating_add(1);
                    tokio::time::sleep(accept_backoff(accept_failures)).await;
                }
                Err(_) => {}
            }
        }

        self.running.store(false, Ordering::SeqCst);
        bus.shutdown();
        for task in tasks {
            task.abort();
            let _ = task.await;
        }
        clients.shutdown().await;
        self.active_sessions.store(0, Ordering::SeqCst);
        emit_runtime_stopped();
        runtime_failure.map_or(Ok(()), Err)
    }
}

/// The IPv6 interface address Cloudflare assigned, so IPv6 targets get a source
/// address. An invalid value keeps WARP IPv4-only, as before, instead of failing the start.
fn warp_source_ipv6(value: Option<&str>) -> Option<std::net::Ipv6Addr> {
    parse_interface_ipv6(value.unwrap_or_default().trim()).unwrap_or_else(|error| {
        tracing::warn!("ignoring the WARP IPv6 interface address: {error}");
        None
    })
}

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

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

    use super::*;
    use crate::config::{
        ResolvedWarpRuntimeConfig, ResolvedWarpRuntimeEndpoint, WarpAmneziaConfig, WarpManualEndpoint,
    };

    fn dummy_config() -> ResolvedWarpRuntimeConfig {
        let manual = WarpManualEndpoint {
            host: "engage.cloudflareclient.com".to_string(),
            ipv4: "162.159.192.1".to_string(),
            ipv6: "2606:4700:d0::a29f:c001".to_string(),
            port: 2408,
        };
        ResolvedWarpRuntimeConfig {
            enabled: false,
            profile_id: "test".to_string(),
            account_kind: "free".to_string(),
            device_id: "device".to_string(),
            access_token: "token".to_string(),
            private_key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=".to_string(),
            public_key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=".to_string(),
            peer_public_key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=".to_string(),
            client_id: None,
            interface_address_v4: Some("172.16.0.2/32".to_string()),
            interface_address_v6: None,
            endpoint: ResolvedWarpRuntimeEndpoint {
                host: manual.host.clone(),
                ipv4: Some(manual.ipv4.clone()),
                ipv6: Some(manual.ipv6.clone()),
                port: manual.port,
                source: "manual".to_string(),
            },
            route_mode: "all".to_string(),
            route_hosts: String::new(),
            built_in_rules_enabled: false,
            endpoint_selection_mode: "manual".to_string(),
            manual_endpoint: manual,
            scanner_enabled: false,
            scanner_parallelism: 0,
            scanner_max_rtt_ms: 0,
            mtu: 1280,
            local_socks_host: "127.0.0.1".to_string(),
            local_socks_port: 11_080,
            amnezia: WarpAmneziaConfig::default(),
        }
    }

    /// Smoke: observer is invoked when `emit_connect_observation` is called.
    #[test]
    fn warp_ipv6_interface_address_becomes_the_ipv6_source() {
        let expected: std::net::Ipv6Addr = "2606:4700:110:8a36::1".parse().expect("IPv6");
        assert_eq!(warp_source_ipv6(Some("2606:4700:110:8a36::1/128")), Some(expected));
        assert_eq!(warp_source_ipv6(Some("2606:4700:110:8a36::1")), Some(expected));
        assert_eq!(warp_source_ipv6(None), None);
        assert_eq!(warp_source_ipv6(Some("not-an-address")), None);
    }

    #[test]
    fn observer_fires_on_emit() {
        let runtime = WarpRuntime::new(dummy_config());
        let count = Arc::new(AtomicU64::new(0));
        let count_clone = Arc::clone(&count);
        runtime.set_quality_observer(Arc::new(move |_obs| {
            count_clone.fetch_add(1, Ordering::Relaxed);
        }));
        runtime.emit_connect_observation(TcpConnectObservation { rtt_ms: 0, succeeded: true });
        assert_eq!(count.load(Ordering::Relaxed), 1);
    }

    /// Observer receives the exact observation values.
    #[test]
    fn observer_receives_correct_values() {
        let runtime = WarpRuntime::new(dummy_config());
        let received_ok = Arc::new(AtomicBool::new(false));
        let received_rtt = Arc::new(AtomicU64::new(u64::MAX));
        let ok_clone = Arc::clone(&received_ok);
        let rtt_clone = Arc::clone(&received_rtt);
        runtime.set_quality_observer(Arc::new(move |obs| {
            ok_clone.store(obs.succeeded, Ordering::Relaxed);
            rtt_clone.store(obs.rtt_ms, Ordering::Relaxed);
        }));
        runtime.emit_connect_observation(TcpConnectObservation { rtt_ms: 0, succeeded: false });
        assert!(!received_ok.load(Ordering::Relaxed));
        assert_eq!(received_rtt.load(Ordering::Relaxed), 0);
    }

    /// No observer installed: `emit_connect_observation` is a no-op (no panic).
    #[test]
    fn no_observer_emit_is_noop() {
        let runtime = WarpRuntime::new(dummy_config());
        // Must not panic.
        runtime.emit_connect_observation(TcpConnectObservation { rtt_ms: 0, succeeded: true });
    }

    /// `set_quality_observer` replaces the previous observer.
    #[test]
    fn set_quality_observer_replaces_previous() {
        let runtime = WarpRuntime::new(dummy_config());
        let first_count = Arc::new(AtomicU64::new(0));
        let second_count = Arc::new(AtomicU64::new(0));

        let first_clone = Arc::clone(&first_count);
        runtime.set_quality_observer(Arc::new(move |_| {
            first_clone.fetch_add(1, Ordering::Relaxed);
        }));

        let second_clone = Arc::clone(&second_count);
        runtime.set_quality_observer(Arc::new(move |_| {
            second_clone.fetch_add(1, Ordering::Relaxed);
        }));

        runtime.emit_connect_observation(TcpConnectObservation { rtt_ms: 0, succeeded: true });

        assert_eq!(first_count.load(Ordering::Relaxed), 0, "first observer must not fire after replacement");
        assert_eq!(second_count.load(Ordering::Relaxed), 1, "second observer must fire");
    }

    /// Smoke: readiness observer is invoked when `notify_ready` is called.
    #[test]
    fn readiness_observer_fires_on_notify() {
        let runtime = WarpRuntime::new(dummy_config());
        let count = Arc::new(AtomicU64::new(0));
        let count_clone = Arc::clone(&count);
        runtime.set_readiness_observer(Arc::new(move || {
            count_clone.fetch_add(1, Ordering::Relaxed);
        }));
        runtime.notify_ready();
        assert_eq!(count.load(Ordering::Relaxed), 1);
    }

    /// No readiness observer installed: `notify_ready` is a no-op (no panic).
    #[test]
    fn no_readiness_observer_notify_is_noop() {
        let runtime = WarpRuntime::new(dummy_config());
        // Must not panic.
        runtime.notify_ready();
    }

    /// `set_readiness_observer` replaces the previous observer.
    #[test]
    fn set_readiness_observer_replaces_previous() {
        let runtime = WarpRuntime::new(dummy_config());
        let first_count = Arc::new(AtomicU64::new(0));
        let second_count = Arc::new(AtomicU64::new(0));

        let first_clone = Arc::clone(&first_count);
        runtime.set_readiness_observer(Arc::new(move || {
            first_clone.fetch_add(1, Ordering::Relaxed);
        }));

        let second_clone = Arc::clone(&second_count);
        runtime.set_readiness_observer(Arc::new(move || {
            second_clone.fetch_add(1, Ordering::Relaxed);
        }));

        runtime.notify_ready();

        assert_eq!(first_count.load(Ordering::Relaxed), 0, "first readiness observer must not fire after replacement");
        assert_eq!(second_count.load(Ordering::Relaxed), 1, "second readiness observer must fire");
    }

    /// Multiple emit calls all reach the observer.
    #[test]
    fn observer_fires_for_every_emit() {
        let runtime = WarpRuntime::new(dummy_config());
        let count = Arc::new(AtomicU64::new(0));
        let count_clone = Arc::clone(&count);
        runtime.set_quality_observer(Arc::new(move |_| {
            count_clone.fetch_add(1, Ordering::Relaxed);
        }));
        for _ in 0..5 {
            runtime.emit_connect_observation(TcpConnectObservation { rtt_ms: 0, succeeded: true });
        }
        assert_eq!(count.load(Ordering::Relaxed), 5);
    }
}
