//! Process-global VPN socket-protection callback registry.
//!
//! Holds at most one `ProtectCallback` — the JNI shim that calls
//! `VpnService.protect(int)` — in a process-global slot. `libripdpi.so` and
//! `libripdpi-warp.so` each link their own copy of this crate, so each `.so`
//! has an independent slot driven by its own `jniRegisterVpnProtect` /
//! `jniUnregisterVpnProtect` pair.
//!
//! ## Generation guard
//!
//! The slot is process-global, so an asymmetric register/unregister can let a
//! stale `unregister` from a torn-down VPN session clear a *newer* session's
//! callback (a "stale unregister"). Outbound sockets would then fail
//! [`protect_socket_via_callback`] and risk a routing loop into the TUN — see
//! `.claude/rules/vpnservice-protect-invariant.md`.
//!
//! [`register_protect_callback_versioned`] stamps the slot with a monotonic
//! [`ProtectGeneration`] and returns it; [`unregister_protect_callback_if`]
//! clears the slot only when the stored generation still matches, so a late
//! unregister from a superseded session becomes a no-op instead of clobbering
//! the live registration.
//!
//! Unlike the sibling root-helper registry
//! (`ripdpi-runtime-platform::root_helper`), this registry's register and
//! unregister are two *separate JNI calls* with no Rust object spanning them,
//! so the generation token round-trips through the JNI boundary:
//! `jniRegisterVpnProtect` returns it as a `jlong` and
//! `jniUnregisterVpnProtect(jlong)` takes it back. See
//! `docs/architecture/JNI_CONTRACT.md` §8.
//!
//! [`register_protect_callback`] / [`unregister_protect_callback`] remain as
//! unconditional back-compat wrappers for callers that do not race a later
//! session (a single active VPN session — the common case — is unaffected:
//! the generation increments and always matches on release).

#![forbid(unsafe_code)]

use std::io;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, RwLock};

pub trait ProtectCallback: Send + Sync {
    fn protect(&self, fd: RawFd) -> io::Result<()>;

    /// Resolve `host` on the network that owns the protected socket path.
    fn resolve_host(&self, _host: &str) -> io::Result<Vec<IpAddr>> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "protected hostname resolution is unavailable"))
    }
}

/// Per-runtime policy for outbound sockets created by a relay dialer.
///
/// Callback registration is process-global lifecycle state; it is not a safe
/// proxy for whether a particular runtime is inside an Android VPN. Callers
/// must carry this policy from their runtime configuration to every dialer.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub enum SocketProtectionPolicy {
    /// No TUN is active for this runtime, so outbound sockets connect directly.
    #[default]
    Inactive,
    /// A TUN is active and every non-loopback outbound socket must be protected.
    VpnRequired,
}

impl SocketProtectionPolicy {
    /// Apply the configured policy before any connect, bind, or network I/O.
    pub fn protect(self, fd: RawFd) -> io::Result<()> {
        match self {
            Self::Inactive => Ok(()),
            Self::VpnRequired => protect_socket_via_callback(fd),
        }
    }

    /// Apply the configured policy, preserving the documented loopback exemption.
    pub fn protect_non_loopback(self, fd: RawFd, target: SocketAddr) -> io::Result<()> {
        if target.ip().is_loopback() {
            return Ok(());
        }
        self.protect(fd)
    }

    /// Resolve a relay hostname without sending a DNS query through the app TUN.
    /// VPN mode delegates to the registered Android underlying-network callback;
    /// non-VPN mode retains the ordinary system resolver.
    pub async fn resolve_host(self, host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
        if let Ok(ip) = host.parse::<IpAddr>() {
            return Ok(vec![SocketAddr::new(ip, port)]);
        }
        let addresses = match self {
            Self::Inactive => tokio::net::lookup_host((host, port)).await?.collect::<Vec<_>>(),
            Self::VpnRequired => {
                let host = host.to_owned();
                tokio::task::spawn_blocking(move || resolve_host_via_callback(&host))
                    .await
                    .map_err(|error| io::Error::other(format!("protected DNS task failed: {error}")))??
                    .into_iter()
                    .map(|ip| SocketAddr::new(ip, port))
                    .collect()
            }
        };
        if addresses.is_empty() {
            Err(io::Error::new(io::ErrorKind::AddrNotAvailable, "relay hostname resolved to no addresses"))
        } else {
            Ok(addresses)
        }
    }

