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
// configurable persistent-keepalive interval, and the AWG 3.1 `I1..I5`
// special-junk frames.

use std::io;
use std::net::{IpAddr, Ipv6Addr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use serde::{Deserialize, Serialize};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc::{UnboundedReceiver, unbounded_channel};
use tokio::sync::watch;
use tokio::task::{JoinHandle, JoinSet};
use tokio::time::{Duration, timeout};

use crate::config::{
    AwgV3Config, ResolvedWarpRuntimeEndpoint, WarpAmneziaConfig, now_ms, parse_ipv4_cidr, resolve_endpoint,
};
use crate::platform::WarpPlatform;
use crate::ports::{PortProtocol, UdpAssociationPool, VirtualPortPool};
use crate::socks::handle_socks_client;
use crate::support::to_io_error;
use crate::virtual_iface::{Bus, DynamicTcpInterface, DynamicUdpInterface};
use crate::wireguard::{WgCarrier, WireGuardTunnel, WireGuardTunnelParams, connect_ws_carrier};

const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(100);
const MIN_MTU: i32 = 1280;
const TELEMETRY_SOURCE: &str = "amneziawg";
/// Bound on the WSS carrier TCP connect, TLS handshake and WebSocket upgrade.
/// A DPI black hole that accepts TCP and then stays silent would hang forever.
const WS_CARRIER_CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
/// Every task that can report a failure holds a sender until shutdown, so a
/// closed failure channel means they all died.
/// Bound on the wait for the peer's handshake response, so an unreachable
/// peer fails the start instead of leaving it pending. Short under test.
const HANDSHAKE_TIMEOUT: Duration = if cfg!(test) { Duration::from_millis(1_000) } else { Duration::from_secs(20) };
const TASKS_GONE: &str = "AmneziaWG tunnel tasks stopped";

enum RuntimePoll {
    Client(TcpStream),
    ListenerError(io::Error),
    CriticalFailure(String),
    Idle,
}

/// Wait for one SOCKS listener event or a critical virtual-interface failure.
///
/// # Cancel safety
/// Cancel-safe: both `TcpListener::accept` and `UnboundedReceiver::recv` leave
/// their queues unchanged when cancelled, and this function mutates no state
/// before returning an outcome.
async fn poll_runtime(listener: &TcpListener, failures: &mut UnboundedReceiver<String>) -> RuntimePoll {
    tokio::select! {
        failure = failures.recv() => RuntimePoll::CriticalFailure(failure.unwrap_or_else(|| TASKS_GONE.to_string())),
        accepted = timeout(ACCEPT_POLL_INTERVAL, listener.accept()) => match accepted {
            Ok(Ok((stream, _))) => RuntimePoll::Client(stream),
            Ok(Err(error)) => RuntimePoll::ListenerError(error),
            Err(_) => RuntimePoll::Idle,
        },
    }
}

/// Resolved, self-contained configuration for a generic AmneziaWG tunnel.
///
/// This is the Kotlin<->Rust JSON wire DTO (camelCase) the
/// `ripdpi-amneziawg-android` bridge deserializes from the
/// `AmneziaWgProfileScreen` form. Unlike [`crate::config::ResolvedWarpRuntimeConfig`]
/// it carries no Cloudflare account/device/scanner fields.
#[derive(Clone, Deserialize, Serialize, Default)]
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
    /// Upper bound of an AWG 3.x `PersistentKeepalive = lo-hi` range; `0` = a fixed interval.
    #[serde(default)]
    pub persistent_keepalive_max: i32,
    /// AmneziaWG `Jc/Jmin/Jmax/H1..H4/S1..S2` obfuscation knobs.
    #[serde(default)]
    pub amnezia: AmneziaWgObfuscation,
    /// Transport carrier the WireGuard datagrams egress over. Additive serde
    /// default of [`AmneziaWgCarrierKind::Udp`] preserves today's plain-UDP
    /// behavior, so a config that omits the field (or a native core built
    /// before the carrier seam) deserializes unchanged and the native schema
    /// does not bump.
    #[serde(default)]
    pub carrier: AmneziaWgCarrierKind,
    /// WebSocket carrier request URL (e.g. `wss://host:443/path`). Only consulted
    /// when [`Self::carrier`] is [`AmneziaWgCarrierKind::Ws`]; ignored (and
    /// typically empty) for the UDP path. The host/port are user-pasted config —
    /// never logged or forwarded to telemetry in plain form
    /// (network-fingerprint-privacy).
    #[serde(default)]
    pub carrier_ws_url: String,
    /// Loopback SOCKS5 bind host (e.g. `127.0.0.1`).
    pub local_socks_host: String,
    /// Loopback SOCKS5 bind port.
    pub local_socks_port: i32,
}

impl std::fmt::Debug for AmneziaWgProfileConfig {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("AmneziaWgProfileConfig")
            .field("enabled", &self.enabled)
            .field("profile_id", &self.profile_id)
            .field("private_key", &"<redacted>")
            .field("preshared_key", &"<redacted>")
            .field("carrier_ws_url", &"<redacted>")
            .field("carrier", &self.carrier)
            .field("mtu", &self.mtu)
            .field("local_socks_port", &self.local_socks_port)
            .finish_non_exhaustive()
    }
}

/// Selects the transport the AmneziaWG tunnel's WireGuard datagrams ride over.
///
/// `serde(rename_all = "snake_case")` so the wire tokens are `"udp"` / `"ws"`,
/// matching the Kotlin `RipDpiAmneziaWgCarrierKind` enum. Defaults to
/// [`Self::Udp`] (today's behavior) so the field is fully additive.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum AmneziaWgCarrierKind {
    /// Plain WireGuard over UDP (the default): the tunnel binds + protects its
    /// own `UdpSocket`.
    #[default]
    Udp,
    /// WireGuard framed over a WebSocket carrier on a single protected TLS/TCP
    /// stream (see [`crate::wireguard::WgCarrier`] and `ripdpi-wireguard-ws`).
    Ws,
}

