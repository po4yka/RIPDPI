#![forbid(unsafe_code)]

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RuntimeCapability {
    TtlWrite,
    RawTcpFakeSend,
    RawUdpFragmentation,
    ReplacementSocket,
    RootHelperAvailable,
    VpnProtect,
    VpnProtectCallback,
    VpnMode,
    TcpWindowClamp,
    NetworkBinding,
}

impl RuntimeCapability {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::TtlWrite => "ttl_write",
            Self::RawTcpFakeSend => "raw_tcp_fake_send",
            Self::RawUdpFragmentation => "raw_udp_fragmentation",
            Self::ReplacementSocket => "replacement_socket",
            Self::RootHelperAvailable => "root_helper_available",
            Self::VpnProtect => "vpn_protect",
            Self::VpnProtectCallback => "vpn_protect_callback",
            Self::VpnMode => "vpn_mode",
            Self::TcpWindowClamp => "tcp_window_clamp",
            Self::NetworkBinding => "network_binding",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CapabilityUnavailable {
    NotProbed,
    Unsupported,
    PermissionDenied,
    MissingRootHelper,
}

#[derive(Debug, Clone)]
pub enum CapabilityOutcome<T> {
    Available(T),
    Unavailable { capability: RuntimeCapability, reason: CapabilityUnavailable },
    ProbeFailed { capability: RuntimeCapability, error: String },
}

impl<T> CapabilityOutcome<T> {
    pub fn is_available(&self) -> bool {
        matches!(self, Self::Available(_))
    }

    pub fn capability(&self) -> Option<RuntimeCapability> {
        match self {
            Self::Available(_) => None,
            Self::Unavailable { capability, .. } | Self::ProbeFailed { capability, .. } => Some(*capability),
        }
    }

    pub fn take(self) -> Option<T> {
        match self {
            Self::Available(value) => Some(value),
            Self::Unavailable { .. } | Self::ProbeFailed { .. } => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};

    #[test]
    fn runtime_capability_as_str_stable() {
        assert_eq!(RuntimeCapability::TtlWrite.as_str(), "ttl_write");
        assert_eq!(RuntimeCapability::RawTcpFakeSend.as_str(), "raw_tcp_fake_send");
        assert_eq!(RuntimeCapability::RawUdpFragmentation.as_str(), "raw_udp_fragmentation");
        assert_eq!(RuntimeCapability::ReplacementSocket.as_str(), "replacement_socket");
        assert_eq!(RuntimeCapability::RootHelperAvailable.as_str(), "root_helper_available");
        assert_eq!(RuntimeCapability::VpnProtect.as_str(), "vpn_protect");
        assert_eq!(RuntimeCapability::VpnProtectCallback.as_str(), "vpn_protect_callback");
        assert_eq!(RuntimeCapability::VpnMode.as_str(), "vpn_mode");
        assert_eq!(RuntimeCapability::TcpWindowClamp.as_str(), "tcp_window_clamp");
        assert_eq!(RuntimeCapability::NetworkBinding.as_str(), "network_binding");
    }

    #[test]
    fn zapret2_strategy_capability_requirements_are_named() {
        let required = [
            RuntimeCapability::ReplacementSocket,
            RuntimeCapability::RawTcpFakeSend,
            RuntimeCapability::RawUdpFragmentation,
            RuntimeCapability::VpnProtect,
            RuntimeCapability::VpnMode,
            RuntimeCapability::TcpWindowClamp,
        ];

        let names: Vec<&str> = required.iter().map(|capability| capability.as_str()).collect();

        assert_eq!(
            names,
            [
                "replacement_socket",
                "raw_tcp_fake_send",
                "raw_udp_fragmentation",
                "vpn_protect",
                "vpn_mode",
                "tcp_window_clamp",
            ],
        );
    }

    #[test]
    fn capability_outcome_is_available() {
        let avail: CapabilityOutcome<bool> = CapabilityOutcome::Available(true);
        assert!(avail.is_available());

        let unavail: CapabilityOutcome<bool> = CapabilityOutcome::Unavailable {
            capability: RuntimeCapability::TtlWrite,
            reason: CapabilityUnavailable::Unsupported,
        };
        assert!(!unavail.is_available());

        let failed: CapabilityOutcome<bool> = CapabilityOutcome::ProbeFailed {
            capability: RuntimeCapability::RawTcpFakeSend,
            error: "test error".to_owned(),
        };
        assert!(!failed.is_available());
    }

    #[test]
    fn capability_outcome_take() {
        let avail: CapabilityOutcome<u32> = CapabilityOutcome::Available(42);
        assert_eq!(avail.take(), Some(42));

        let unavail: CapabilityOutcome<u32> = CapabilityOutcome::Unavailable {
            capability: RuntimeCapability::NetworkBinding,
            reason: CapabilityUnavailable::PermissionDenied,
        };
        assert_eq!(unavail.take(), None);

        let failed: CapabilityOutcome<u32> = CapabilityOutcome::ProbeFailed {
            capability: RuntimeCapability::RootHelperAvailable,
            error: "oops".to_owned(),
        };
        assert_eq!(failed.take(), None);
    }

    #[test]
    fn capability_outcome_capability_accessor() {
        let avail: CapabilityOutcome<bool> = CapabilityOutcome::Available(true);
        assert_eq!(avail.capability(), None);

        let unavail: CapabilityOutcome<bool> = CapabilityOutcome::Unavailable {
            capability: RuntimeCapability::VpnProtectCallback,
            reason: CapabilityUnavailable::NotProbed,
        };
        assert_eq!(unavail.capability(), Some(RuntimeCapability::VpnProtectCallback));

        let failed: CapabilityOutcome<bool> = CapabilityOutcome::ProbeFailed {
            capability: RuntimeCapability::ReplacementSocket,
            error: "err".to_owned(),
        };
        assert_eq!(failed.capability(), Some(RuntimeCapability::ReplacementSocket));
    }
}