    /// Resolve a `host:port` authority through the policy-aware resolver.
    pub async fn resolve_authority(self, authority: &str) -> io::Result<Vec<SocketAddr>> {
        if let Ok(address) = authority.parse::<SocketAddr>() {
            return Ok(vec![address]);
        }
        let (host, port) = authority
            .rsplit_once(':')
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "relay authority must contain a port"))?;
        if !host.starts_with('[') && host.contains(':') {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "unbracketed IPv6 relay authority must use bracketed form",
            ));
        }
        let host = host.strip_prefix('[').and_then(|value| value.strip_suffix(']')).unwrap_or(host);
        let port = port
            .parse::<u16>()
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "relay authority has an invalid port"))?;
        self.resolve_host(host, port).await
    }
}

/// Monotonic token identifying one [`register_protect_callback_versioned`]
/// call.
///
/// Returned by the versioned register and passed back to
/// [`unregister_protect_callback_if`] so a stale unregister cannot clear a slot
/// a later session re-registered. The raw [`token`](Self::token) value
/// round-trips across the JNI boundary as a `jlong`; only equality against a
/// value the registry handed out is meaningful. Token `0` is never issued, so
/// it is a safe sentinel for "no registration" / a failed JNI register.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProtectGeneration(u64);

impl ProtectGeneration {
    /// The raw token value, for round-tripping across the JNI boundary.
    #[must_use]
    pub const fn token(self) -> u64 {
        self.0
    }

    /// Reconstruct a generation from a token previously returned by
    /// [`register_protect_callback_versioned`]. A token the registry never
    /// issued (including `0`) simply never matches the stored generation, so
    /// [`unregister_protect_callback_if`] is then a safe no-op.
    #[must_use]
    pub const fn from_token(token: u64) -> Self {
        Self(token)
    }
}

/// The registered callback plus the generation that registered it.
struct RegisteredProtect {
    generation: ProtectGeneration,
    callback: Arc<dyn ProtectCallback>,
}

static PROTECT_CB: RwLock<Option<RegisteredProtect>> = RwLock::new(None);
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);

/// Acquire the registry read guard, recovering from lock poison.
///
/// The registry is poison-recovering by policy: every slot write is a complete
/// assignment of an owned value, so a panic between writes can never leave torn
/// state and recovering the guard cannot observe a half-written slot. This
/// keeps the whole registry panic-free; fail-closed behavior lives in the
/// consumers — an empty (or poisoned-but-empty) slot still yields the typed
/// [`io::ErrorKind::NotConnected`] error instead of a fabricated callback.
fn registry_read() -> std::sync::RwLockReadGuard<'static, Option<RegisteredProtect>> {
    match PROTECT_CB.read() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

/// Acquire the registry write guard, recovering from lock poison.
///
/// Same rationale as [`registry_read`]: lifecycle writes are single owned
/// assignments, so recovery cannot observe torn state and register/unregister
/// calls stay panic-free even after a previous holder panicked.
fn registry_write() -> std::sync::RwLockWriteGuard<'static, Option<RegisteredProtect>> {
    match PROTECT_CB.write() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

/// Register the VPN protect callback and return the [`ProtectGeneration`]
/// stamped on the registry slot.
///
/// Prefer this over [`register_protect_callback`]: keep the returned
/// generation and release through [`unregister_protect_callback_if`] so a
/// stale unregister from a superseded VPN session cannot clobber a newer
/// session's callback.
#[must_use = "dropping the generation leaves this registration unpairable; keep it for unregister_protect_callback_if"]
pub fn register_protect_callback_versioned(cb: Arc<dyn ProtectCallback>) -> ProtectGeneration {
    // Relaxed: the counter only needs uniqueness, not ordering against the
    // slot write below — the write lock provides the happens-before edge.
    let generation = ProtectGeneration(NEXT_GENERATION.fetch_add(1, Ordering::Relaxed));
    let mut guard = registry_write();
    *guard = Some(RegisteredProtect { generation, callback: cb });
    generation
}