/// AmneziaWG obfuscation knobs as carried by the `AmneziaWgProfileScreen`
/// form. Mirrors the Kotlin `AmneziaWgParameters` field set, including the
/// AWG-2.x `S3`/`S4` cookie/transport padding sizes (additive serde defaults of
/// `0`, so a config that omits them deserializes cleanly and the native schema
/// does not bump).
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AmneziaWgObfuscation {
    pub wire_profile: String,
    pub jc: i32,
    pub jmin: i32,
    pub jmax: i32,
    pub s1: i32,
    pub s2: i32,
    #[serde(default)]
    pub s3: i32,
    #[serde(default)]
    pub s4: i32,
    pub h1: String,
    pub h2: String,
    pub h3: String,
    pub h4: String,
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
    /// AmneziaWG 3.x keys (header protection, padding, timers). Omitted = all off.
    #[serde(default)]
    pub v3: AwgV3Config,
}

impl Default for AmneziaWgObfuscation {
    fn default() -> Self {
        Self {
            wire_profile: crate::awg_profile::WIRE_PROFILE.into(),
            jc: 0,
            jmin: 0,
            jmax: 0,
            s1: 0,
            s2: 0,
            s3: 0,
            s4: 0,
            h1: "1".into(),
            h2: "2".into(),
            h3: "3".into(),
            h4: "4".into(),
            i1: String::new(),
            i2: String::new(),
            i3: String::new(),
            i4: String::new(),
            i5: String::new(),
            v3: AwgV3Config::default(),
        }
    }
}

impl AmneziaWgObfuscation {
    /// `true` when at least one obfuscation knob is set. When false the tunnel
    /// is wire-identical to upstream WireGuard (the codec runs in passthrough).
    ///
    /// Non-default size bounds also select validation, including when `jc` is zero.
    fn is_active(&self) -> bool {
        self.wire_profile != crate::awg_profile::WIRE_PROFILE
            || self.jc != 0
            || self.jmin != 0
            || self.jmax != 0
            || self.s1 != 0
            || self.s2 != 0
            || self.s3 != 0
            || self.s4 != 0
            || self.h1 != "1"
            || self.h2 != "2"
            || self.h3 != "3"
            || self.h4 != "4"
            || !self.i1.is_empty()
            || !self.i2.is_empty()
            || !self.i3.is_empty()
            || !self.i4.is_empty()
            || !self.i5.is_empty()
            || self.v3.is_set()
    }

    /// Project onto the data-plane [`WarpAmneziaConfig`] consumed by the AWG
    /// wire codec. The full `S1..S4` junk-size knobs carry through (the 4-slot
    /// `AwgWireCodec` raw-padding builder honors `s3`/`s4`). The AWG 3.1
    /// `I1..I5` special-junk frames are carried out-of-band via the tunnel's
    /// `signature_programs` parameter (sourced from this profile's
    /// `AmneziaWgObfuscation` `i1..i5`), so they default to empty here.
    fn to_warp_amnezia(&self) -> WarpAmneziaConfig {
        WarpAmneziaConfig {
            enabled: self.is_active(),
            wire_profile: self.wire_profile.clone(),
            jc: self.jc,
            jmin: self.jmin,
            jmax: self.jmax,
            h1: self.h1.clone(),
            h2: self.h2.clone(),
            h3: self.h3.clone(),
            h4: self.h4.clone(),
            s1: self.s1,
            s2: self.s2,
            s3: self.s3,
            s4: self.s4,
            v3: self.v3.clone(),
            ..Default::default()
        }
    }
}

/// `PersistentKeepalive` seconds as boringtun's interval plus the AWG 3.x range top.
/// `0` disables keepalive; a range (`max != 0`) must satisfy `1 <= low <= max <= 65535`,
/// because `0-N` has no defined meaning.
fn keepalive_bounds(low: i32, max: i32) -> io::Result<(Option<u16>, Option<u16>)> {
    let invalid = || io::Error::new(io::ErrorKind::InvalidInput, "AmneziaWG persistent keepalive range is invalid");
    let low = u16::try_from(low.max(0)).map_err(|_| invalid())?;
    if max == 0 {
        return Ok(((low != 0).then_some(low), None));
    }
    let max = u16::try_from(max).map_err(|_| invalid())?;
    if low == 0 || max < low {
        return Err(invalid());
    }
    Ok((Some(low), Some(max)))
}

