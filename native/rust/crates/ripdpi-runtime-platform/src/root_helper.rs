//! Runtime-adaptation — process-global registry for the root helper client.
//!
//! Follows the same pattern as `protect.rs` — a global `RwLock<Option<...>>`
//! that is set at startup when root mode is enabled and the helper socket
//! path is known. The other runtime-adaptation modules (`fake_send`,
//! `ip_fragmentation`, `experimental_tier3`) read this slot through
//! [`with_root_helper`] to decide between the privileged and local paths.
//! Linux/Android only.
//!
//! ## Generation guard
//!
//! Registration is process-global, so an asymmetric register/unregister can
//! let a stale `unregister` from an ended session clear a *newer* session's
//! client (a "stale unregister"). Each [`register_root_helper_versioned`] call
//! stamps the slot with a monotonic [`RootHelperGeneration`];
//! [`unregister_root_helper_if`] clears the slot only when its generation
//! still matches, so a late unregister from a superseded session becomes a
//! no-op instead of clobbering the live registration.
//!
//! RAII guards should register via [`register_root_helper_versioned`], keep
//! the returned generation, and release through [`unregister_root_helper_if`].
//! [`register_root_helper`] / [`unregister_root_helper`] remain as
//! unconditional back-compat wrappers for callers that do not race a later
//! session (single active session — the common case — is unaffected: the
//! generation increments and always matches on release).

use std::io;
use std::sync::atomic::{AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, OnceLock, RwLock};
use std::time::Instant;

use super::root_helper_client::{RootHelperClient, is_not_delivered};

/// Monotonic token identifying one [`register_root_helper_versioned`] call.
///
/// Held by the registering RAII guard and passed back to
/// [`unregister_root_helper_if`] so a stale unregister cannot clear a slot a
/// later session re-registered. Opaque: only equality against the value the
/// registry handed out is meaningful.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RootHelperGeneration(u64);

/// The registered client, the generation that registered it, and its
/// reachability state.
struct RegisteredRootHelper {
    generation: RootHelperGeneration,
    client: RootHelperClient,
    health: HelperHealth,
}

static ROOT_HELPER: RwLock<Option<Arc<RegisteredRootHelper>>> = RwLock::new(None);
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);

/// How long every operation skips a helper that did not take a command.
/// Each attempt on a dead or wedged helper costs a nonce read and up to two
/// bounded connects on the data-plane thread.
const UNREACHABLE_BACKOFF_MS: u64 = 5_000;

/// Why a registered helper did not receive a command.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum FallbackCause {
    /// The socket or the session nonce file does not exist.
    Missing,
    /// Nothing listens on the socket.
    Refused,
    /// The connect or the protocol preflight did not finish in time.
    TimedOut,
    /// The request frame is larger than the IPC limit.
    FrameTooLarge,
    /// Bad nonce or protocol version mismatch.
    Rejected,
    Other,
}

impl FallbackCause {
    fn of(error: &io::Error) -> Self {
        match error.kind() {
            io::ErrorKind::NotFound => Self::Missing,
            io::ErrorKind::ConnectionRefused => Self::Refused,
            io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => Self::TimedOut,
            io::ErrorKind::InvalidInput => Self::FrameTooLarge,
            io::ErrorKind::InvalidData | io::ErrorKind::PermissionDenied => Self::Rejected,
            _ => Self::Other,
        }
    }

    fn label(self) -> &'static str {
        match self {
            Self::Missing => "helper_socket_or_nonce_missing",
            Self::Refused => "helper_connection_refused",
            Self::TimedOut => "helper_timed_out",
            Self::FrameTooLarge => "request_frame_too_large",
            Self::Rejected => "helper_rejected_session",
            Self::Other => "helper_unreachable",
        }
    }

    /// An oversized frame says nothing about the helper; only that one
    /// operation goes local.
    fn backs_off(self) -> bool {
        self != Self::FrameTooLarge
    }

    fn bit(self) -> u8 {
        1 << (self as u8)
    }
}