/// Register the VPN protect callback.
///
/// Unconditional back-compat wrapper over
/// [`register_protect_callback_versioned`] that discards the generation.
/// Callers that may race a later session should use the versioned form
/// together with [`unregister_protect_callback_if`].
pub fn register_protect_callback(cb: Arc<dyn ProtectCallback>) {
    let _ = register_protect_callback_versioned(cb);
}

/// Unregister the VPN protect callback only if the slot still carries
/// `generation`.
///
/// Returns `true` when the slot was cleared, and `false` — a safe no-op — when
/// a newer `register_protect_callback*` call has already superseded this
/// generation (the stale unregister is ignored) or when no callback is
/// registered at all. This is the safe release path for the paired JNI
/// register/unregister calls.
#[must_use = "a false return means the slot was not cleared (stale generation or empty); ignoring it hides an unpaired release"]
pub fn unregister_protect_callback_if(generation: ProtectGeneration) -> bool {
    let mut guard = registry_write();
    match guard.as_ref() {
        Some(registered) if registered.generation == generation => {
            *guard = None;
            true
        }
        // Stale generation (superseded by a newer session) or empty slot:
        // ignore the unregister so a live registration is never clobbered.
        Some(_) | None => false,
    }
}

/// Unregister the VPN protect callback.
///
/// Unconditional back-compat wrapper: clears whatever callback is in the slot.
/// Prefer [`unregister_protect_callback_if`] when a stale unregister could
/// otherwise clear a newer session's callback.
pub fn unregister_protect_callback() {
    let mut guard = registry_write();
    *guard = None;
}

/// Invoke a snapshot of the registered callback without holding the registry
/// lock across JNI or other blocking callback work.
pub fn protect_socket_via_callback(fd: RawFd) -> io::Result<()> {
    let callback = protect_callback_snapshot();
    callback
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotConnected, "VPN protect callback not registered"))?
        .protect(fd)
}

/// Resolve through a snapshot of the registered callback without holding the
/// registry lock across JNI or blocking network work.
pub fn resolve_host_via_callback(host: &str) -> io::Result<Vec<IpAddr>> {
    protect_callback_snapshot()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotConnected, "VPN protect callback not registered"))?
        .resolve_host(host)
}

fn protect_callback_snapshot() -> Option<Arc<dyn ProtectCallback>> {
    let guard = registry_read();
    guard.as_ref().map(|registered| Arc::clone(&registered.callback))
}

