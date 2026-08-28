//! UID-based flow admission gate — closes the `SO_BINDTODEVICE` escape (kernel 5.7+).
//!
//! On Linux kernel 5.7+ (Android 12+/API 31+) `SO_BINDTODEVICE` no longer requires
//! privilege, so any app can bind a socket directly to `tun0` and bypass Android's
//! per-app split-tunnel routing. tun2socks reads packets off the TUN device with no
//! UID attribution, so that bypass is invisible to the routing layer. This module is
//! the enforcement core: a UID is resolved for each *new* flow (off the hot path, via
//! a [`FlowUidSource`] the JNI layer implements) and checked against the split-tunnel
//! allowlist/denylist before a SOCKS session is opened.
//!
//! ## Scope of this module
//!
//! The **pure, unit-testable decision** ([`UidFlowPolicy::evaluate`] /
//! [`UidFlowPolicy::admit`]) plus the [`FlowUidSource`] port. The live data path
//! consults it at the smoltcp admission seams: TCP before opening a SOCKS session,
//! and UDP before creating an association. Android arms the policy only on API 31+
//! and resolves `getConnectionOwnerUid` asynchronously through the JNI worker.
//!
//! ## Privacy
//!
//! Per `.claude/rules/network-fingerprint-privacy.md`, raw UID / IP must never be
//! logged. This module logs nothing and [`Verdict`] carries no PII.

use std::collections::HashSet;
use std::net::SocketAddr;

/// IANA protocol number for TCP, matching `PROTO_TCP` at the admission seam.
pub const PROTO_TCP: u8 = 6;
/// IANA protocol number for UDP, matching `PROTO_UDP` at the forwarding seam.
pub const PROTO_UDP: u8 = 17;

/// The admission decision for a new flow.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    /// UID resolution is still running off the data-plane thread. TCP admission
    /// remains parked; UDP retains the current datagram in a bounded queue and
    /// retries admission off the packet-receive path.
    Pending,
    /// Forward the flow to the SOCKS proxy as usual.
    Allow,
    /// Unauthorized TCP (or non-UDP) flow: abort the smoltcp socket (send RST) and
    /// never open a SOCKS session.
    ResetTcp,
    /// Unauthorized UDP datagram: drop it; the association is never created.
    DropUdp,
}

impl Verdict {
    /// The connection-terminating verdict for `protocol`: UDP drops, everything
    /// else (TCP and other L4) resets.
    #[must_use]
    fn deny_for(protocol: u8) -> Self {
        if protocol == PROTO_UDP { Self::DropUdp } else { Self::ResetTcp }
    }
}

/// Resolves the owning UID for a flow. The JNI layer implements this via
/// `ConnectivityManager.getConnectionOwnerUid(protocol, local, localPort, remote,
/// remotePort)` (API 29+, no root). Mirrors
/// `ripdpi_flow_app_attribution::AppUidResolver`: this crate never calls it on the
/// per-packet hot path — resolution is cached / driven off-path.
pub trait FlowUidSource: Send + Sync {
    /// The owning UID, or `None` if unattributable (shared/unknown UID, multiple
    /// owners, or lookup failure). `None` is handled per
    /// [`UidFlowPolicy::admit`]'s unresolved policy.
    fn uid_for(&self, protocol: u8, local: SocketAddr, remote: SocketAddr) -> UidLookup;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UidLookup {
    Missing,
    Pending,
    Resolved(Option<u32>),
}

/// Process-local 5-tuple cache populated by the Android JNI attribution worker.
pub struct CachedFlowUidSource;

impl FlowUidSource for CachedFlowUidSource {
    fn uid_for(&self, protocol: u8, local: SocketAddr, remote: SocketAddr) -> UidLookup {
        match ripdpi_flow_app_attribution::lookup_flow_uid(protocol, local, remote) {
            ripdpi_flow_app_attribution::FlowUidLookup::Missing => UidLookup::Missing,
            ripdpi_flow_app_attribution::FlowUidLookup::Pending => UidLookup::Pending,
            ripdpi_flow_app_attribution::FlowUidLookup::Resolved(uid) => UidLookup::Resolved(uid),
        }
    }
}

#[derive(Debug, Clone, Copy, Default)]
enum PolicyMode {
    #[default]
    Disarmed,
    Allowlist,
    Denylist,
}

/// Split-tunnel UID admission policy: an allowlist or denylist of UIDs, an arm
/// mode, and how to treat unattributable flows.
///
/// A **disarmed** policy (the [`Default`]) allows every flow — the gate must never be
/// the component that breaks traffic on an unverified path, so it is inert until the
/// device layer arms it. Arm only on kernel >= 5.7 (Android 12+/API 31+); below that
/// the escape does not exist.
///
/// When **enforcing**, an unresolved UID is blocked by default (`block_unresolved`,
/// the fail-closed posture this epic mandates); the knob exists because
/// `getConnectionOwnerUid` can transiently fail and a deployment may prefer to let
/// such flows through.
#[derive(Debug, Clone, Default)]
pub struct UidFlowPolicy {
    uids: HashSet<u32>,
    mode: PolicyMode,
    block_unresolved: bool,
}

impl UidFlowPolicy {
    /// A disarmed policy: every flow is [`Verdict::Allow`]ed. Construction default.
    #[must_use]
    pub fn disarmed() -> Self {
        Self::default()
    }