/// Reachability of one registration: a backoff window after a failed
/// delivery and the causes already logged during the current outage.
#[derive(Default)]
struct HelperHealth {
    skip_until_ms: AtomicU64,
    warned_causes: AtomicU8,
}

impl HelperHealth {
    fn skips(&self, now_ms: u64) -> bool {
        now_ms < self.skip_until_ms.load(Ordering::Relaxed)
    }

    /// Starts the backoff when `cause` calls for one. Returns `true` the
    /// first time `cause` occurs in this outage.
    fn record_fallback(&self, cause: FallbackCause, now_ms: u64) -> bool {
        if cause.backs_off() {
            self.skip_until_ms.fetch_max(now_ms.saturating_add(UNREACHABLE_BACKOFF_MS), Ordering::Relaxed);
        }
        self.warned_causes.fetch_or(cause.bit(), Ordering::Relaxed) & cause.bit() == 0
    }

    /// Ends the outage. Returns `true` when an outage was in progress.
    fn record_delivered(&self) -> bool {
        self.warned_causes.load(Ordering::Relaxed) != 0 && self.warned_causes.swap(0, Ordering::Relaxed) != 0
    }
}

/// Milliseconds on a monotonic process clock.
fn now_ms() -> u64 {
    static EPOCH: OnceLock<Instant> = OnceLock::new();
    u64::try_from(EPOCH.get_or_init(Instant::now).elapsed().as_millis()).unwrap_or(u64::MAX)
}

/// Register the root helper client and return the [`RootHelperGeneration`]
/// stamped on the registry slot.
///
/// Prefer this over [`register_root_helper`] for RAII guards: keep the
/// returned generation and release through [`unregister_root_helper_if`] so a
/// stale unregister cannot clobber a newer session's registration.
pub fn register_root_helper_versioned(socket_path: String) -> RootHelperGeneration {
    let generation = RootHelperGeneration(NEXT_GENERATION.fetch_add(1, Ordering::Relaxed));
    let mut guard = ROOT_HELPER.write().expect("root helper lock poisoned");
    *guard = Some(Arc::new(RegisteredRootHelper {
        generation,
        client: RootHelperClient::new(socket_path),
        health: HelperHealth::default(),
    }));
    tracing::info!(generation = generation.0, "root helper client registered");
    generation
}

/// Register the root helper client. Called at service startup when
/// `root_mode = true` and the helper socket path is available.
///
/// Unconditional back-compat wrapper over [`register_root_helper_versioned`]
/// that discards the generation. Callers that may race a later session should
/// use the versioned form together with [`unregister_root_helper_if`].
pub fn register_root_helper(socket_path: String) {
    let _ = register_root_helper_versioned(socket_path);
}

/// Unregister the root helper client. Called at service shutdown.
///
/// Unconditional: clears whatever is registered. Prefer
/// [`unregister_root_helper_if`] when a stale unregister could otherwise clear
/// a newer session's client.
pub fn unregister_root_helper() {
    let mut guard = ROOT_HELPER.write().expect("root helper lock poisoned");
    *guard = None;
    tracing::info!("root helper client unregistered");
}

/// Unregister the root helper client only if the slot still carries
/// `generation`.
///
/// Returns `true` when the slot was cleared, `false` when a newer
/// `register_root_helper*` call has already superseded this generation — the
/// stale unregister is then ignored and logged. This is the safe release path
/// for RAII guards.
pub fn unregister_root_helper_if(generation: RootHelperGeneration) -> bool {
    let mut guard = ROOT_HELPER.write().expect("root helper lock poisoned");
    match guard.as_ref() {
        Some(registered) if registered.generation == generation => {
            *guard = None;
            tracing::info!(generation = generation.0, "root helper client unregistered");
            true
        }
        Some(registered) => {
            tracing::warn!(
                stale = generation.0,
                current = registered.generation.0,
                "ignored stale root helper unregister (superseded by a newer session)"
            );
            false
        }
        None => false,
    }
}

