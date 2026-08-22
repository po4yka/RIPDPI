use std::io;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::Instant;

use ripdpi_relay_mux::{RelayCapabilities, RelaySession, RelaySessionFactory};
use tokio::io::{AsyncRead, AsyncWrite};

use crate::telemetry::{ChainHopRole, ChainHopTelemetryState};

pub(crate) trait ChainAsyncIo: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> ChainAsyncIo for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

/// One hop in an ordered N-hop chain.
///
/// The same connector type is used at every position: `connect` opens a real
/// outbound socket (used only for the entry hop, index 0), while `connect_over`
/// layers the hop's protocol on top of the previous hop's stream and opens no
/// new OS socket. QUIC-only kinds (`Hysteria2`, `Tuic`) can serve only as the
/// entry hop — they have no `connect_over` — so the builder rejects them at any
/// later index.
#[derive(Clone)]
pub(crate) enum ChainHopConnector {
    VlessReality(ripdpi_vless::config::VlessRealityConfig),
    Masque(ripdpi_masque::config::MasqueConfig),
    Trojan(ripdpi_relay_tls_transports::TrojanClientConfig),
    AnyTls(ripdpi_relay_tls_transports::AnyTlsClientConfig),
    Shadowsocks(ripdpi_relay_tls_transports::ShadowsocksSessionFactory),
    ShadowTls(ripdpi_relay_tls_transports::ShadowTlsSessionFactory),
    Hysteria2(crate::protocols::Hysteria2SessionFactory),
    Tuic(crate::protocols::TuicSessionFactory),
}