    /// An enforcing policy gating on `allowed_uids`, fail-closed on unattributable
    /// flows. Arm only on kernel >= 5.7.
    #[must_use]
    pub fn enforcing(allowed_uids: HashSet<u32>) -> Self {
        Self { uids: allowed_uids, mode: PolicyMode::Allowlist, block_unresolved: true }
    }

    /// An enforcing denylist policy used by Android's `addDisallowedApplication`
    /// routing shape.
    #[must_use]
    pub fn denying(denied_uids: HashSet<u32>) -> Self {
        Self { uids: denied_uids, mode: PolicyMode::Denylist, block_unresolved: true }
    }

    /// Consume `self` and let unattributable flows (resolver returns `None`) pass.
    /// Use when `getConnectionOwnerUid` reliability is a bigger risk than the escape.
    #[must_use]
    pub fn allowing_unresolved(mut self) -> Self {
        self.block_unresolved = false;
        self
    }

    /// Whether this policy actively gates flows.
    #[must_use]
    pub fn is_enforcing(&self) -> bool {
        !matches!(self.mode, PolicyMode::Disarmed)
    }

    /// Pure decision for an already-resolved `uid`. Disarmed => always
    /// [`Verdict::Allow`]. Infallible and side-effect-free — the unit-test surface.
    #[must_use]
    pub fn evaluate(&self, uid: u32, protocol: u8) -> Verdict {
        let allowed = match self.mode {
            PolicyMode::Disarmed => true,
            PolicyMode::Allowlist => self.uids.contains(&uid),
            PolicyMode::Denylist => !self.uids.contains(&uid),
        };
        if allowed { Verdict::Allow } else { Verdict::deny_for(protocol) }
    }

    /// Resolve the flow's UID via `source`, then [`evaluate`](Self::evaluate). A
    /// disarmed policy never calls `source`. An unresolved UID yields the
    /// fail-closed deny verdict unless [`allowing_unresolved`](Self::allowing_unresolved)
    /// was set.
    #[must_use]
    pub fn admit(&self, source: &dyn FlowUidSource, protocol: u8, local: SocketAddr, remote: SocketAddr) -> Verdict {
        if !self.is_enforcing() {
            return Verdict::Allow;
        }
        match source.uid_for(protocol, local, remote) {
            UidLookup::Missing | UidLookup::Pending => Verdict::Pending,
            UidLookup::Resolved(Some(uid)) => self.evaluate(uid, protocol),
            UidLookup::Resolved(None) if self.block_unresolved => Verdict::deny_for(protocol),
            UidLookup::Resolved(None) => Verdict::Allow,
        }
    }

    /// Admit a captured packet against its original registration generation.
    pub(crate) fn admit_registration(
        &self,
        registration_id: &ripdpi_flow_app_attribution::FlowRegistrationId,
    ) -> Verdict {
        if !self.is_enforcing() {
            return Verdict::Allow;
        }
        use ripdpi_flow_app_attribution::FlowUidLookup;
        let protocol = registration_id.request().protocol;
        match ripdpi_flow_app_attribution::lookup_registered_flow_uid(registration_id) {
            FlowUidLookup::Missing => Verdict::deny_for(protocol),
            FlowUidLookup::Pending => Verdict::Pending,
            FlowUidLookup::Resolved(Some(uid)) => self.evaluate(uid, protocol),
            FlowUidLookup::Resolved(None) if self.block_unresolved => Verdict::deny_for(protocol),
            FlowUidLookup::Resolved(None) => Verdict::Allow,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};

    fn addr(port: u16) -> SocketAddr {
        SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port))
    }

    fn allowlist(uids: &[u32]) -> HashSet<u32> {
        uids.iter().copied().collect()
    }

    /// A `FlowUidSource` that returns a fixed answer, ignoring the flow tuple.
    struct FixedSource(UidLookup);
    impl FlowUidSource for FixedSource {
        fn uid_for(&self, _protocol: u8, _local: SocketAddr, _remote: SocketAddr) -> UidLookup {
            self.0
        }
    }

    #[test]
    fn disarmed_allows_every_flow() {
        let policy = UidFlowPolicy::disarmed();
        assert!(!policy.is_enforcing());
        // Even a UID that no allowlist would contain is allowed, both protocols.
        assert_eq!(policy.evaluate(12345, PROTO_TCP), Verdict::Allow);
        assert_eq!(policy.evaluate(12345, PROTO_UDP), Verdict::Allow);
        // admit never consults the source when disarmed (a denying source is ignored).
        assert_eq!(
            policy.admit(&FixedSource(UidLookup::Resolved(Some(999))), PROTO_TCP, addr(1), addr(2)),
            Verdict::Allow
        );
    }

