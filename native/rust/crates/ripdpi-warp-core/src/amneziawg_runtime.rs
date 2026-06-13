// SPDX-License-Identifier: BSD-3-Clause AND MIT
//
// Generic AmneziaWG profile runtime for `ripdpi-warp-core`.
//
// Where [`crate::runtime::WarpRuntime`] drives a *Cloudflare WARP*
// WireGuard session (account/device/scanner-aware), this module drives a
// **user-configured AmneziaWG peer**: an arbitrary endpoint + key pair +
// AmneziaWG obfuscation knobs, with no Cloudflare-specific provisioning.
//
// It reuses the entire warp-core data plane unchanged -- the boringtun Noise
// handshake ([`crate::wireguard::WireGuardTunnel`]), the AmneziaWG wire codec
// ([`crate::amneziawg`]), the smoltcp userspace netstack
// ([`crate::virtual_iface`]), and the loopback SOCKS5 front end
// ([`crate::socks`]) -- and only swaps the *config surface* and *telemetry
// source*. The result is a local SOCKS proxy whose traffic egresses through
// the AmneziaWG tunnel, exactly mirroring how WARP is consumed by the Android
// VpnService TUN->SOCKS bridge.
//
// The deltas a generic AmneziaWG peer needs over WARP are threaded through
// [`crate::wireguard::WireGuardTunnelParams`]: an optional preshared key, a
// configurable persistent-keepalive interval, and the AWG 2.0 `I1..I5`
// special-junk frames.

use std::io;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use serde::{Deserialize, Serialize};
use tokio::net::TcpListener;
use tokio::task::JoinHandle;
use tokio::time::{Duration, timeout};

use crate::config::{ResolvedWarpRuntimeEndpoint, WarpAmneziaConfig, now_ms, parse_ipv4_cidr, resolve_endpoint};
use crate::platform::WarpPlatform;
use crate::ports::{PortProtocol, UdpAssociationPool, VirtualPortPool};
use crate::socks::handle_socks_client;
use crate::support::to_io_error;
use crate::virtual_iface::{Bus, DynamicTcpInterface, DynamicUdpInterface};
use crate::wireguard::{WireGuardTunnel, WireGuardTunnelParams};

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);
const MIN_MTU: i32 = 1280;
const TELEMETRY_SOURCE: &str = "amneziawg";

/// Resolved, self-contained configuration for a generic AmneziaWG tunnel.
///
/// This is the Kotlin<->Rust JSON wire DTO (camelCase) the
/// `ripdpi-amneziawg-android` bridge deserializes from the
/// `AmneziaWgProfileScreen` form. Unlike [`crate::config::ResolvedWarpRuntimeConfig`]
/// it carries no Cloudflare account/device/scanner fields.
#[derive(Debug, Clone, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct AmneziaWgProfileConfig {
    /// When `false`, [`AmneziaWgRuntime::run`] returns immediately without
    /// opening a socket -- used to model the "configured but not connecting"
    /// state without a separate type.
    pub enabled: bool,
    /// Stable identifier for telemetry / logging (never a secret).
    pub profile_id: String,
    /// Base64 Curve25519 interface private key (`[Interface] PrivateKey`).
    pub private_key: String,
    /// Base64 Curve25519 peer public key (`[Peer] PublicKey`).
    pub peer_public_key: String,
    /// Base64 32-byte preshared key (`[Peer] PresharedKey`); empty = none.
    #[serde(default)]
    pub preshared_key: String,
    /// Peer endpoint host (`[Peer] Endpoint` host part); may be a hostname.
    pub endpoint_host: String,
    /// Optional pre-resolved IPv4 for the endpoint (skips DNS when present).
    #[serde(default)]
    pub endpoint_ipv4: String,
    /// Optional pre-resolved IPv6 for the endpoint.
    #[serde(default)]
    pub endpoint_ipv6: String,
    /// Peer endpoint UDP port.
    pub endpoint_port: i32,
    /// Interface IPv4 address in CIDR form (`[Interface] Address`).
    pub interface_address_v4: String,
    /// Optional interface IPv6 address in CIDR form.
    #[serde(default)]
    pub interface_address_v6: String,
    /// `[Interface] MTU`; clamped to a 1280 floor.
    pub mtu: i32,
    /// `[Peer] PersistentKeepalive` in seconds; `0` = disabled.
    #[serde(default)]
    pub persistent_keepalive: i32,
    /// AmneziaWG `Jc/Jmin/Jmax/H1..H4/S1..S2` obfuscation knobs.
    #[serde(default)]
    pub amnezia: AmneziaWgObfuscation,
    /// Loopback SOCKS5 bind host (e.g. `127.0.0.1`).
    pub local_socks_host: String,
    /// Loopback SOCKS5 bind port.
    pub local_socks_port: i32,
}

