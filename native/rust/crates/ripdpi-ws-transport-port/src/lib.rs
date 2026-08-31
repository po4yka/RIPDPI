#![forbid(unsafe_code)]

//! Layer-2 contracts shared by Telegram WebSocket bootstrap, diagnostics,
//! runtime orchestration, and the concrete tunnel implementation.

mod dc;
mod worker_route;

use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::time::Duration;

pub use dc::{
    TELEGRAM_DC_IPV4_TABLE_LAST_REVIEWED, TELEGRAM_DC_IPV4_TABLE_SOURCE, TelegramDc, TelegramDcClass, dc_from_ip,
    dc_from_ipv6, is_telegram_ip, ws_host, ws_url,
};
pub use worker_route::{CloudflareWorkerRoute, WorkerBearer};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MtprotoSeedClassification {
    ValidatedMtproto { dc: TelegramDc },
    NotMtproto,
    UnmappableDc { raw_dc: i32, dc: Option<TelegramDc> },
}

/// Configuration needed by a concrete Telegram WebSocket transport.
pub struct WsTunnelConfig {
    /// Unix socket path for Android VPN socket protection. `None` when not
    /// running in VPN mode.
    pub protect_path: Option<String>,
    /// Optional pre-resolved Telegram WS endpoint, usually supplied by the
    /// runtime through encrypted DNS.
    pub resolved_addr: Option<SocketAddr>,
    /// Optional upper bound for establishing the outer WS connection.
    pub connect_timeout: Option<Duration>,
    /// Optional cover domain for the TLS ClientHello.
    ///
    /// The real Telegram gateway is replaced by this name. Because the peer
    /// certificate cannot authenticate the cover name, this mode disables
    /// normal certificate validation and therefore requires
    /// [`WsTunnelConfig::allow_insecure_sni`] to be `true`.
    pub fake_sni: Option<String>,
    /// Explicit operator acknowledgement that fake-SNI mode disables normal
    /// TLS certificate verification. A configured `fake_sni` must be rejected
    /// unless this flag is `true`.
    pub allow_insecure_sni: bool,
    /// Optional operator-owned Cloudflare Worker route.
    ///
    /// The outer TLS/WebSocket endpoint becomes the Worker while the canonical
    /// Telegram gateway is carried in `X-Ripdpi-Upstream`. Implementations must
    /// reject a Worker route combined with `fake_sni` so verified Worker TLS is
    /// never silently weakened.
    pub worker_route: Option<CloudflareWorkerRoute>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WsTunnelDecision {
    Tunnel(TelegramDc),
    Passthrough,
}

#[must_use]
pub fn classify_target(ip: IpAddr) -> WsTunnelDecision {
    let dc = match ip {
        IpAddr::V4(ip) => dc_from_ip(ip),
        IpAddr::V6(ip) => dc_from_ipv6(ip),
    };
    dc.map_or(WsTunnelDecision::Passthrough, WsTunnelDecision::Tunnel)
}

/// Object-safe boundary between runtime orchestration and the L7 WS tunnel.
///
/// Implementations are synchronous because the underlying relay owns blocking
/// sockets and dedicated worker threads for the whole connection lifetime.
pub trait WsTransport: Send + Sync {
    /// Classify the initial MTProto bytes without transferring socket ownership.
    fn classify_mtproto_seed(&self, seed: &[u8]) -> MtprotoSeedClassification;

    /// Own and relay `client` until the connection finishes.
    ///
    /// The call must not detach the socket or its relay work. It returns only
    /// after the relay has finished (successfully or with an error), allowing
    /// runtime worker cleanup to treat return as the connection-lifetime
    /// boundary. `seed_request` starts with the validated 64-byte MTProto
    /// obfuscated2 init and may include bytes to forward before draining the
    /// client socket.
    fn relay(
        &self,
        client: TcpStream,
        dc: TelegramDc,
        seed_request: Vec<u8>,
        config: &WsTunnelConfig,
    ) -> io::Result<()>;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_object_safe(_: &dyn WsTransport) {}

    struct TestTransport;

    impl WsTransport for TestTransport {
        fn classify_mtproto_seed(&self, _seed: &[u8]) -> MtprotoSeedClassification {
            MtprotoSeedClassification::NotMtproto
        }

        fn relay(
            &self,
            _client: TcpStream,
            _dc: TelegramDc,
            _seed_request: Vec<u8>,
            _config: &WsTunnelConfig,
        ) -> io::Result<()> {
            Ok(())
        }
    }

    #[test]
    fn ws_transport_is_object_safe() {
        assert_object_safe(&TestTransport);
    }
}
