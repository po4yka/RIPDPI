use std::fmt;

use super::{TcpFakeOrdering, TcpFlagOverrides, TcpIpv6ExtensionPayload};
use crate::{TcpChainStep, TcpChainStepKind};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcpStepPayloadInvariantError {
    kind: TcpChainStepKind,
    field: &'static str,
}

impl TcpStepPayloadInvariantError {
    const fn new(kind: TcpChainStepKind, field: &'static str) -> Self {
        Self { kind, field }
    }

    pub const fn kind(&self) -> TcpChainStepKind {
        self.kind
    }

    pub const fn field(&self) -> &'static str {
        self.field
    }
}

impl fmt::Display for TcpStepPayloadInvariantError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{:?} must not declare {} payload fields", self.kind, self.field)
    }
}

impl std::error::Error for TcpStepPayloadInvariantError {}

impl TcpChainStep {
    pub fn validate_payload_family(&self) -> Result<(), TcpStepPayloadInvariantError> {
        if !self.kind.supports_fake_ordering() && self.fake_ordering() != TcpFakeOrdering::before_each_duplicate() {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "fake ordering"));
        }
        if !self.kind.supports_fake_tcp_flags() && self.fake_flag_overrides() != TcpFlagOverrides::disabled() {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "fake TCP flags"));
        }
        if !self.kind.supports_orig_tcp_flags() && self.original_flag_overrides() != TcpFlagOverrides::disabled() {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "original TCP flags"));
        }
        if self.kind != TcpChainStepKind::SeqOverlap && self.seq_overlap_storage_active() {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "sequence-overlap"));
        }
        if self.kind != TcpChainStepKind::HostFake && self.hostfake_storage_active() {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "hostfake"));
        }
        if self.kind != TcpChainStepKind::TlsRandRec
            && self.kind != TcpChainStepKind::IpFrag2
            && self.fragment_storage_active()
        {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "fragmentation"));
        }
        if self.kind != TcpChainStepKind::IpFrag2 && self.ipv6_extension_payload() != TcpIpv6ExtensionPayload::default()
        {
            return Err(TcpStepPayloadInvariantError::new(self.kind, "IPv6 fragmentation"));
        }
        Ok(())
    }
}