/// AmneziaWG obfuscation knobs as carried by the `AmneziaWgProfileScreen`
/// form. Mirrors the Kotlin `AmneziaWgParameters` field set (note: the editor
/// exposes only `S1`/`S2`; `S3`/`S4` are AWG-2.x cookie/transport padding and
/// are held at `0` here).
#[derive(Debug, Clone, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct AmneziaWgObfuscation {
    pub jc: i32,
    pub jmin: i32,
    pub jmax: i32,
    pub s1: i32,
    pub s2: i32,
    pub h1: i64,
    pub h2: i64,
    pub h3: i64,
    pub h4: i64,
    #[serde(default)]
    pub i1: String,
    #[serde(default)]
    pub i2: String,
    #[serde(default)]
    pub i3: String,
    #[serde(default)]
    pub i4: String,
    #[serde(default)]
    pub i5: String,
}

impl AmneziaWgObfuscation {
    /// `true` when at least one obfuscation knob is set. When false the tunnel
    /// is wire-identical to upstream WireGuard (the codec runs in passthrough).
    fn is_active(&self) -> bool {
        self.jc != 0
            || self.s1 != 0
            || self.s2 != 0
            || self.h1 != 0
            || self.h2 != 0
            || self.h3 != 0
            || self.h4 != 0
            || !self.i1.is_empty()
            || !self.i2.is_empty()
            || !self.i3.is_empty()
            || !self.i4.is_empty()
            || !self.i5.is_empty()
    }

    /// Project onto the data-plane [`WarpAmneziaConfig`] consumed by the AWG
    /// wire codec. `S3`/`S4` are always `0` (the editor does not expose them).
    fn to_warp_amnezia(&self) -> WarpAmneziaConfig {
        WarpAmneziaConfig {
            enabled: self.is_active(),
            jc: self.jc,
            jmin: self.jmin,
            jmax: self.jmax,
            h1: self.h1,
            h2: self.h2,
            h3: self.h3,
            h4: self.h4,
            s1: self.s1,
            s2: self.s2,
            s3: 0,
            s4: 0,
        }
    }
}

/// Telemetry snapshot for a generic AmneziaWG tunnel (camelCase JSON).
/// Parallel to [`crate::config::WarpTelemetry`] with `source = "amneziawg"`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AmneziaWgTelemetry {
    pub source: &'static str,
    pub state: String,
    pub active_sessions: u64,
    pub total_sessions: u64,
    pub listener_address: Option<String>,
    pub upstream_address: Option<String>,
    pub profile_id: Option<String>,
    pub last_error: Option<String>,
    pub captured_at: u64,
}

/// A running (or runnable) generic AmneziaWG tunnel that fronts a loopback
/// SOCKS5 listener. Lifecycle mirrors [`crate::runtime::WarpRuntime`]:
/// `new` -> `run` (blocks for the tunnel's lifetime) -> `stop`.
pub struct AmneziaWgRuntime {
    config: AmneziaWgProfileConfig,
    platform: WarpPlatform,
    stop_requested: AtomicBool,
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    listener_address: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
    readiness_observer: Mutex<Option<Arc<dyn Fn() + Send + Sync>>>,
}