impl ChainHopConnector {
    /// The `host:port` authority a *prior* hop must dial to reach this hop.
    ///
    /// Used to chain hop `i` over hop `i-1`: the previous hop's `connect_over`
    /// receives this hop's proxy target as the destination to open inside the
    /// tunnel. QUIC kinds have no upstream-proxy authority (they are entry-only)
    /// and return an error here, which the builder converts into a hard reject.
    pub(crate) fn proxy_target(&self) -> io::Result<String> {
        match self {
            Self::VlessReality(config) => Ok(format!("{}:{}", config.server, config.port)),
            Self::Masque(config) => masque_proxy_target(config),
            Self::Trojan(config) => Ok(ripdpi_relay_tls_transports::trojan_proxy_target(config)),
            Self::AnyTls(config) => Ok(ripdpi_relay_tls_transports::anytls_proxy_target(config)),
            Self::Shadowsocks(factory) => Ok(ripdpi_relay_tls_transports::shadowsocks_proxy_target(factory)),
            Self::ShadowTls(factory) => Ok(ripdpi_relay_tls_transports::shadowtls_proxy_target(factory)),
            Self::Hysteria2(_) | Self::Tuic(_) => Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "QUIC chain hops (Hysteria2/TUIC) can only be the entry hop; nothing may chain through them",
            )),
        }
    }

    /// Open the entry hop's single real outbound socket.
    ///
    /// `outbound_bind_ip` binds the socket to the protected physical interface —
    /// the relay data plane's protect-equivalent (see
    /// `.claude/rules/vpnservice-protect-invariant.md`). Each transport honors
    /// the bind through its own config (`outbound_bind_ip` on the client
    /// configs): VLESS Reality, Trojan, AnyTLS and ShadowTLS bind their carrier
    /// TCP socket before connect; TUIC binds its QUIC UDP carrier. The kinds
    /// that cannot honor a bind IP ([`reject_bind_for_kind`]: MASQUE,
    /// Hysteria2) **fail closed** — they return `Err` before any
    /// `connect`/`bind` runs rather than open an unbound, TUN-routed socket that
    /// the kernel would loop back into the VPN's own TUN device. Non-entry hops
    /// never call this (they tunnel via [`connect_over`](Self::connect_over));
    /// the builder also forbids `outbound_bind_ip` on any non-entry hop, so a
    /// later hop can never silently open an unbound, TUN-routed socket.
    ///
    /// cancel-safe: drops the partially-built stream on cancellation; no shared
    /// state is published before the returned `Box<dyn ChainAsyncIo>` is handed
    /// back, so a dropped future leaves only a closed fd.
    async fn connect(&self, outbound_bind_ip: Option<IpAddr>, target: &str) -> io::Result<Box<dyn ChainAsyncIo>> {
        match self {
            Self::VlessReality(config) => {
                let stream = match outbound_bind_ip {
                    Some(bind_ip) => {
                        ripdpi_vless::VlessRealityClient::connect_with_bind(config, bind_ip, target).await?
                    }
                    None => ripdpi_vless::VlessRealityClient::connect(config, target).await?,
                };
                Ok(Box::new(stream))
            }
            Self::Masque(config) => {
                reject_bind_for_kind(outbound_bind_ip, "MASQUE")?;
                let stream = ripdpi_masque::MasqueClient::connect(config, target).await?;
                Ok(Box::new(stream))
            }
            Self::Trojan(config) => {
                let stream = ripdpi_relay_tls_transports::connect_trojan_tcp(config, target).await?;
                Ok(Box::new(stream))
            }
            Self::AnyTls(config) => {
                let stream = ripdpi_relay_tls_transports::connect_anytls_tcp(config, target).await?;
                Ok(Box::new(stream))
            }
            Self::Shadowsocks(factory) => {
                let stream = ripdpi_relay_tls_transports::connect_shadowsocks_tcp(factory, target).await?;
                Ok(Box::new(stream))
            }
            Self::ShadowTls(factory) => {
                let stream = ripdpi_relay_tls_transports::connect_shadowtls_tcp(factory, target).await?;
                Ok(Box::new(stream))
            }
            Self::Hysteria2(factory) => {
                reject_bind_for_kind(outbound_bind_ip, "Hysteria2")?;
                let session = factory.create_session().await?;
                let stream = session.open_stream(target).await?;
                Ok(Box::new(stream))
            }
            Self::Tuic(factory) => {
                let session = factory.create_session().await?;
                let stream = session.open_stream(target).await?;
                Ok(Box::new(stream))
            }
        }
    }

    /// Layer this hop's protocol over the previous hop's `transport` stream.
    ///
    /// Opens no new OS socket: the bytes flow through `transport`, so no
    /// outbound-bind / socket-protect step applies here. QUIC kinds are not
    /// chainable and are rejected by the builder before reaching this path.
    ///
    /// cancel-safe: on cancellation the half-built handshake is dropped and the
    /// underlying `transport` (already protected at the entry hop) is closed; no
    /// state is published to the caller.
    async fn connect_over(&self, transport: Box<dyn ChainAsyncIo>, target: &str) -> io::Result<Box<dyn ChainAsyncIo>> {
        match self {
            Self::VlessReality(config) => {
                let stream = ripdpi_vless::VlessRealityClient::connect_over(config, transport, target).await?;
                Ok(Box::new(stream))
            }
            Self::Masque(config) => {
                let stream = ripdpi_masque::MasqueClient::connect_over(config, transport, target).await?;
                Ok(Box::new(stream))
            }
            Self::Trojan(config) => {
                let stream = ripdpi_relay_tls_transports::connect_trojan_tcp_over(config, transport, target).await?;
                Ok(Box::new(stream))
            }
            Self::AnyTls(config) => {
                let stream = ripdpi_relay_tls_transports::connect_anytls_tcp_over(config, transport, target).await?;
                Ok(Box::new(stream))
            }
            Self::Shadowsocks(factory) => {
                let stream =
                    ripdpi_relay_tls_transports::connect_shadowsocks_tcp_over(factory, transport, target).await?;
                Ok(Box::new(stream))
            }
            Self::ShadowTls(factory) => {
                let stream =
                    ripdpi_relay_tls_transports::connect_shadowtls_tcp_over(factory, transport, target).await?;
                Ok(Box::new(stream))
            }
            Self::Hysteria2(_) | Self::Tuic(_) => Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "QUIC chain hops (Hysteria2/TUIC) cannot tunnel over a prior hop",
            )),
        }
    }
}

fn reject_bind_for_kind(outbound_bind_ip: Option<IpAddr>, kind: &str) -> io::Result<()> {
    if outbound_bind_ip.is_some() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("chain {kind} entry does not support outbound bind IP"),
        ));
    }
    Ok(())
}

fn masque_proxy_target(config: &ripdpi_masque::config::MasqueConfig) -> io::Result<String> {
    if let Some(address) = config.proxy_socket_addr {
        return Ok(address.to_string());
    }
    let parsed = url::Url::parse(&config.url).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let host =
        parsed.host_str().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL is missing a host"))?;
    let port = parsed
        .port_or_known_default()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "MASQUE URL is missing a port"))?;
    Ok(format!("{host}:{port}"))
}

#[derive(Clone)]
pub(crate) struct ChainRelaySessionFactory {
    /// Ordered hop connectors, length in `[CHAIN_RELAY_MIN_HOPS, MAX]`.
    /// `hops[0]` is the entry; the last is the exit.
    pub(crate) hops: Vec<ChainHopConnector>,
    pub(crate) outbound_bind_ip: Option<IpAddr>,
    pub(crate) telemetry: ChainHopTelemetryState,
}

pub(crate) struct ChainRelaySession {
    hops: Vec<ChainHopConnector>,
    outbound_bind_ip: Option<IpAddr>,
    telemetry: ChainHopTelemetryState,
}

