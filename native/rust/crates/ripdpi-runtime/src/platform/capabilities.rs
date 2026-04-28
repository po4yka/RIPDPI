use std::io;
use std::net::TcpStream;

use socket2::{Domain, Protocol, Socket, Type};

/// A discrete runtime capability that the engine can probe and report.
///
/// Variants map one-to-one to testable platform features; the string ids
/// returned by `as_str()` are stable for telemetry / serialization.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RuntimeCapability {
    /// Ability to set IP TTL on outbound TCP packets via raw sockets.
    TtlWrite,
    /// Ability to send fake TCP segments via a raw socket (non-root path).
    RawTcpFakeSend,
    /// Ability to send IP-fragmented UDP via a raw socket (non-root path).
    RawUdpFragmentation,
    /// Ability to create a replacement (protected) socket for the VPN path.
    ReplacementSocket,
    /// Root-helper process is reachable and authenticated.
    RootHelperAvailable,
    /// Android VPN protect callback is wired up and callable.
    VpnProtectCallback,
    /// Socket can be bound to a specific network interface.
    NetworkBinding,
}

impl RuntimeCapability {
    /// Returns a stable, lowercase-snake-case identifier suitable for
    /// telemetry keys and JSON field names.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::TtlWrite => "ttl_write",
            Self::RawTcpFakeSend => "raw_tcp_fake_send",
            Self::RawUdpFragmentation => "raw_udp_fragmentation",
            Self::ReplacementSocket => "replacement_socket",
            Self::RootHelperAvailable => "root_helper_available",
            Self::VpnProtectCallback => "vpn_protect_callback",
            Self::NetworkBinding => "network_binding",
        }
    }
}

/// Reason a capability is definitively unavailable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CapabilityUnavailable {
    /// The capability has not been probed yet.
    NotProbed,
    /// The platform does not support this capability at all.
    Unsupported,
    /// The process lacks the required permissions.
    PermissionDenied,
    /// The root-helper binary is missing or unreachable.
    MissingRootHelper,
}

/// Result of probing a single runtime capability.
///
/// `T` is the payload type when the capability is `Available` — for boolean
/// capabilities this is `bool`, for richer probes it may be a struct.
///
/// `io::Error` is intentionally not stored directly because it does not impl
/// `Clone` or `PartialEq`; the human-readable message is captured instead.
#[derive(Debug, Clone)]
pub enum CapabilityOutcome<T> {
    /// The capability is present and usable; `T` holds the probed state.
    Available(T),
    /// The capability is definitively unavailable for the given reason.
    Unavailable { capability: RuntimeCapability, reason: CapabilityUnavailable },
    /// Probing itself failed with a transient or unexpected error.
    ProbeFailed {
        capability: RuntimeCapability,
        /// Human-readable error message (not the raw `io::Error`).
        error: String,
    },
}

impl<T> CapabilityOutcome<T> {
    /// Returns `true` only when the capability is `Available`.
    pub fn is_available(&self) -> bool {
        matches!(self, Self::Available(_))
    }

    /// Returns the `RuntimeCapability` tag for `Unavailable` and `ProbeFailed`
    /// variants; returns `None` for `Available` (the tag is not stored there).
    pub fn capability(&self) -> Option<RuntimeCapability> {
        match self {
            Self::Available(_) => None,
            Self::Unavailable { capability, .. } => Some(*capability),
            Self::ProbeFailed { capability, .. } => Some(*capability),
        }
    }

    /// Consumes the outcome and returns `Some(T)` if `Available`, else `None`.
    pub fn take(self) -> Option<T> {
        match self {
            Self::Available(v) => Some(v),
            _ => None,
        }
    }
}

pub fn detect_default_ttl() -> io::Result<u8> {
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    let ttl = socket.ttl_v4()?;
    u8::try_from(ttl).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket ttl exceeds u8"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn try_set_stream_ttl_with_outcome(stream: &TcpStream, ttl: u8) -> CapabilityOutcome<()> {
    super::linux::try_set_stream_ttl_with_outcome(stream, ttl)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn try_set_stream_ttl_with_outcome(_stream: &TcpStream, _ttl: u8) -> CapabilityOutcome<()> {
    CapabilityOutcome::Unavailable {
        capability: RuntimeCapability::TtlWrite,
        reason: CapabilityUnavailable::Unsupported,
    }
}