impl AmneziaWgRuntime {
    pub fn new(config: AmneziaWgProfileConfig) -> Arc<Self> {
        Self::with_platform(config, WarpPlatform::default())
    }

    pub fn with_platform(config: AmneziaWgProfileConfig, platform: WarpPlatform) -> Arc<Self> {
        Arc::new(Self {
            config,
            platform,
            stop_requested: AtomicBool::new(false),
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            listener_address: Mutex::new(None),
            last_error: Mutex::new(None),
            readiness_observer: Mutex::new(None),
        })
    }

    /// Signal a blocked [`AmneziaWgRuntime::run`] to unwind. Idempotent.
    pub fn stop(&self) {
        self.stop_requested.store(true, Ordering::SeqCst);
    }

    /// Install a readiness observer fired once when the SOCKS listener is
    /// bound (ADR 0003 native readiness push). Install before [`Self::run`].
    ///
    /// Cancel-safety: synchronous; no `.await` inside.
    pub fn set_readiness_observer(&self, observer: Arc<dyn Fn() + Send + Sync>) {
        if let Ok(mut guard) = self.readiness_observer.lock() {
            *guard = Some(observer);
        }
    }

    fn notify_ready(&self) {
        let observer = match self.readiness_observer.lock() {
            Ok(guard) => guard.as_ref().map(Arc::clone),
            Err(_) => None,
        };
        if let Some(observer) = observer {
            observer();
        }
    }

    pub fn telemetry(&self) -> AmneziaWgTelemetry {
        let running = self.running.load(Ordering::SeqCst);
        AmneziaWgTelemetry {
            source: TELEMETRY_SOURCE,
            state: if running { "running".to_string() } else { "idle".to_string() },
            active_sessions: self.active_sessions.load(Ordering::SeqCst),
            total_sessions: self.total_sessions.load(Ordering::SeqCst),
            listener_address: self.listener_address.lock().expect("listener address").clone(),
            upstream_address: Some(format!("{}:{}", self.config.endpoint_host, self.config.endpoint_port)),
            profile_id: Some(self.config.profile_id.clone()),
            last_error: self.last_error.lock().expect("last error").clone(),
            captured_at: now_ms(),
        }
    }

