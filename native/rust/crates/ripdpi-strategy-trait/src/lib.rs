//! Shared strategy abstraction for RIPDPI desync backends.

use std::collections::HashMap;

/// Stable identifier for a transport flow.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct FlowId(pub u64);

/// Packet direction relative to the protected app.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum FlowDirection {
    /// Packet is leaving the protected app.
    #[default]
    Outbound,
    /// Packet is entering the protected app.
    Inbound,
}

/// Coarse runtime mode required by a strategy.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum CapabilityTier {
    /// Plain user-space proxy operations.
    #[default]
    Tier0,
    /// Socket option or packet send operation that may fail per device.
    Tier1,
    /// Root-helper-backed operation.
    Tier2,
    /// VPN/TUN packet-path operation.
    Tier3,
}

/// Runtime capability bit used by strategy descriptors and context checks.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum RuntimeCapability {
    /// Outbound TTL writes are available.
    TtlWrite,
    /// Raw TCP fake-packet sends are available.
    RawTcpFakeSend,
    /// Raw UDP fragmentation sends are available.
    RawUdpFragmentation,
    /// TCP replacement socket operations are available.
    ReplacementSocket,
    /// VPN socket protection is available.
    VpnProtect,
    /// Active VPN/TUN packet path is available.
    VpnMode,
    /// TCP window clamp socket option is available.
    TcpWindowClamp,
}

/// Read-only capability snapshot exposed to strategies.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Capabilities {
    /// Highest runtime tier available for the current execution path.
    pub tier: CapabilityTier,
    /// Fine-grained capabilities available for the current execution path.
    pub available: Vec<RuntimeCapability>,
}

impl Capabilities {
    /// Returns whether a fine-grained capability is available.
    pub fn has(&self, capability: RuntimeCapability) -> bool {
        self.available.contains(&capability)
    }
}

/// Per-connection state shared across strategy calls.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ConnectionState {
    /// Number of packets observed for this strategy flow.
    pub packet_count: u64,
}

/// HTTP request or response dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct HttpDissect {
    /// Host header value when one was present.
    pub host: Option<String>,
    /// True when the payload is a request rather than a reply.
    pub is_request: bool,
}

/// TLS ClientHello or ServerHello dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct TlsDissect {
    /// SNI host value when one was present.
    pub sni: Option<String>,
    /// True when the payload is a ClientHello.
    pub is_client_hello: bool,
}

/// DTLS ClientHello or ServerHello dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DtlsDissect {
    /// True when the payload is a ClientHello.
    pub is_client_hello: bool,
}

/// QUIC Initial dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct QuicDissect {
    /// QUIC version encoded in the long header.
    pub version: Option<u32>,
}

/// WireGuard message dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct WireGuardDissect {
    /// WireGuard message type, when recognized.
    pub message_type: Option<u32>,
}

/// DHT packet dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DhtDissect;

/// Discord IP discovery packet dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DiscordDissect;

/// STUN packet dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct StunDissect;

/// XMPP stream packet dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct XmppDissect;

/// DNS query or response dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DnsDissect {
    /// True when the packet is a DNS query.
    pub is_query: bool,
}

/// MTProto packet dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MtprotoDissect;

/// BitTorrent handshake dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct BitTorrentDissect;

/// uTP BitTorrent handshake dissection metadata.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct UtpBitTorrentDissect;

/// Layer-7 protocol classification aligned with zapret2 `t_l7proto`.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub enum L7Protocol {
    /// Wildcard selector for any protocol.
    Any,
    /// Protocol was not recognized.
    #[default]
    Unknown,
    /// Protocol was recognized but intentionally not narrowed.
    Known,
    /// HTTP request or response.
    Http(HttpDissect),
    /// TLS handshake or record.
    Tls(TlsDissect),
    /// DTLS handshake or record.
    Dtls(DtlsDissect),
    /// QUIC Initial packet.
    Quic(QuicDissect),
    /// WireGuard packet.
    WireGuard(WireGuardDissect),
    /// DHT packet.
    Dht(DhtDissect),
    /// Discord IP discovery packet.
    Discord(DiscordDissect),
    /// STUN packet.
    Stun(StunDissect),
    /// XMPP stream packet.
    Xmpp(XmppDissect),
    /// DNS query or response.
    Dns(DnsDissect),
    /// MTProto packet.
    Mtproto(MtprotoDissect),
    /// BitTorrent handshake.
    BitTorrent(BitTorrentDissect),
    /// uTP BitTorrent handshake.
    UtpBitTorrent(UtpBitTorrentDissect),
}

/// Named payload marker aligned with zapret2 `t_marker`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum MarkerName {
    /// Absolute payload offset.
    Absolute,
    /// Start of HTTP Host or TLS SNI host.
    Host,
    /// End of HTTP Host or TLS SNI host.
    HostEnd,
    /// Start of the second-level domain.
    HostSld,
    /// Middle of the second-level domain.
    HostMidSld,
    /// End of the second-level domain.
    HostEndSld,
    /// HTTP method offset.
    HttpMethod,
    /// TLS extension-length field offset.
    ExtLen,
    /// TLS SNI extension offset.
    SniExt,
    /// Generic payload data offset used by config strategies.
    Data,
    /// End of payload.
    End,
}