/// Identifies the current registration for caches of helper-dependent
/// answers: the registering generation, or 0 when no helper is registered.
pub(crate) fn registration_id() -> u64 {
    ROOT_HELPER.read().ok().and_then(|guard| guard.as_ref().map(|registered| registered.generation.0)).unwrap_or(0)
}

/// Returns `true` if a root helper client is currently registered.
pub fn has_root_helper() -> bool {
    ROOT_HELPER.read().is_ok_and(|guard| guard.is_some())
}

fn registered_root_helper() -> Option<Arc<RegisteredRootHelper>> {
    ROOT_HELPER.read().ok()?.as_ref().map(Arc::clone)
}

/// Execute a closure with a reference to the root helper client.
///
/// Returns `None` if no helper is registered. The registry lock is released
/// before `f` runs; an `Arc` snapshot keeps an in-flight client alive across a
/// concurrent unregister without blocking registry lifecycle operations.
pub fn with_root_helper<F, R>(f: F) -> Option<R>
where
    F: FnOnce(&RootHelperClient) -> R,
{
    let registered = registered_root_helper()?;
    Some(f(&registered.client))
}

/// Like [`with_root_helper`], but also returns `None` when the registered
/// helper never received the command (dead or wedged socket, missing nonce,
/// protocol preflight failure, oversized frame). The helper ran nothing, so
/// the caller degrades to the local path instead of failing the operation.
///
/// After a failed delivery other than an oversized frame, the helper is
/// skipped for [`UNREACHABLE_BACKOFF_MS`], so the data plane does not wait on
/// it for each operation. The first fallback per cause in an outage is logged
/// at `warn`. The local error is returned unchanged: callers degrade on its
/// errno (for example EPERM from a raw socket without root).
pub(crate) fn with_reachable_root_helper<F, T>(f: F) -> Option<io::Result<T>>
where
    F: FnOnce(&RootHelperClient) -> io::Result<T>,
{
    let registered = registered_root_helper()?;
    if registered.health.skips(now_ms()) {
        return None;
    }
    match f(&registered.client) {
        Err(error) if is_not_delivered(&error) => {
            let cause = FallbackCause::of(&error);
            if registered.health.record_fallback(cause, now_ms()) {
                tracing::warn!(
                    generation = registered.generation.0,
                    cause = cause.label(),
                    "root helper did not take the command; using the local path, where raw-socket operations \
                     fail without root: {error}"
                );
            } else {
                tracing::debug!(cause = cause.label(), "root helper unreachable, using the local path: {error}");
            }
            None
        }
        result => {
            if registered.health.record_delivered() {
                tracing::info!(generation = registered.generation.0, "root helper reachable again");
            }
            Some(result)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    static TEST_MUTEX: Mutex<()> = Mutex::new(());

    #[test]
    fn no_helper_returns_none() {
        let _lock = TEST_MUTEX.lock().unwrap();
        unregister_root_helper();
        assert!(!has_root_helper());
        let result = with_root_helper(|_| 42);
        assert!(result.is_none());
    }

    #[test]
    fn register_and_check() {
        let _lock = TEST_MUTEX.lock().unwrap();
        register_root_helper("/tmp/test_root_helper.sock".into());
        assert!(has_root_helper());
        let result = with_root_helper(super::super::root_helper_client::RootHelperClient::socket_path);
        assert_eq!(result, Some("/tmp/test_root_helper.sock".to_string()));
        unregister_root_helper();
        assert!(!has_root_helper());
    }

    #[test]
    fn callback_runs_without_holding_registry_lock() {
        let _lock = TEST_MUTEX.lock().unwrap();
        register_root_helper("/tmp/test_root_helper.sock".into());

        let lock_available = with_root_helper(|_| ROOT_HELPER.try_write().is_ok());

        assert_eq!(lock_available, Some(true));
        unregister_root_helper();
    }

    #[test]
    fn unreachable_helper_falls_back_to_the_local_path() {
        let _lock = TEST_MUTEX.lock().unwrap();
        register_root_helper("/nonexistent/ripdpi-root-helper.sock".into());

        assert!(with_root_helper(RootHelperClient::probe_capabilities).is_some_and(|result| result.is_err()));
        assert!(with_reachable_root_helper(RootHelperClient::probe_capabilities).is_none());
        // A new registration starts without the backoff of the old one.
        register_root_helper("/nonexistent/ripdpi-root-helper.sock".into());
        let helper_error = with_reachable_root_helper(|_| -> io::Result<()> { Err(io::Error::other("refused")) });
        assert!(helper_error.is_some_and(|result| result.is_err()), "helper-side errors still surface");
        unregister_root_helper();
    }

    #[test]
    fn unreachable_helper_is_skipped_until_the_backoff_ends() {
        let _lock = TEST_MUTEX.lock().unwrap();
        register_root_helper("/nonexistent/ripdpi-root-helper.sock".into());
        assert!(with_reachable_root_helper(RootHelperClient::probe_capabilities).is_none());

        let mut attempts = 0;
        let skipped = with_reachable_root_helper(|_| -> io::Result<()> {
            attempts += 1;
            Ok(())
        });

        assert!(skipped.is_none(), "the op must take the local path during the backoff");
        assert_eq!(attempts, 0, "the helper must not be contacted during the backoff");
        unregister_root_helper();
    }

    #[test]
    fn backoff_lasts_five_seconds_and_skips_oversized_frames() {
        let health = HelperHealth::default();
        assert!(health.record_fallback(FallbackCause::FrameTooLarge, 1_000), "first oversized frame is logged");
        assert!(!health.skips(1_001), "an oversized frame says nothing about the helper");

        assert!(health.record_fallback(FallbackCause::Refused, 1_000), "first refusal is logged");
        assert!(!health.record_fallback(FallbackCause::Refused, 1_500), "a repeated cause is not logged again");
        assert!(health.skips(6_499));
        assert!(!health.skips(6_500));

        assert!(health.record_delivered(), "a delivery ends the outage");
        assert!(health.record_fallback(FallbackCause::Refused, 7_000), "a new outage logs the cause again");
    }

    #[test]
    fn fallback_cause_follows_the_transport_error_kind() {
        let cause = |kind| FallbackCause::of(&io::Error::from(kind));
        assert_eq!(cause(io::ErrorKind::NotFound), FallbackCause::Missing);
        assert_eq!(cause(io::ErrorKind::ConnectionRefused), FallbackCause::Refused);
        assert_eq!(cause(io::ErrorKind::TimedOut), FallbackCause::TimedOut);
        assert_eq!(cause(io::ErrorKind::WouldBlock), FallbackCause::TimedOut);
        assert_eq!(cause(io::ErrorKind::InvalidInput), FallbackCause::FrameTooLarge);
    }

    #[test]
    fn versioned_register_hands_out_distinct_generations() {
        let _lock = TEST_MUTEX.lock().unwrap();
        unregister_root_helper();
        let first = register_root_helper_versioned("/tmp/first.sock".into());
        let second = register_root_helper_versioned("/tmp/second.sock".into());
        assert_ne!(first, second);
        unregister_root_helper();
    }

    #[test]
    fn stale_unregister_is_ignored_after_a_newer_register() {
        let _lock = TEST_MUTEX.lock().unwrap();
        unregister_root_helper();

        let stale = register_root_helper_versioned("/tmp/old-session.sock".into());
        let current = register_root_helper_versioned("/tmp/new-session.sock".into());

        // The stale generation no longer matches the slot -> unregister is a no-op
        // and the newer session's client survives.
        assert!(!unregister_root_helper_if(stale));
        assert!(has_root_helper());
        assert_eq!(with_root_helper(RootHelperClient::socket_path), Some("/tmp/new-session.sock".to_string()));

        // The current generation matches and clears the slot.
        assert!(unregister_root_helper_if(current));
        assert!(!has_root_helper());

        // Releasing an already-cleared generation is a harmless no-op.
        assert!(!unregister_root_helper_if(current));
    }
}