    /// Resolve the peer endpoint, building boringtun + the AWG codec, and run
    /// the SOCKS-fronted tunnel until [`Self::stop`] is called. Blocks for the
    /// whole tunnel lifetime (the JNI bridge calls this on a dedicated thread).
    ///
    /// # Cancel safety
    /// Not cancel-safe: dropping this future mid-run leaks the spawned tunnel
    /// tasks. The runtime is torn down only via [`Self::stop`], which lets the
    /// accept loop exit and abort the tasks in order. The bridge never selects
    /// over this future; it owns the thread.
    pub async fn run(self: Arc<Self>) -> io::Result<()> {
        if !self.config.enabled {
            return Ok(());
        }
        let source_peer_ip = parse_ipv4_cidr(Some(self.config.interface_address_v4.as_str())).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "AmneziaWG runtime requires an IPv4 interface address")
        })?;
        let endpoint = resolve_endpoint(&self.endpoint_descriptor()).await?;

        let amnezia_cfg = self.config.amnezia.to_warp_amnezia();
        let keepalive = u16::try_from(self.config.persistent_keepalive.max(0)).ok().filter(|value| *value != 0);
        let preshared = (!self.config.preshared_key.is_empty()).then_some(self.config.preshared_key.as_str());
        let tunnel = Arc::new(
            WireGuardTunnel::new(
                WireGuardTunnelParams {
                    private_key: &self.config.private_key,
                    peer_public_key: &self.config.peer_public_key,
                    preshared_key: preshared,
                    persistent_keepalive: keepalive,
                    endpoint,
                    // A generic AmneziaWG peer carries no Cloudflare reserved bytes.
                    reserved: [0u8; 3],
                    source_peer_ip,
                    amnezia_cfg: &amnezia_cfg,
                    special_junk_hex: [
                        self.config.amnezia.i1.as_str(),
                        self.config.amnezia.i2.as_str(),
                        self.config.amnezia.i3.as_str(),
                        self.config.amnezia.i4.as_str(),
                        self.config.amnezia.i5.as_str(),
                    ],
                },
                &self.platform,
            )
            .await
            .map_err(to_io_error)?,
        );

        // AmneziaWG handshake prelude (I1..I5 special junk + Jc random junk)
        // is emitted before the first WireGuard handshake. No-op when
        // obfuscation is disabled.
        tunnel.send_amnezia_junk().await;

        let bus = Bus::new();
        let mut tasks = Vec::<JoinHandle<()>>::new();
        let tcp_pool = Arc::new(VirtualPortPool::new(PortProtocol::Tcp));
        let udp_pool = Arc::new(UdpAssociationPool::new());
        let bind_addr = format!("{}:{}", self.config.local_socks_host, self.config.local_socks_port);
        let listener = TcpListener::bind(&bind_addr).await?;
        let mtu = self.config.mtu.max(MIN_MTU) as usize;

        {
            let tunnel = Arc::clone(&tunnel);
            let bus = bus.clone();
            tasks.push(tokio::spawn(async move { tunnel.consume_task(bus).await }));
        }
        {
            let tunnel = Arc::clone(&tunnel);
            let bus = bus.clone();
            tasks.push(tokio::spawn(async move { tunnel.produce_task(bus).await }));
        }
        {
            let tunnel = Arc::clone(&tunnel);
            tasks.push(tokio::spawn(async move { tunnel.routine_task().await }));
        }
        {
            let interface = DynamicTcpInterface::new(bus.clone(), source_peer_ip, mtu);
            tasks.push(tokio::spawn(async move {
                if let Err(error) = interface.run().await {
                    tracing::warn!("AmneziaWG TCP virtual interface stopped: {error}");
                }
            }));
        }
        {
            let interface = DynamicUdpInterface::new(bus.clone(), source_peer_ip, mtu);
            tasks.push(tokio::spawn(async move {
                if let Err(error) = interface.run().await {
                    tracing::warn!("AmneziaWG UDP virtual interface stopped: {error}");
                }
            }));
        }

        *self.listener_address.lock().expect("listener address") = Some(bind_addr.clone());
        self.running.store(true, Ordering::SeqCst);
        emit_runtime_ready(&bind_addr);
        self.notify_ready();

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
        bus.shutdown();
        for task in tasks {
            task.abort();
            let _ = task.await;
        }
        emit_runtime_stopped();
        Ok(())
    }

    fn endpoint_descriptor(&self) -> ResolvedWarpRuntimeEndpoint {
        let optional = |value: &str| (!value.is_empty()).then(|| value.to_string());
        ResolvedWarpRuntimeEndpoint {
            host: self.config.endpoint_host.clone(),
            ipv4: optional(&self.config.endpoint_ipv4),
            ipv6: optional(&self.config.endpoint_ipv6),
            port: self.config.endpoint_port,
            source: "profile".to_string(),
        }
    }
}

fn emit_runtime_ready(bind_addr: &str) {
    tracing::info!(
        ring = "amneziawg",
        subsystem = "amneziawg",
        source = "amneziawg",
        kind = "runtime_ready",
        "listener started addr={bind_addr}"
    );
}