/// Telemetry snapshot for a generic AmneziaWG tunnel (camelCase JSON).
/// Parallel to [`crate::config::WarpTelemetry`] with `source = "amneziawg"`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AmneziaWgTelemetry {
    pub source: &'static str,
    pub state: String,
    /// Mirrors `state` (`running`/`idle`); kept distinct for parity with
    /// `WarpTelemetry` and the Kotlin `NativeRuntimeSnapshot.health` field, so a
    /// running tunnel does not surface the snapshot's default `health = "idle"`.
    pub health: String,
    pub active_sessions: u64,
    pub total_sessions: u64,
    /// Cumulative count of successful WG-over-WebSocket carrier handshakes (a
    /// protected carrier socket opened, TLS/WS upgraded, and the first real
    /// WireGuard datagram framed). Stays `0` on the plain-UDP path. Additive
    /// telemetry field: serde defaults to `0` so a snapshot from a build
    /// without the carrier path decodes unchanged, and the JNI snapshot schema
    /// version does not bump.
    pub ws_carrier_handshakes: u64,
    /// Cumulative count of WG-over-WebSocket carrier handshakes that failed
    /// before the first datagram could be framed (protect rejection, connect,
    /// TLS, or WS-upgrade failure). Additive; defaults to `0`.
    pub ws_carrier_handshake_failures: u64,
    pub listener_address: Option<String>,
    /// Redacted for privacy: AWG endpoints are user-supplied server identities.
    /// Keep the Rust field for shared model parity, but never emit it in JSON.
    #[serde(skip_serializing)]
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
    stop: watch::Sender<bool>,
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    ws_carrier_handshakes: AtomicU64,
    ws_carrier_handshake_failures: AtomicU64,
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
            stop: watch::Sender::new(false),
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            ws_carrier_handshakes: AtomicU64::new(0),
            ws_carrier_handshake_failures: AtomicU64::new(0),
            listener_address: Mutex::new(None),
            last_error: Mutex::new(None),
            readiness_observer: Mutex::new(None),
        })
    }

    /// Signal a blocked [`AmneziaWgRuntime::run`] to unwind. Idempotent.
    pub fn stop(&self) {
        self.stop.send_replace(true);
    }

    fn stop_requested(&self) -> bool {
        *self.stop.borrow()
    }

    /// Complete once [`Self::stop`] has been called, also before this call.
    ///
    /// # Cancel safety
    /// cancel-safe: it only observes the stop flag.
    async fn stopped(&self) {
        // The sender lives in `self`, so the wait ends only on a stop request.
        let _ = self.stop.subscribe().wait_for(|stopped| *stopped).await;
    }

    /// Record one successful WG-over-WebSocket carrier handshake.
    ///
    /// The WG-over-WSS carrier-select path (an additive transport seam tracked
    /// as a follow-up B1 slice) calls this once it has opened a protected
    /// carrier socket, completed the TLS/WS upgrade, and framed the first real
    /// WireGuard datagram. The counter is surfaced through [`Self::telemetry`]
    /// so the carrier path is observable even before any UI knob exists. The
    /// plain-UDP path never calls it, leaving the counter at `0`.
    ///
    /// Cancel-safety: a single relaxed-style atomic add; no `.await`.
    pub fn record_ws_carrier_handshake(&self) {
        self.ws_carrier_handshakes.fetch_add(1, Ordering::SeqCst);
    }

    /// Record one failed WG-over-WebSocket carrier handshake (protect
    /// rejection, connect/TLS/WS-upgrade failure before the first datagram).
    ///
    /// Cancel-safety: a single relaxed-style atomic add; no `.await`.
    pub fn record_ws_carrier_handshake_failure(&self) {
        self.ws_carrier_handshake_failures.fetch_add(1, Ordering::SeqCst);
    }

    /// Install a readiness observer fired once when the remote WireGuard
    /// handshake is authenticated and the SOCKS listener can serve it (ADR
    /// 0003 native readiness push). Install before [`Self::run`].
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
        let state = if running { "running" } else { "idle" };
        AmneziaWgTelemetry {
            source: TELEMETRY_SOURCE,
            state: state.to_string(),
            health: state.to_string(),
            active_sessions: self.active_sessions.load(Ordering::SeqCst),
            total_sessions: self.total_sessions.load(Ordering::SeqCst),
            ws_carrier_handshakes: self.ws_carrier_handshakes.load(Ordering::SeqCst),
            ws_carrier_handshake_failures: self.ws_carrier_handshake_failures.load(Ordering::SeqCst),
            listener_address: self.listener_address.lock().expect("listener address").clone(),
            upstream_address: None,
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
        let source_peer_ipv6 = parse_interface_ipv6(&self.config.interface_address_v6)?;
        // A stop request, also one sent before `run`, ends network setup at once.
        let tunnel = tokio::select! {
            biased;
            () = self.stopped() => return Ok(()),
            tunnel = self.connect_tunnel(source_peer_ip, source_peer_ipv6) => Arc::new(tunnel?),
        };

        let bus = Bus::new();
        let mut tasks = Vec::<JoinHandle<()>>::new();
        let (failure_tx, mut failure_rx) = unbounded_channel::<String>();
        let tcp_pool = Arc::new(VirtualPortPool::new(PortProtocol::Tcp)?);
        let udp_pool = Arc::new(UdpAssociationPool::new());
        crate::socks::ensure_loopback_socks_host(&self.config.local_socks_host)?;
        let bind_addr = format!("{}:{}", self.config.local_socks_host, self.config.local_socks_port);
        let listener = TcpListener::bind(&bind_addr).await?;
        let bind_addr = listener.local_addr()?.to_string();
        let mtu = self.config.mtu.max(MIN_MTU) as usize;

        {
            let tunnel = Arc::clone(&tunnel);
            let bus = bus.clone();
            let failure_tx = failure_tx.clone();
            tasks.push(tokio::spawn(async move {
                // A closed or broken carrier fails the runtime so its supervisor restarts it.
                let message = format!("AmneziaWG carrier stopped: {}", tunnel.consume_task(bus).await);
                tracing::warn!("{message}");
                let _ = failure_tx.send(message);
            }));
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
            let interface = DynamicTcpInterface::new(bus.clone(), source_peer_ip, source_peer_ipv6, mtu);
            let failure_tx = failure_tx.clone();
            tasks.push(tokio::spawn(async move {
                if let Err(error) = interface.run().await {
                    let message = format!("AmneziaWG TCP virtual interface stopped: {error}");
                    tracing::warn!("{message}");
                    let _ = failure_tx.send(message);
                }
            }));
        }
        {
            let interface = DynamicUdpInterface::new(bus.clone(), source_peer_ip, source_peer_ipv6, mtu);
            let failure_tx = failure_tx.clone();
            tasks.push(tokio::spawn(async move {
                if let Err(error) = interface.run().await {
                    let message = format!("AmneziaWG UDP virtual interface stopped: {error}");
                    tracing::warn!("{message}");
                    let _ = failure_tx.send(message);
                }
            }));
        }
        drop(failure_tx);

        // Arm the receive side first, then emit the AmneziaWG prelude and an
        // explicit handshake initiation. Readiness stays idle until boringtun
        // authenticates the peer's response; a bound local SOCKS port alone is
        // not evidence that the configured VPN is usable.
        if let Err(error) = tunnel.initiate_handshake().await {
            *self.last_error.lock().expect("last error") = Some(error.to_string());
            shutdown_runtime_tasks(&bus, tasks).await;
            emit_runtime_stopped();
            return Err(error);
        }

        let mut runtime_failure = self.await_handshake(&tunnel, &mut failure_rx).await;
        if runtime_failure.is_none() && !self.stop_requested() {
            *self.listener_address.lock().expect("listener address") = Some(bind_addr.clone());
            self.running.store(true, Ordering::SeqCst);
            emit_runtime_ready(&bind_addr);
            self.notify_ready();
        }

        // The owner must retain even clients stalled before their bus subscription.
        // A bus shutdown alone cannot reach an incomplete SOCKS greeting.
        let mut clients = JoinSet::new();
        while self.running.load(Ordering::SeqCst) && !self.stop_requested() {
            while clients.try_join_next().is_some() {}
            match poll_runtime(&listener, &mut failure_rx).await {
                RuntimePoll::Client(stream) => {
                    self.active_sessions.fetch_add(1, Ordering::SeqCst);
                    self.total_sessions.fetch_add(1, Ordering::SeqCst);
                    let runtime = Arc::clone(&self);
                    let bus = bus.clone();
                    let tcp_pool = Arc::clone(&tcp_pool);
                    let udp_pool = Arc::clone(&udp_pool);
                    clients.spawn(async move {
                        if let Err(error) = handle_socks_client(stream, bus, tcp_pool, udp_pool).await {
                            *runtime.last_error.lock().expect("last error") = Some(error.to_string());
                        }
                        runtime.active_sessions.fetch_sub(1, Ordering::SeqCst);
                    });
                }
                RuntimePoll::ListenerError(error) => {
                    *self.last_error.lock().expect("last error") = Some(error.to_string());
                }
                RuntimePoll::CriticalFailure(message) => {
                    *self.last_error.lock().expect("last error") = Some(message.clone());
                    runtime_failure = Some(io::Error::other(message));
                    break;
                }
                RuntimePoll::Idle => {}
            }
        }

        self.running.store(false, Ordering::SeqCst);
        drop(listener);
        shutdown_runtime_tasks(&bus, tasks).await;
        // Cancellation is terminal: close owned client sockets, then observe all
        // handlers exiting before this runtime reports a completed shutdown.
        clients.shutdown().await;
        self.active_sessions.store(0, Ordering::SeqCst);
        let cleared_listener = self
            .listener_address
            .lock()
            .map(|mut address| *address = None)
            .map_err(|_| io::Error::other("AmneziaWG listener state poisoned after shutdown"));
        emit_runtime_stopped();
        cleared_listener?;
        runtime_failure.map_or(Ok(()), Err)
    }

    /// Resolve the peer, open the configured carrier, and build the tunnel.
    ///
    /// # Cancel safety
    /// cancel-safe: dropping it drops the in-flight carrier connect and its
    /// socket. Only the carrier counters and `last_error` change, and only
    /// when the carrier connect completes.
    async fn connect_tunnel(
        &self,
        source_peer_ip: IpAddr,
        source_peer_ipv6: Option<Ipv6Addr>,
    ) -> io::Result<WireGuardTunnel> {
        let endpoint = resolve_endpoint(&self.endpoint_descriptor()).await?;

        let amnezia_cfg = self.config.amnezia.to_warp_amnezia();
        let (keepalive, keepalive_max) =
            keepalive_bounds(self.config.persistent_keepalive, self.config.persistent_keepalive_max)?;
        let preshared = (!self.config.preshared_key.is_empty()).then_some(self.config.preshared_key.as_str());

        // Carrier select. UDP (the default) hands the tunnel `None` so it binds +
        // protects its own UdpSocket. WS opens a protected carrier socket NOW
        // (protect-before-connect via the SAME VpnService.protect callback as the
        // UDP path), upgrades it to a WebSocket, and hands the tunnel the ready
        // carrier. A carrier-open success/failure is recorded on the telemetry
        // counters before the first WireGuard datagram is framed.
        let carrier = self.open_carrier().await?;

        WireGuardTunnel::new(
            WireGuardTunnelParams {
                private_key: &self.config.private_key,
                peer_public_key: &self.config.peer_public_key,
                preshared_key: preshared,
                persistent_keepalive: keepalive,
                persistent_keepalive_max: keepalive_max,
                endpoint,
                // A generic AmneziaWG peer carries no Cloudflare reserved bytes.
                reserved: [0u8; 3],
                source_peer_ip,
                source_peer_ipv6,
                amnezia_cfg: &amnezia_cfg,
                signature_programs: [
                    self.config.amnezia.i1.as_str(),
                    self.config.amnezia.i2.as_str(),
                    self.config.amnezia.i3.as_str(),
                    self.config.amnezia.i4.as_str(),
                    self.config.amnezia.i5.as_str(),
                ],
                // `None` for the UDP path (the tunnel binds its own socket);
                // `Some(ws)` when a WS carrier was opened above.
                carrier,
            },
            &self.platform,
        )
        .await
        .map_err(to_io_error)
    }

    /// Wait until the peer answers the handshake or [`Self::stop`] is called.
    /// A carrier that dies first, or a peer silent for [`HANDSHAKE_TIMEOUT`]
    /// (`TimedOut`), is a runtime failure: it is recorded and returned.
    ///
    /// # Cancel safety
    /// cancel-safe: `UnboundedReceiver::recv` loses no message when cancelled,
    /// and the failure is recorded only after it is received.
    async fn await_handshake(
        &self,
        tunnel: &WireGuardTunnel,
        failures: &mut UnboundedReceiver<String>,
    ) -> Option<io::Error> {
        let deadline = tokio::time::Instant::now() + HANDSHAKE_TIMEOUT;
        while !self.stop_requested() && !tunnel.is_handshake_established() {
            let failure = if tokio::time::Instant::now() >= deadline {
                io::Error::new(io::ErrorKind::TimedOut, "AmneziaWG peer handshake timed out")
            } else if let Ok(failure) = timeout(ACCEPT_POLL_INTERVAL, failures.recv()).await {
                io::Error::other(failure.unwrap_or_else(|| TASKS_GONE.to_string()))
            } else {
                continue;
            };
            if let Ok(mut last_error) = self.last_error.lock() {
                *last_error = Some(failure.to_string());
            }
            return Some(failure);
        }
        None
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

    /// Open the datagram transport for the configured carrier.
    ///
    /// * [`AmneziaWgCarrierKind::Udp`] returns `Ok(None)`: the tunnel binds +
    ///   protects its own `UdpSocket` (today's behavior).
    /// * [`AmneziaWgCarrierKind::Ws`] opens a protected carrier socket to the
    ///   parsed `carrier_ws_url` authority *now* — protect-before-connect via
    ///   the same `VpnService.protect` callback the UDP path uses — upgrades it
    ///   to a WSS WebSocket with URL-derived Host/SNI, and returns
    ///   `Ok(Some(carrier))`. A successful open
    ///   increments [`Self::record_ws_carrier_handshake`]; a failure increments
    ///   [`Self::record_ws_carrier_handshake_failure`], records the error, and
    ///   propagates so `run` fails closed rather than silently downgrading to
    ///   UDP (which would defeat the obfuscation the user selected).
    ///
    /// A WS carrier with an empty `carrier_ws_url` is a config error
    /// (`InvalidInput`) — there is nothing to connect to.
    ///
    /// # Cancel safety
    /// Not cancel-safe in aggregate (it mutates the failure counter on error),
    /// but `run` never selects over it.
    async fn open_carrier(&self) -> io::Result<Option<WgCarrier>> {
        match self.config.carrier {
            AmneziaWgCarrierKind::Udp => Ok(None),
            AmneziaWgCarrierKind::Ws => {
                if self.config.carrier_ws_url.is_empty() {
                    // A WS carrier that cannot even attempt to connect is a failed
                    // carrier handshake (counted), not a silent UDP downgrade.
                    self.record_ws_carrier_handshake_failure();
                    let error =
                        io::Error::new(io::ErrorKind::InvalidInput, "AmneziaWG WS carrier requires carrierWsUrl");
                    *self.last_error.lock().expect("last error") = Some(error.to_string());
                    return Err(error);
                }
                let protector = self.platform.carrier_protector();
                let connect =
                    timeout(WS_CARRIER_CONNECT_TIMEOUT, connect_ws_carrier(&self.config.carrier_ws_url, &protector));
                let connected = connect.await.unwrap_or_else(|_| {
                    Err(io::Error::new(io::ErrorKind::TimedOut, "AmneziaWG WS carrier connect timed out"))
                });
                match connected {
                    Ok(carrier) => {
                        self.record_ws_carrier_handshake();
                        // Never log the endpoint host/port (privacy): the scope
                        // is the opaque profile_id only.
                        tracing::info!(profile = %self.config.profile_id, "AmneziaWG WS carrier connected");
                        Ok(Some(carrier))
                    }
                    Err(error) => {
                        self.record_ws_carrier_handshake_failure();
                        *self.last_error.lock().expect("last error") = Some(error.to_string());
                        Err(error)
                    }
                }
            }
        }
    }
}