impl ChainRelaySession {
    /// Record a hop's connect outcome and return the latency for tracing.
    fn record(&self, index: usize, state: &str, latency_ms: Option<u64>) {
        self.telemetry.record(ChainHopRole::Hop(index), state, latency_ms);
    }
}

impl RelaySession for ChainRelaySession {
    type Stream = Box<dyn ChainAsyncIo>;
    type Datagram = ();
    type Error = io::Error;

    async fn open_stream(&self, target: &str) -> Result<Self::Stream, Self::Error> {
        // cancel-safe: each hop's connect/connect_over is itself cancel-safe and
        // the fold publishes nothing until the final stream is returned. A drop
        // mid-fold closes the partially-built tunnel (every prior hop's stream is
        // owned by `stream` and dropped with it) and leaves no orphaned task —
        // there is no detached `tokio::spawn` in this path.
        let last_index = self.hops.len() - 1;

        // Entry hop (index 0): the only hop that opens a real outbound
        // socket. Its destination is the next hop's proxy authority (for a
        // 2-hop chain that is the exit's authority).
        let entry_destination = if last_index == 0 { target.to_string() } else { self.hops[1].proxy_target()? };
        self.record(0, "connecting", None);
        let started = Instant::now();
        let mut stream = match self.hops[0].connect(self.outbound_bind_ip, &entry_destination).await {
            Ok(stream) => {
                self.record(0, "connected", Some(started.elapsed().as_millis() as u64));
                stream
            }
            Err(error) => {
                self.record(0, "failed", Some(started.elapsed().as_millis() as u64));
                return Err(error);
            }
        };

        // Intermediate + exit hops: each tunnels through the prior hop's
        // stream. Hop `i` dials hop `i+1`'s proxy authority, except the last
        // hop, which dials the caller's final `target`.
        for index in 1..self.hops.len() {
            let destination =
                if index == last_index { target.to_string() } else { self.hops[index + 1].proxy_target()? };
            self.record(index, "connecting", None);
            let started = Instant::now();
            stream = match self.hops[index].connect_over(stream, &destination).await {
                Ok(stream) => {
                    self.record(index, "connected", Some(started.elapsed().as_millis() as u64));
                    stream
                }
                Err(error) => {
                    self.record(index, "failed", Some(started.elapsed().as_millis() as u64));
                    return Err(error);
                }
            };
        }

        Ok(stream)
    }

    async fn open_datagram(&self) -> Result<Self::Datagram, Self::Error> {
        // cancel-safe: returns immediately with an error; no awaits, no state.
        Err(io::Error::new(io::ErrorKind::Unsupported, "chain relay does not support UDP ASSOCIATE"))
    }
}

impl RelaySessionFactory for ChainRelaySessionFactory {
    type Session = ChainRelaySession;
    type Error = io::Error;

    fn capabilities(&self) -> RelayCapabilities {
        RelayCapabilities { tcp: true, udp: false, reusable: false }
    }

    async fn create_session(&self) -> Result<Arc<Self::Session>, Self::Error> {
        let hops = self.hops.clone();
        let outbound_bind_ip = self.outbound_bind_ip;
        let telemetry = self.telemetry.clone();
        // cancel-safe: pure construction, no awaits with side effects.
        Ok(Arc::new(ChainRelaySession { hops, outbound_bind_ip, telemetry }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// VpnService.protect() invariant (`.claude/rules/vpnservice-protect-invariant.md`):
    /// the chain entry kinds that cannot bind to the protected interface must
    /// fail closed. `connect(Some(bind_ip), ..)` routes every such kind through
    /// `reject_bind_for_kind` *before* any `connect`/`bind` runs, so a bind IP it
    /// cannot honor never opens an unbound, TUN-routed socket. This pins that the
    /// guard rejects a bind IP for each of those kinds (MASQUE, Hysteria2) and
    /// accepts the no-bind path.
    #[test]
    fn reject_bind_for_kind_fails_closed_on_bind_ip() {
        let bind_ip = Some(IpAddr::from([10, 0, 0, 1]));

        // Every kind whose `connect` arm calls `reject_bind_for_kind` must
        // surface an error the moment a bind IP is supplied.
        for kind in ["MASQUE", "Hysteria2"] {
            let err = reject_bind_for_kind(bind_ip, kind)
                .expect_err("a bind IP must be rejected so the hop never opens an unbound socket");
            assert_eq!(err.kind(), io::ErrorKind::InvalidInput, "kind {kind} must reject the bind IP");
        }

        // The no-bind path is the only accepted shape for these kinds.
        for kind in ["MASQUE", "Hysteria2"] {
            reject_bind_for_kind(None, kind).expect("a missing bind IP must be accepted");
        }
    }
}