/// Parsed packet and marker tree provided to strategies.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Dissect {
    /// Classified layer-7 protocol.
    pub proto: L7Protocol,
    /// Source transport port.
    pub src_port: u16,
    /// Destination transport port.
    pub dst_port: u16,
    /// True when the packet came from an IPv6 flow.
    pub is_ipv6: bool,
    /// Resolved marker offsets in the payload.
    pub markers: HashMap<MarkerName, usize>,
}

/// Strategy execution context passed to all backends.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StrategyContext<'a> {
    /// Parsed protocol and marker data.
    pub dissect: &'a Dissect,
    /// Per-connection state snapshot.
    pub conn: &'a ConnectionState,
    /// Runtime capabilities available to the strategy.
    pub caps: &'a Capabilities,
    /// Stable flow identifier.
    pub flow_id: FlowId,
    /// Raw packet or stream payload.
    pub payload: &'a [u8],
    /// Packet direction relative to the protected app.
    pub direction: FlowDirection,
}

/// Strategy action planned by a backend.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DesyncAction {
    /// Write bytes to the outbound stream.
    Write(Vec<u8>),
    /// Send raw bytes through a VPN-protected socket.
    RawSend(Vec<u8>),
    /// Send a fake TCP payload using the runtime's fake-packet path.
    WriteFake {
        /// Optional TTL to use for the fake packet.
        ttl: Option<u8>,
        /// Optional zapret-compatible SNI generation mode.
        sni_mode: Option<String>,
        /// Optional payload source path supplied by the script/config.
        payload_file: Option<String>,
    },
    /// Send a TCP urgent/OOB byte after an optional prefix.
    WriteUrgent {
        /// Bytes written before the urgent byte.
        prefix: Vec<u8>,
        /// TCP urgent byte.
        urgent_byte: u8,
    },
    /// Send a fake TCP RST packet.
    SendFakeRst {
        /// Optional TTL to use for the fake RST packet.
        ttl: Option<u8>,
    },
    /// Falsify the UDP length field by the given delta.
    UdpLen {
        /// Signed length delta.
        delta: i16,
    },
    /// Split a payload at an offset.
    Split { offset: usize, disorder: bool },
    /// Set the socket TTL.
    SetTtl(u8),
    /// Restore the default socket TTL.
    RestoreDefaultTtl,
    /// Set the TCP window clamp.
    SetWindowClamp(u32),
}

/// Mutable desync plan populated by a strategy.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DesyncPlan {
    /// Planned packet or socket actions.
    pub actions: Vec<DesyncAction>,
    /// Verdict requested by the strategy.
    pub verdict: StrategyVerdict,
}

/// Strategy-level verdict for the current flow.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum StrategyVerdict {
    /// Apply the actions collected in the plan.
    #[default]
    Apply,
    /// Let the connection proceed without desync.
    FallbackPlain,
    /// Drop the packet or connection.
    Drop,
}

/// Public metadata advertised by a strategy.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct StrategyDescriptor {
    /// Stable strategy identifier.
    pub id: String,
    /// Human-readable strategy label.
    pub label: String,
    /// Supported layer-7 protocol families.
    pub supported_protocols: Vec<L7Protocol>,
    /// Required coarse capability tier.
    pub required_tier: CapabilityTier,
    /// Required fine-grained capabilities.
    pub required_capabilities: Vec<RuntimeCapability>,
}

/// Error returned by strategy matching or planning.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StrategyError {
    /// Strategy configuration was invalid.
    InvalidConfig(String),
    /// Strategy script failed to load.
    ScriptLoad(String),
    /// Strategy script returned a Lua type error.
    LuaTypeError(String),
    /// Strategy execution failed.
    Execution(String),
    /// Required runtime capability was not available.
    CapabilityUnavailable(RuntimeCapability),
}

/// Common interface implemented by Rust-native, config, and Lua strategies.
pub trait DesyncStrategy: Send + Sync {
    /// Returns the stable strategy identifier.
    fn id(&self) -> &str;

    /// Returns whether this strategy applies to the context.
    fn matches(&self, ctx: &StrategyContext<'_>) -> bool;

    /// Appends desync actions to the supplied plan.
    fn plan(&self, ctx: &StrategyContext<'_>, plan: &mut DesyncPlan) -> Result<(), StrategyError>;

    /// Returns public strategy metadata.
    fn describe(&self) -> StrategyDescriptor;
}

/// Link-time factory for stateless strategy implementations.
///
/// Configured strategies that need per-profile parameters can still be
/// materialized through their config loader. This registry is for stable,
/// zero-argument defaults that can advertise themselves without central wiring.
#[derive(Clone, Copy)]
pub struct StrategyFactory {
    /// Stable strategy identifier.
    pub id: &'static str,
    /// Builds a fresh strategy instance.
    pub make: fn() -> Box<dyn DesyncStrategy>,
}

/// Link-time descriptor for strategies that cannot be built without runtime
/// configuration, but should still be visible to diagnostics and inventory
/// checks.
#[derive(Clone, Copy)]
pub struct StrategyDescriptorRegistration {
    /// Stable strategy identifier.
    pub id: &'static str,
    /// Builds public metadata for the strategy family.
    pub describe: fn() -> StrategyDescriptor,
}

/// Factories contributed by linked strategy crates.
#[linkme::distributed_slice]
pub static STRATEGY_FACTORIES: [StrategyFactory] = [..];

/// Descriptor-only registrations contributed by linked strategy crates.
#[linkme::distributed_slice]
pub static STRATEGY_DESCRIPTOR_REGISTRATIONS: [StrategyDescriptorRegistration] = [..];