    #[test]
    fn enforcing_allows_allowlisted_uid_on_both_protocols() {
        let policy = UidFlowPolicy::enforcing(allowlist(&[1000, 1001]));
        assert!(policy.is_enforcing());
        assert_eq!(policy.evaluate(1000, PROTO_TCP), Verdict::Allow);
        assert_eq!(policy.evaluate(1001, PROTO_UDP), Verdict::Allow);
    }

    #[test]
    fn enforcing_denies_unlisted_uid_with_protocol_specific_verdict() {
        let policy = UidFlowPolicy::enforcing(allowlist(&[1000]));
        // The SO_BINDTODEVICE escaper's UID is not on the allowlist.
        assert_eq!(policy.evaluate(2000, PROTO_TCP), Verdict::ResetTcp);
        assert_eq!(policy.evaluate(2000, PROTO_UDP), Verdict::DropUdp);
        // Unknown L4 (e.g. ICMP-in-IP, proto 1) defaults to the TCP-style reset.
        assert_eq!(policy.evaluate(2000, 1), Verdict::ResetTcp);
    }

    #[test]
    fn admit_resolves_then_evaluates() {
        let policy = UidFlowPolicy::enforcing(allowlist(&[1000]));
        // Resolver attributes the flow to an allowed UID → Allow.
        assert_eq!(
            policy.admit(&FixedSource(UidLookup::Resolved(Some(1000))), PROTO_TCP, addr(1), addr(2)),
            Verdict::Allow
        );
        // Resolver attributes it to a denied UID → reset/drop by protocol.
        assert_eq!(
            policy.admit(&FixedSource(UidLookup::Resolved(Some(2000))), PROTO_TCP, addr(1), addr(2)),
            Verdict::ResetTcp
        );
        assert_eq!(
            policy.admit(&FixedSource(UidLookup::Resolved(Some(2000))), PROTO_UDP, addr(1), addr(2)),
            Verdict::DropUdp
        );
    }

    #[test]
    fn enforcing_blocks_unresolved_by_default_fail_closed() {
        let policy = UidFlowPolicy::enforcing(allowlist(&[1000]));
        assert_eq!(
            policy.admit(&FixedSource(UidLookup::Resolved(None)), PROTO_TCP, addr(1), addr(2)),
            Verdict::ResetTcp
        );
        assert_eq!(
            policy.admit(&FixedSource(UidLookup::Resolved(None)), PROTO_UDP, addr(1), addr(2)),
            Verdict::DropUdp
        );
    }

    #[test]
    fn allowing_unresolved_passes_unattributable_flows() {
        let policy = UidFlowPolicy::enforcing(allowlist(&[1000])).allowing_unresolved();
        assert_eq!(policy.admit(&FixedSource(UidLookup::Resolved(None)), PROTO_TCP, addr(1), addr(2)), Verdict::Allow);
        assert_eq!(policy.admit(&FixedSource(UidLookup::Resolved(None)), PROTO_UDP, addr(1), addr(2)), Verdict::Allow);
        // A resolved-but-denied UID is still blocked even with unresolved allowed.
        assert_eq!(
            policy.admit(&FixedSource(UidLookup::Resolved(Some(2000))), PROTO_TCP, addr(1), addr(2)),
            Verdict::ResetTcp
        );
    }

    #[test]
    fn empty_enforcing_allowlist_denies_everything_resolved() {
        // An armed policy with no allowed UIDs blocks every attributed flow — the
        // caller must populate the allowlist before arming on-device.
        let policy = UidFlowPolicy::enforcing(HashSet::new());
        assert_eq!(policy.evaluate(1000, PROTO_TCP), Verdict::ResetTcp);
        assert_eq!(
            policy.admit(&FixedSource(UidLookup::Resolved(Some(1000))), PROTO_UDP, addr(1), addr(2)),
            Verdict::DropUdp
        );
    }

    #[test]
    fn pending_resolution_parks_admission_without_blocking() {
        let policy = UidFlowPolicy::enforcing(allowlist(&[1000]));
        assert_eq!(policy.admit(&FixedSource(UidLookup::Pending), PROTO_TCP, addr(1), addr(2)), Verdict::Pending);
        assert_eq!(policy.admit(&FixedSource(UidLookup::Missing), PROTO_UDP, addr(1), addr(2)), Verdict::Pending);
    }

    #[test]
    fn denylist_rejects_only_listed_uids() {
        let policy = UidFlowPolicy::denying(allowlist(&[2000]));
        assert_eq!(policy.evaluate(1000, PROTO_TCP), Verdict::Allow);
        assert_eq!(policy.evaluate(2000, PROTO_TCP), Verdict::ResetTcp);
    }
}