/// Returns whether a protect callback is registered.
///
/// Fail **closed** on any registry anomaly that would otherwise look like
/// "absent": the skip-protect consumers that gate on
/// `if !has_protect_callback() { return Ok(()) }` never silently emit an
/// unprotected non-loopback connect/bind. The registry recovers from lock
/// poison (see [`registry_read`]) because slot writes are complete owned
/// assignments, so this accessor reports the actual stored state — under any
/// live VPN session that is `Some(callback)` — while a genuinely empty slot
/// still reports `false`, preserving desktop / VPN-down no-op semantics. A
/// consumer that races a concurrent teardown gets the typed
/// [`io::ErrorKind::NotConnected`] error from [`protect_socket_via_callback`]
/// instead of proceeding unprotected. See
/// `.claude/rules/vpnservice-protect-invariant.md`.
pub fn has_protect_callback() -> bool {
    registry_read().is_some()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use std::sync::atomic::AtomicI32;

    static TEST_MUTEX: Mutex<()> = Mutex::new(());

    struct TestCallback {
        last_fd: AtomicI32,
        resolved: Vec<IpAddr>,
    }

    impl TestCallback {
        fn new() -> Self {
            Self { last_fd: AtomicI32::new(-1), resolved: vec![IpAddr::V4(std::net::Ipv4Addr::new(203, 0, 113, 7))] }
        }
    }

    impl ProtectCallback for TestCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            // Release pairs with Acquire in the test assertion below.
            self.last_fd.store(fd, Ordering::Release);
            Ok(())
        }

        fn resolve_host(&self, _host: &str) -> io::Result<Vec<IpAddr>> {
            Ok(self.resolved.clone())
        }
    }

    #[test]
    fn no_callback_returns_error() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();
        let result = protect_socket_via_callback(42);
        assert!(result.is_err());
        assert_eq!(result.expect_err("missing callback").kind(), io::ErrorKind::NotConnected);
    }

    #[test]
    fn inactive_policy_allows_proxy_mode_without_callback() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        assert!(SocketProtectionPolicy::Inactive.protect(42).is_ok());
    }

    #[test]
    fn vpn_required_policy_rejects_missing_callback() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        let error = SocketProtectionPolicy::VpnRequired.protect(42).expect_err("must fail closed");
        assert_eq!(error.kind(), io::ErrorKind::NotConnected);
    }

    #[test]
    fn vpn_required_resolution_uses_registered_callback() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();
        register_protect_callback(Arc::new(TestCallback::new()));

        let runtime = tokio::runtime::Builder::new_current_thread().build().expect("runtime");
        let addresses = runtime
            .block_on(SocketProtectionPolicy::VpnRequired.resolve_host("relay.example", 443))
            .expect("protected resolution");

        assert_eq!(addresses, vec!["203.0.113.7:443".parse().expect("address")]);
        unregister_protect_callback();
    }

    #[test]
    fn vpn_required_resolution_without_callback_fails_closed() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        let runtime = tokio::runtime::Builder::new_current_thread().build().expect("runtime");
        let error = runtime
            .block_on(SocketProtectionPolicy::VpnRequired.resolve_host("relay.example", 443))
            .expect_err("missing resolver callback must fail closed");

        assert_eq!(error.kind(), io::ErrorKind::NotConnected);
    }

    struct RejectingCallback;

    impl ProtectCallback for RejectingCallback {
        fn protect(&self, _fd: RawFd) -> io::Result<()> {
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "test rejection"))
        }
    }

    #[test]
    fn vpn_required_policy_propagates_callback_rejection() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        register_protect_callback(Arc::new(RejectingCallback));

        let error = SocketProtectionPolicy::VpnRequired.protect(42).expect_err("must fail closed");
        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
        unregister_protect_callback();
    }

    #[test]
    fn register_and_invoke() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        let cb = Arc::new(TestCallback::new());
        let cb_ref = Arc::clone(&cb);
        register_protect_callback(cb);

        assert!(has_protect_callback());
        assert!(protect_socket_via_callback(99).is_ok());
        // Acquire pairs with Release in TestCallback::protect.
        assert_eq!(cb_ref.last_fd.load(Ordering::Acquire), 99);

        unregister_protect_callback();
        assert!(!has_protect_callback());
    }

    struct RegistryLockProbeCallback {
        lock_available: std::sync::atomic::AtomicBool,
    }

    impl ProtectCallback for RegistryLockProbeCallback {
        fn protect(&self, _fd: RawFd) -> io::Result<()> {
            self.lock_available.store(PROTECT_CB.try_write().is_ok(), Ordering::Release);
            Ok(())
        }
    }

    #[test]
    fn callback_runs_without_holding_registry_lock() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        let callback =
            Arc::new(RegistryLockProbeCallback { lock_available: std::sync::atomic::AtomicBool::new(false) });
        let callback_ref = Arc::clone(&callback);
        register_protect_callback(callback);

        protect_socket_via_callback(99).expect("protect callback");

        assert!(callback_ref.lock_available.load(Ordering::Acquire));
        unregister_protect_callback();
    }

    /// Characterizes the unconditional back-compat wrapper: a single
    /// [`unregister_protect_callback`] clears whatever callback is in the slot,
    /// even one a *later* `register` installed. Generation-aware callers use
    /// [`unregister_protect_callback_if`] instead — see the tests below.
    #[test]
    fn unregister_is_unconditional_for_overwritten_callbacks() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        // Session A registers, then session B overwrites the slot.
        register_protect_callback(Arc::new(TestCallback::new()));
        register_protect_callback(Arc::new(TestCallback::new()));
        assert!(has_protect_callback());

        // A single unconditional unregister clears session B's callback.
        unregister_protect_callback();
        assert!(!has_protect_callback());
    }

    #[test]
    fn versioned_register_hands_out_distinct_generations() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();
        let first = register_protect_callback_versioned(Arc::new(TestCallback::new()));
        let second = register_protect_callback_versioned(Arc::new(TestCallback::new()));
        assert_ne!(first, second);
        unregister_protect_callback();
    }

    /// A stale unregister from a superseded VPN session must not clear the
    /// newer session's callback — the core stale-unregister hazard.
    #[test]
    fn stale_unregister_does_not_clear_a_newer_callback() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        // Session A registers, then session B supersedes it before A's
        // unregister runs.
        let stale = register_protect_callback_versioned(Arc::new(TestCallback::new()));
        let current = register_protect_callback_versioned(Arc::new(TestCallback::new()));

        // A's late unregister carries a generation the slot no longer holds —
        // it is ignored and session B's callback survives.
        assert!(!unregister_protect_callback_if(stale));
        assert!(has_protect_callback());

        // B's own unregister carries the matching generation and clears it.
        assert!(unregister_protect_callback_if(current));
        assert!(!has_protect_callback());
    }

    /// The current generation's unregister clears the callback it registered.
    #[test]
    fn current_generation_unregister_clears_the_callback() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        let cb = Arc::new(TestCallback::new());
        let cb_ref = Arc::clone(&cb);
        let generation = register_protect_callback_versioned(cb);

        assert!(has_protect_callback());
        assert!(protect_socket_via_callback(7).is_ok());
        // Acquire pairs with Release in TestCallback::protect.
        assert_eq!(cb_ref.last_fd.load(Ordering::Acquire), 7);

        assert!(unregister_protect_callback_if(generation));
        assert!(!has_protect_callback());

        // Releasing an already-cleared generation is a harmless no-op.
        assert!(!unregister_protect_callback_if(generation));
    }

    /// A token the registry never issued — including `0`, which a failed JNI
    /// register hands back — is a safe no-op and never clears a live slot.
    #[test]
    fn unregister_with_unknown_token_is_a_safe_noop() {
        let _lock = TEST_MUTEX.lock().expect("test mutex");
        unregister_protect_callback();

        // Empty slot: unregistering any token is a no-op.
        assert!(!unregister_protect_callback_if(ProtectGeneration::from_token(0)));

        // Live slot: an unknown token does not clear it.
        let generation = register_protect_callback_versioned(Arc::new(TestCallback::new()));
        assert!(!unregister_protect_callback_if(ProtectGeneration::from_token(0)));
        assert!(has_protect_callback());

        // The genuine generation still clears it.
        assert!(unregister_protect_callback_if(generation));
    }
}

#[cfg(test)]
mod bare_ipv6_rejection_tests {
    use super::*;

    /// Regression test (audit H4 siblings): a bare IPv6 authority must be
    /// rejected with `InvalidInput` instead of reaching the resolver as a
    /// corrupted host (`"2001:db8:"`).
    #[test]
    fn resolve_authority_rejects_bare_ipv6_authority() {
        let error = tokio::runtime::Builder::new_current_thread()
            .build()
            .expect("test runtime")
            .block_on(SocketProtectionPolicy::Inactive.resolve_authority("2001:db8::1"))
            .expect_err("bare IPv6 authority must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn resolve_authority_accepts_bracketed_ipv6_authority() {
        let addresses = tokio::runtime::Builder::new_current_thread()
            .build()
            .expect("test runtime")
            .block_on(SocketProtectionPolicy::Inactive.resolve_authority("[2001:db8::1]:443"))
            .expect("bracketed IPv6 authority parses without DNS");
        assert_eq!(addresses, vec![SocketAddr::new("2001:db8::1".parse().expect("ipv6"), 443)]);
    }
}