fn emit_runtime_stopped() {
    tracing::info!(
        ring = "amneziawg",
        subsystem = "amneziawg",
        source = "amneziawg",
        kind = "runtime_stopped",
        "listener stopped"
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    fn obf(jc: i32, s1: i32, h1: i64) -> AmneziaWgObfuscation {
        AmneziaWgObfuscation { jc, s1, h1, ..Default::default() }
    }

    #[test]
    fn obfuscation_is_active_only_when_a_knob_is_set() {
        assert!(!AmneziaWgObfuscation::default().is_active());
        assert!(obf(4, 0, 0).is_active());
        assert!(obf(0, 8, 0).is_active());
        assert!(obf(0, 0, 0x10_00_00_01).is_active());
        let with_i = AmneziaWgObfuscation { i1: "deadbeef".to_string(), ..Default::default() };
        assert!(with_i.is_active());
    }

    #[test]
    fn to_warp_amnezia_maps_knobs_and_zeroes_s3_s4() {
        let o = AmneziaWgObfuscation {
            jc: 4,
            jmin: 10,
            jmax: 50,
            s1: 8,
            s2: 12,
            h1: 1,
            h2: 2,
            h3: 3,
            h4: 4,
            ..Default::default()
        };
        let w = o.to_warp_amnezia();
        assert!(w.enabled);
        assert_eq!((w.jc, w.jmin, w.jmax), (4, 10, 50));
        assert_eq!((w.s1, w.s2, w.s3, w.s4), (8, 12, 0, 0));
        assert_eq!((w.h1, w.h2, w.h3, w.h4), (1, 2, 3, 4));
    }

    #[test]
    fn disabled_config_run_returns_immediately() {
        let rt = AmneziaWgRuntime::new(AmneziaWgProfileConfig::default());
        let result =
            tokio::runtime::Builder::new_current_thread().enable_all().build().expect("runtime").block_on(rt.run());
        assert!(result.is_ok());
    }

    #[test]
    fn telemetry_reports_idle_before_run() {
        let cfg = AmneziaWgProfileConfig {
            profile_id: "awg-1".to_string(),
            endpoint_host: "vpn.example.org".to_string(),
            endpoint_port: 51820,
            ..Default::default()
        };
        let rt = AmneziaWgRuntime::new(cfg);
        let t = rt.telemetry();
        assert_eq!(t.source, "amneziawg");
        assert_eq!(t.state, "idle");
        assert_eq!(t.profile_id.as_deref(), Some("awg-1"));
        assert_eq!(t.upstream_address.as_deref(), Some("vpn.example.org:51820"));
    }

    #[test]
    fn config_round_trips_through_json() {
        let json = r#"{
            "enabled": true,
            "profileId": "p1",
            "privateKey": "Qd...",
            "peerPublicKey": "Pk...",
            "presharedKey": "",
            "endpointHost": "1.2.3.4",
            "endpointPort": 51820,
            "interfaceAddressV4": "10.8.0.2/32",
            "mtu": 1420,
            "persistentKeepalive": 25,
            "amnezia": { "jc": 4, "jmin": 10, "jmax": 50, "s1": 8, "s2": 0,
                         "h1": 1, "h2": 2, "h3": 3, "h4": 4, "i1": "dead" },
            "localSocksHost": "127.0.0.1",
            "localSocksPort": 11090
        }"#;
        let cfg: AmneziaWgProfileConfig = serde_json::from_str(json).expect("parse");
        assert!(cfg.enabled);
        assert_eq!(cfg.endpoint_port, 51820);
        assert_eq!(cfg.persistent_keepalive, 25);
        assert_eq!(cfg.amnezia.jc, 4);
        assert_eq!(cfg.amnezia.i1, "dead");
        assert!(cfg.amnezia.is_active());
        // Re-serialize and re-parse to confirm camelCase symmetry.
        let s = serde_json::to_string(&cfg).expect("serialize");
        let again: AmneziaWgProfileConfig = serde_json::from_str(&s).expect("reparse");
        assert_eq!(again.local_socks_port, 11090);
    }
}
