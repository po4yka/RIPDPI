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
use crate::wireguard::{WgCarrier, WireGuardTunnel, WireGuardTunnelParams, connect_ws_carrier};

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
#[derive(Debug, Clone, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct AmneziaWgObfuscation {
    pub jc: i32,
    pub jmin: i32,
    pub jmax: i32,
    pub s1: i32,
    pub s2: i32,
    #[serde(default)]
    pub s3: i32,
    #[serde(default)]
    pub s4: i32,
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
    ///
    /// `jmin`/`jmax` are deliberately NOT consulted: junk packets are only
    /// emitted when `jc > 0` (see [`crate::amneziawg::AwgParams::build_junk_packets`]
    /// and `is_passthrough`), so a config with only `jmin`/`jmax` set is inert.
    /// Do not add them here -- doing so would flip otherwise-passthrough tunnels
    /// into "active" and build a codec for nothing.
    fn is_active(&self) -> bool {
        self.jc != 0
            || self.s1 != 0
            || self.s2 != 0
            || self.s3 != 0
            || self.s4 != 0
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
    /// wire codec. The full `S1..S4` junk-size knobs carry through (the 4-slot
    /// `AwgWireCodec` raw-padding builder honors `s3`/`s4`). The AWG 2.0
    /// `I1..I5` special-junk frames are carried out-of-band via the tunnel's
    /// `special_junk_hex` parameter (sourced from this profile's
    /// `AmneziaWgObfuscation` `i1..i5`), so they default to empty here.
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
            s3: self.s3,
            s4: self.s4,
            ..Default::default()
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
    stop_requested: AtomicBool,
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
            stop_requested: AtomicBool::new(false),
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
        self.stop_requested.store(true, Ordering::SeqCst);
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
        let endpoint = resolve_endpoint(&self.endpoint_descriptor()).await?;

        let amnezia_cfg = self.config.amnezia.to_warp_amnezia();
        let keepalive = u16::try_from(self.config.persistent_keepalive.max(0)).ok().filter(|value| *value != 0);
        let preshared = (!self.config.preshared_key.is_empty()).then_some(self.config.preshared_key.as_str());

        // Carrier select. UDP (the default) hands the tunnel `None` so it binds +
        // protects its own UdpSocket. WS opens a protected carrier socket NOW
        // (protect-before-connect via the SAME VpnService.protect callback as the
        // UDP path), upgrades it to a WebSocket, and hands the tunnel the ready
        // carrier. A carrier-open success/failure is recorded on the telemetry
        // counters before the first WireGuard datagram is framed.
        let carrier = self.open_carrier().await?;

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
                    // `None` for the UDP path (the tunnel binds its own socket);
                    // `Some(ws)` when a WS carrier was opened above.
                    carrier,
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
        crate::socks::ensure_loopback_socks_host(&self.config.local_socks_host)?;
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
                match connect_ws_carrier(&self.config.carrier_ws_url, &protector).await {
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
            h1: 1,
            h2: 2,
            h3: 3,
            h4: 4,
            ..Default::default()
        };
        let w = o.to_warp_amnezia();
        assert!(w.enabled);
        assert_eq!((w.jc, w.jmin, w.jmax), (4, 10, 50));
        assert_eq!((w.s1, w.s2, w.s3, w.s4), (8, 12, 16, 20));
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
                         "h1": 1, "h2": 2, "h3": 3, "h4": 4, "i1": "dead" },
            "localSocksHost": "127.0.0.1",
            "localSocksPort": 11090
        }"#;
        let cfg: AmneziaWgProfileConfig = serde_json::from_str(json).expect("parse");
        assert!(cfg.enabled);
        assert_eq!(cfg.endpoint_port, 51820);
        assert_eq!(cfg.persistent_keepalive, 25);
        assert_eq!(cfg.amnezia.jc, 4);
        assert_eq!((cfg.amnezia.s3, cfg.amnezia.s4), (16, 20));
        assert_eq!(cfg.amnezia.i1, "dead");
        assert!(cfg.amnezia.is_active());
        // Re-serialize and re-parse to confirm camelCase symmetry.
        let s = serde_json::to_string(&cfg).expect("serialize");
        let again: AmneziaWgProfileConfig = serde_json::from_str(&s).expect("reparse");
        assert_eq!(again.local_socks_port, 11090);
    }
}