fn parse_interface_ipv6(value: &str) -> io::Result<Option<Ipv6Addr>> {
    if value.is_empty() {
        return Ok(None);
    }
    let (address, prefix) = value.split_once('/').unwrap_or((value, "128"));
    let invalid = || io::Error::new(io::ErrorKind::InvalidInput, "invalid AmneziaWG IPv6 interface address");
    let prefix: u8 = prefix.parse().map_err(|_| invalid())?;
    if prefix > 128 {
        return Err(invalid());
    }
    let address: Ipv6Addr = address.parse().map_err(|_| invalid())?;
    if address.is_unspecified() || address.is_multicast() {
        return Err(invalid());
    }
    Ok(Some(address))
}

/// # Cancel safety
/// NOT cancel-safe: completion requires joining every child after aborting it.
async fn shutdown_runtime_tasks(bus: &Bus, tasks: Vec<JoinHandle<()>>) {
    bus.shutdown();
    for task in tasks {
        task.abort();
        let _ = task.await;
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
    use base64::Engine as _;
    use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
    use boringtun::x25519::{PublicKey, StaticSecret};

    use super::*;

    fn obf(jc: i32, s1: i32, h1: i64) -> AmneziaWgObfuscation {
        AmneziaWgObfuscation { jc, s1, h1: (if h1 == 0 { 1 } else { h1 }).to_string(), ..Default::default() }
    }

    #[test]
    fn standalone_config_debug_redacts_secrets_and_carrier_url() {
        let config = AmneziaWgProfileConfig {
            private_key: "private-key-secret".into(),
            preshared_key: "preshared-secret".into(),
            carrier_ws_url: "wss://example.invalid/private-token".into(),
            ..Default::default()
        };
        let debug = format!("{config:?}");
        assert!(!debug.contains(&config.private_key), "private key leaked");
        assert!(!debug.contains(&config.preshared_key), "preshared key leaked");
        assert!(!debug.contains(&config.carrier_ws_url), "carrier URL leaked");
    }

    #[test]
    fn optional_ipv6_interface_is_validated_before_runtime_start() {
        assert_eq!(parse_interface_ipv6("").expect("unset"), None);
        assert_eq!(parse_interface_ipv6("fd77::2/128").expect("valid IPv6"), Some("fd77::2".parse().expect("address")));
        for invalid in ["fd77::2/129", "fd77::2/bad", "10.0.0.2/32", "::/128", "ff02::1/128"] {
            assert_eq!(parse_interface_ipv6(invalid).expect_err("invalid IPv6").kind(), io::ErrorKind::InvalidInput);
        }
    }

    #[test]
    fn obfuscation_is_active_only_when_a_knob_is_set() {
        assert!(!AmneziaWgObfuscation::default().is_active());
        assert!(obf(4, 0, 0).is_active());
        assert!(obf(0, 8, 0).is_active());
        assert!(obf(0, 0, 0x10_00_00_01).is_active());
        let with_i = AmneziaWgObfuscation { i1: "<b 0xdeadbeef>".to_string(), ..Default::default() };
        assert!(with_i.is_active());
        // s3/s4 are real junk-size knobs (the codec's 4-slot raw-padding builder
        // honors them), so an s3/s4-only config must also count as active --
        // otherwise to_warp_amnezia() would disable the codec and silently drop
        // the padding.
        let with_s3 = AmneziaWgObfuscation { s3: 16, ..Default::default() };
        assert!(with_s3.is_active());
        let with_s4 = AmneziaWgObfuscation { s4: 20, ..Default::default() };
        assert!(with_s4.is_active());
    }

    #[test]
    fn to_warp_amnezia_maps_knobs_including_s3_s4() {
        let o = AmneziaWgObfuscation {
            jc: 4,
            jmin: 10,
            jmax: 50,
            s1: 8,
            s2: 12,
            s3: 16,
            s4: 20,
            h1: "1".into(),
            h2: "2".into(),
            h3: "3".into(),
            h4: "4".into(),
            ..Default::default()
        };
        let w = o.to_warp_amnezia();
        assert!(w.enabled);
        assert_eq!((w.jc, w.jmin, w.jmax), (4, 10, 50));
        assert_eq!((w.s1, w.s2, w.s3, w.s4), (8, 12, 16, 20));
        assert_eq!((w.h1.as_str(), w.h2.as_str(), w.h3.as_str(), w.h4.as_str()), ("1", "2", "3", "4"));
    }

    #[test]
    fn disabled_config_run_returns_immediately() {
        let rt = AmneziaWgRuntime::new(AmneziaWgProfileConfig::default());
        let result =
            tokio::runtime::Builder::new_current_thread().enable_all().build().expect("runtime").block_on(rt.run());
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn dead_peer_never_publishes_runtime_readiness() {
        let sink = std::net::UdpSocket::bind("127.0.0.1:0").expect("reserve an unresponsive UDP endpoint");
        let endpoint_port = i32::from(sink.local_addr().expect("sink address").port());
        let local_private = StaticSecret::from([0x17; 32]);
        let peer_private = StaticSecret::from([0x2b; 32]);
        let config = AmneziaWgProfileConfig {
            enabled: true,
            profile_id: "dead-peer-readiness".to_string(),
            private_key: BASE64_STANDARD.encode(local_private.to_bytes()),
            peer_public_key: BASE64_STANDARD.encode(PublicKey::from(&peer_private).to_bytes()),
            endpoint_host: "127.0.0.1".to_string(),
            endpoint_ipv4: "127.0.0.1".to_string(),
            endpoint_port,
            interface_address_v4: "10.8.0.2/32".to_string(),
            mtu: 1420,
            local_socks_host: "127.0.0.1".to_string(),
            local_socks_port: 0,
            ..Default::default()
        };
        let runtime = AmneziaWgRuntime::new(config);
        let notified = Arc::new(AtomicBool::new(false));
        let observer_flag = Arc::clone(&notified);
        runtime.set_readiness_observer(Arc::new(move || observer_flag.store(true, Ordering::SeqCst)));

        let run_task = tokio::spawn(Arc::clone(&runtime).run());
        tokio::time::sleep(Duration::from_millis(250)).await;

        assert!(!run_task.is_finished(), "runtime must wait for a remote handshake rather than fail open");
        let telemetry = runtime.telemetry();
        assert_eq!(telemetry.state, "idle");
        assert_eq!(telemetry.listener_address, None);
        assert!(!notified.load(Ordering::SeqCst), "native readiness push must not fire for an unresponsive peer");

        runtime.stop();
        timeout(Duration::from_secs(1), run_task)
            .await
            .expect("stopped runtime must unwind")
            .expect("runtime task must join")
            .expect("stop before readiness is a clean shutdown");
    }

    #[tokio::test]
    async fn stop_interrupts_a_black_holed_ws_carrier_connect() {
        // Accepts TCP but never answers the TLS handshake, like a DPI black hole.
        let black_hole = TcpListener::bind("127.0.0.1:0").await.expect("black-hole listener");
        let carrier_ws_url = format!("wss://{}/wg", black_hole.local_addr().expect("black-hole address"));
        let held = tokio::spawn(async move { black_hole.accept().await.map(|(stream, _)| stream) });
        let config = AmneziaWgProfileConfig {
            enabled: true,
            carrier: AmneziaWgCarrierKind::Ws,
            carrier_ws_url,
            interface_address_v4: "10.8.0.2/32".to_string(),
            endpoint_host: "127.0.0.1".to_string(),
            endpoint_ipv4: "127.0.0.1".to_string(),
            endpoint_port: 443,
            local_socks_host: "127.0.0.1".to_string(),
            local_socks_port: 0,
            ..Default::default()
        };
        let runtime = AmneziaWgRuntime::new(config);
        let run_task = tokio::spawn(Arc::clone(&runtime).run());
        let _accepted = timeout(Duration::from_secs(1), held).await.expect("carrier connects").expect("accept task");
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert!(!run_task.is_finished(), "the carrier handshake is still pending");

        runtime.stop();
        timeout(Duration::from_secs(1), run_task)
            .await
            .expect("stop must interrupt a pending carrier connect")
            .expect("runtime task must join")
            .expect("stop before readiness is a clean shutdown");
    }

    #[tokio::test]
    async fn unanswered_handshake_fails_the_start_with_timed_out() {
        let sink = std::net::UdpSocket::bind("127.0.0.1:0").expect("reserve an unresponsive UDP endpoint");
        let local_private = StaticSecret::from([0x17; 32]);
        let peer_private = StaticSecret::from([0x2b; 32]);
        let config = AmneziaWgProfileConfig {
            enabled: true,
            private_key: BASE64_STANDARD.encode(local_private.to_bytes()),
            peer_public_key: BASE64_STANDARD.encode(PublicKey::from(&peer_private).to_bytes()),
            endpoint_host: "127.0.0.1".to_string(),
            endpoint_ipv4: "127.0.0.1".to_string(),
            endpoint_port: i32::from(sink.local_addr().expect("sink address").port()),
            interface_address_v4: "10.8.0.2/32".to_string(),
            mtu: 1420,
            local_socks_host: "127.0.0.1".to_string(),
            local_socks_port: 0,
            ..Default::default()
        };
        let runtime = AmneziaWgRuntime::new(config);

        let error = timeout(HANDSHAKE_TIMEOUT * 3, Arc::clone(&runtime).run())
            .await
            .expect("an unanswered handshake must not leave the start pending")
            .expect_err("an unanswered handshake fails the start");

        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
        assert_eq!(runtime.telemetry().last_error.as_deref(), Some("AmneziaWG peer handshake timed out"));
        assert_eq!(runtime.telemetry().state, "idle");
    }

    #[tokio::test]
    async fn critical_virtual_interface_failure_preempts_listener_poll() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind listener");
        let (failure_tx, mut failure_rx) = unbounded_channel();
        failure_tx.send("TCP virtual connect failed".to_string()).expect("send failure");

        let outcome = poll_runtime(&listener, &mut failure_rx).await;

        match outcome {
            RuntimePoll::CriticalFailure(message) => assert_eq!(message, "TCP virtual connect failed"),
            _ => panic!("critical interface failure must terminate the runtime poll"),
        }
    }

    #[tokio::test]
    async fn closed_failure_channel_is_a_critical_failure() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind listener");
        let (failure_tx, mut failure_rx) = unbounded_channel::<String>();
        drop(failure_tx);

        let outcome = poll_runtime(&listener, &mut failure_rx).await;

        assert!(
            matches!(outcome, RuntimePoll::CriticalFailure(message) if message == TASKS_GONE),
            "a runtime whose supervised tasks all died must not keep serving"
        );
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
        assert_eq!(t.upstream_address, None, "AWG telemetry must not expose endpoint host:port");
        // Carrier counters start at zero on a runtime that has never opened a
        // WG-over-WebSocket carrier (the plain-UDP / idle path).
        assert_eq!(t.ws_carrier_handshakes, 0);
        assert_eq!(t.ws_carrier_handshake_failures, 0);
    }

    #[test]
    fn telemetry_json_does_not_expose_endpoint_host_or_port() {
        let cfg = AmneziaWgProfileConfig {
            profile_id: "awg-1".to_string(),
            endpoint_host: "vpn.example.org".to_string(),
            endpoint_port: 51820,
            ..Default::default()
        };
        let rt = AmneziaWgRuntime::new(cfg);
        let json = serde_json::to_string(&rt.telemetry()).expect("serialize telemetry");
        assert!(!json.contains("vpn.example.org"), "AWG endpoint host leaked in telemetry JSON: {json}");
        assert!(!json.contains("51820"), "AWG endpoint port leaked in telemetry JSON: {json}");
        assert!(!json.contains("upstreamAddress"), "AWG telemetry should omit the endpoint field entirely: {json}");
    }

    #[test]
    fn carrier_handshake_counters_increment_and_surface_in_telemetry() {
        let rt = AmneziaWgRuntime::new(AmneziaWgProfileConfig::default());
        assert_eq!(rt.telemetry().ws_carrier_handshakes, 0);
        assert_eq!(rt.telemetry().ws_carrier_handshake_failures, 0);

        rt.record_ws_carrier_handshake();
        rt.record_ws_carrier_handshake();
        rt.record_ws_carrier_handshake_failure();

        let t = rt.telemetry();
        assert_eq!(t.ws_carrier_handshakes, 2, "two successful carrier handshakes recorded");
        assert_eq!(t.ws_carrier_handshake_failures, 1, "one failed carrier handshake recorded");
    }

    #[test]
    fn carrier_defaults_to_udp_when_omitted() {
        // A config with no `carrier` field deserializes to the UDP default,
        // preserving today's behavior (additive, no schema bump).
        let json = r#"{
            "enabled": true, "profileId": "p", "privateKey": "k", "peerPublicKey": "p",
            "endpointHost": "1.2.3.4", "endpointPort": 51820,
            "interfaceAddressV4": "10.8.0.2/32", "mtu": 1330,
            "localSocksHost": "127.0.0.1", "localSocksPort": 11090
        }"#;
        let cfg: AmneziaWgProfileConfig = serde_json::from_str(json).expect("parse");
        assert_eq!(cfg.carrier, AmneziaWgCarrierKind::Udp);
        assert!(cfg.carrier_ws_url.is_empty());
    }

    #[test]
    fn carrier_ws_select_round_trips() {
        let json = r#"{
            "enabled": true, "profileId": "p", "privateKey": "k", "peerPublicKey": "p",
            "endpointHost": "1.2.3.4", "endpointPort": 443,
            "interfaceAddressV4": "10.8.0.2/32", "mtu": 1330,
            "carrier": "ws", "carrierWsUrl": "wss://carrier.example.org:443/wg",
            "localSocksHost": "127.0.0.1", "localSocksPort": 11090
        }"#;
        let cfg: AmneziaWgProfileConfig = serde_json::from_str(json).expect("parse");
        assert_eq!(cfg.carrier, AmneziaWgCarrierKind::Ws);
        assert_eq!(cfg.carrier_ws_url, "wss://carrier.example.org:443/wg");
        // camelCase symmetry on re-serialize.
        let again: AmneziaWgProfileConfig =
            serde_json::from_str(&serde_json::to_string(&cfg).expect("serialize")).expect("reparse");
        assert_eq!(again.carrier, AmneziaWgCarrierKind::Ws);
    }

    #[test]
    fn ws_carrier_without_url_fails_closed() {
        // A WS carrier with no URL is a config error, not a silent UDP downgrade.
        let cfg = AmneziaWgProfileConfig {
            enabled: true,
            carrier: AmneziaWgCarrierKind::Ws,
            carrier_ws_url: String::new(),
            interface_address_v4: "10.8.0.2/32".to_string(),
            endpoint_host: "1.2.3.4".to_string(),
            endpoint_port: 443,
            local_socks_host: "127.0.0.1".to_string(),
            local_socks_port: 11090,
            ..Default::default()
        };
        let rt = AmneziaWgRuntime::new(cfg);
        let result = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("runtime")
            .block_on(rt.clone().run());
        let err = result.expect_err("WS carrier without a URL must fail run closed");
        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
        // The failure was recorded on the carrier counter, not silently dropped.
        assert_eq!(rt.telemetry().ws_carrier_handshake_failures, 1);
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
                         "s3": 16, "s4": 20,
                         "wireProfile": "awg-3.1-ranges-v1", "h1": "1", "h2": "2", "h3": "3", "h4": "4", "i1": "<b 0xdead>" },
            "localSocksHost": "127.0.0.1",
            "localSocksPort": 11090
        }"#;
        let cfg: AmneziaWgProfileConfig = serde_json::from_str(json).expect("parse");
        assert!(cfg.enabled);
        assert_eq!(cfg.endpoint_port, 51820);
        assert_eq!(cfg.persistent_keepalive, 25);
        assert_eq!(cfg.amnezia.jc, 4);
        assert_eq!((cfg.amnezia.s3, cfg.amnezia.s4), (16, 20));
        assert_eq!(cfg.amnezia.i1, "<b 0xdead>");
        assert!(cfg.amnezia.is_active());
        // Re-serialize and re-parse to confirm camelCase symmetry.
        let s = serde_json::to_string(&cfg).expect("serialize");
        let again: AmneziaWgProfileConfig = serde_json::from_str(&s).expect("reparse");
        assert_eq!(again.local_socks_port, 11090);
    }

    #[test]
    fn keepalive_ranges_must_start_above_zero() {
        assert_eq!(keepalive_bounds(0, 0).unwrap(), (None, None));
        assert_eq!(keepalive_bounds(25, 0).unwrap(), (Some(25), None));
        assert_eq!(keepalive_bounds(25, 35).unwrap(), (Some(25), Some(35)));
        for (low, max) in [(0, 35), (36, 35), (25, 70_000), (-1, 5)] {
            assert!(keepalive_bounds(low, max).is_err(), "{low}-{max}");
        }
    }

    /// Pins the AWG 3.x keys emitted by Kotlin `RipDpiAmneziaWgV3Config` and
    /// `ResolvedRipDpiAmneziaWgConfig.persistentKeepaliveMax`.
    #[test]
    fn awg3_keys_deserialize_activate_the_codec_and_stay_redacted() {
        let key = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=";
        let json = format!(
            r#"{{ "enabled": true, "profileId": "p3", "privateKey": "k", "peerPublicKey": "p",
                 "endpointHost": "1.2.3.4", "endpointPort": 51820, "interfaceAddressV4": "10.8.0.2/32",
                 "mtu": 1420, "persistentKeepalive": 25, "persistentKeepaliveMax": 35,
                 "amnezia": {{ "wireProfile": "awg-3.1-ranges-v1", "jc": 0, "jmin": 0, "jmax": 0,
                   "s1": 12, "s2": 12, "s3": 12, "s4": 12, "h1": "1", "h2": "2", "h3": "3", "h4": "4",
                   "v3": {{ "headerProtectionKey": "{key}", "contentPaddingAddition": "10-100",
                     "rekeyAfterTime": "100-120", "rekeyTimeout": "3-7", "rejectAfterTime": "150-180",
                     "keepaliveTimeout": "5-15", "maxHandshakeAttempts": "15-20",
                     "randomTrailers": true, "disableCookies": true }} }},
                 "localSocksHost": "127.0.0.1", "localSocksPort": 11090 }}"#
        );
        let cfg: AmneziaWgProfileConfig = serde_json::from_str(&json).expect("parse");
        assert_eq!(cfg.persistent_keepalive_max, 35);
        let v3 = &cfg.amnezia.v3;
        assert_eq!(v3.header_protection_key.0, key);
        assert_eq!((v3.content_padding_addition.as_str(), v3.max_handshake_attempts.as_str()), ("10-100", "15-20"));
        assert!(v3.random_trailers && v3.disable_cookies);
        assert!(cfg.amnezia.is_active(), "3.x keys alone must enable the AWG codec");
        let warp = cfg.amnezia.to_warp_amnezia();
        assert_eq!(&warp.v3, v3);
        assert!(crate::amneziawg::AwgParams::from_config_for_platform(&warp, &[""; 5], false).is_ok());
        assert!(!format!("{:?} {warp:?}", cfg.amnezia).contains(key), "Debug must redact the header protection key");

        let unknown = json.replace("\"disableCookies\"", "\"disableCookiez\"");
        assert!(serde_json::from_str::<AmneziaWgProfileConfig>(&unknown).is_err(), "unknown v3 keys fail closed");
        let without: AmneziaWgObfuscation = serde_json::from_str(
            r#"{"wireProfile":"awg-3.1-ranges-v1","jc":0,"jmin":0,"jmax":0,"s1":0,"s2":0,"h1":"1","h2":"2","h3":"3","h4":"4"}"#,
        )
        .expect("AWG 2.0 JSON without v3");
        assert_eq!(without.v3, AwgV3Config::default());
    }
}
