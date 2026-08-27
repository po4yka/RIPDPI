//! Process-global per-flow app-attribution cache.
//!
//! Bridges the two halves of RIPDPI's data plane. The TUN core
//! (`ripdpi-tunnel-core`) is the only place that knows the *originating* app's
//! 5-tuple; the proxy-runtime policy layer (`ripdpi-runtime-policy`) is where
//! direct-path learning verdicts (`NO_TCP_FALLBACK`, …) are decided — but it
//! only sees a loopback socket, never the app. This crate is the shared,
//! process-global side-channel both halves consult. App attribution is keyed by
//! the **destination IP** (the one value both sides can compute), while native
//! admission keeps a separate full-5-tuple UID cache:
//!
//! * TUN core, at flow birth, calls [`note_flow`] with the originating 5-tuple.
//!   On a cache miss the request is enqueued for asynchronous resolution and
//!   `None` is returned immediately — **never** blocking the per-flow / per-packet
//!   path (see `.claude/rules/vpnservice-protect-invariant.md`).
//! * A background worker (the JNI adapter) drains [`pop_pending_request`],
//!   resolves UID → package → version off the hot path, and calls
//!   [`store_flow_resolution`] plus [`store_uid_resolution`].
//! * The policy layer calls [`lookup_flow`] (read-only, no enqueue) for each of
//!   its candidate destination IPs to attribute a learning signal.
//!
//! Like `ripdpi-native-protect`, each `.so` links its own copy, so the cache is
//! per-`.so`. State is bounded (LRU) and additionally evicted on flow close
//! ([`evict_flow`]) and cleared on session teardown ([`end_attribution_session_if`]).
//!
//! ## Conservative by default
//!
//! A `Resolved(None)` entry is the deliberate "unattributed" verdict — shared
//! UID, multiple packages behind one UID, or a failed lookup. The policy layer
//! treats a missing or `None` attribution as "no per-app verdict", so the system
//! degrades to authority/IP-scoped policy rather than guessing. Two apps to the
//! same destination IP collide on the key; that too resolves conservatively.
//!
//! ## Generation guard
//!
//! The cache is process-global, so an asymmetric session begin/end could let a
//! stale teardown from a superseded VPN session clear a newer session's state.
//! [`begin_attribution_session`] stamps a monotonic [`AttributionGeneration`];
//! [`end_attribution_session_if`] clears state only when the stored generation
//! still matches, mirroring `ripdpi-native-protect`'s protect-callback guard.

#![forbid(unsafe_code)]

use std::collections::VecDeque;
use std::net::{IpAddr, SocketAddr};
use std::num::NonZeroUsize;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

use lru::LruCache;

/// Max distinct destination IPs tracked before LRU eviction kicks in. A backstop
/// for flows whose explicit [`evict_flow`] was missed; sized for many concurrent
/// destinations without unbounded growth under churn.
const CACHE_CAPACITY: usize = 4096;

/// Max pending resolve requests buffered for the worker. If the worker stalls,
/// the oldest requests are dropped (the corresponding flows stay unattributed —
/// conservative) rather than growing without bound.
const PENDING_CAPACITY: usize = 1024;

/// Absolute lifetime for an admission-only DNS UID verdict. Five seconds spans
/// a DNS transaction burst without allowing traffic to renew a stale socket
/// owner indefinitely after the kernel reuses the exact UDP tuple.
const ADMISSION_ABSOLUTE_TTL: Duration = Duration::from_secs(5);

/// Resolved owning-app identity for a flow's destination IP.
///
/// `package` is an `Arc<str>` so cache hits clone cheaply. `version_code` is the
/// Android `longVersionCode`. This value is used **in memory only** for the
/// per-app policy decision; it must never be persisted or exported (see
/// `.claude/rules/network-fingerprint-privacy.md`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FlowAppContext {
    package: std::sync::Arc<str>,
    version_code: i64,
}

impl FlowAppContext {
    #[must_use]
    pub fn new(package: impl AsRef<str>, version_code: i64) -> Self {
        Self { package: std::sync::Arc::from(package.as_ref()), version_code }
    }

    #[must_use]
    pub fn package(&self) -> &str {
        &self.package
    }

    #[must_use]
    pub fn version_code(&self) -> i64 {
        self.version_code
    }
}

/// App-attribution cache entry state for a destination IP.
#[derive(Debug, Clone)]
enum Resolution {
    /// A resolve request has been enqueued; no answer yet.
    Pending,
    /// Resolution finished. `None` is the conservative "unattributed" verdict.
    Resolved(Option<FlowAppContext>),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum UidResolutionState {
    Pending,
    Resolved(Option<u32>),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct UidResolution {
    generation: u64,
    state: UidResolutionState,
    kind: FlowResolutionKind,
    created_at: Instant,
}

/// Cached UID lookup state for one complete flow tuple.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FlowUidLookup {
    Missing,
    Pending,
    Resolved(Option<u32>),
}

/// A pending request the worker resolves off the hot path. Carries the full
/// 5-tuple so the resolver can call `getConnectionOwnerUid(protocol, local, remote)`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct FlowResolveRequest {
    /// IP protocol number: 6 = TCP, 17 = UDP.
    pub protocol: u8,
    /// The originating app's local endpoint (TUN-side source).
    pub local: SocketAddr,
    /// The flow's destination endpoint.
    pub remote: SocketAddr,
}

/// Resolution purpose carried to the Android worker.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FlowResolutionKind {
    /// Resolve UID and update destination-level app attribution.
    Attribution,
    /// Resolve only the exact tuple's UID for admission.
    AdmissionOnly,
}

impl FlowResolveRequest {
    /// The app-attribution cache key for this request — the destination IP.
    #[must_use]
    pub fn key(&self) -> IpAddr {
        self.remote.ip()
    }
}

/// Opaque identity of one exact flow registration.
///
/// The generation prevents a delayed cleanup from removing a later flow that reused the same protocol/local/remote tuple.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct FlowAttributionToken {
    request: FlowResolveRequest,
    generation: u64,
}

impl FlowAttributionToken {
    /// Exact tuple owned by this registration.
    #[must_use]
    pub const fn request(&self) -> FlowResolveRequest {
        self.request
    }
}

/// Result of recording a flow, including the token its owner must release.
#[must_use = "the flow token must be retained and released with evict_flow"]
#[derive(Debug, PartialEq, Eq)]
pub struct FlowObservation {
    /// Destination-level app context already cached when the flow was noted.
    pub context: Option<FlowAppContext>,
    /// Exact registration identity that must be released with [`evict_flow`].
    pub token: FlowAttributionToken,
}

/// Generation-stamped unit of work drained by the asynchronous UID resolver.
#[derive(Debug, PartialEq, Eq)]
pub struct FlowResolutionJob {
    request: FlowResolveRequest,
    generation: u64,
    kind: FlowResolutionKind,
}

impl FlowResolutionJob {
    #[must_use]
    pub const fn request(&self) -> FlowResolveRequest {
        self.request
    }

    /// Whether this job may update destination-level app attribution.
    #[must_use]
    pub const fn kind(&self) -> FlowResolutionKind {
        self.kind
    }
}

/// A resolver the background worker drives. Defined here so the JNI adapter can
/// implement it; this crate never calls it on the hot path.
pub trait AppUidResolver: Send + Sync {
    /// Resolve the owning app for a flow, or `None` if unattributable
    /// (shared/unknown UID, multiple packages, lookup failure).
    fn resolve(&self, request: FlowResolveRequest) -> Option<FlowAppContext>;
}

/// Monotonic token identifying one [`begin_attribution_session`] call, so a
/// stale teardown cannot clear a newer session's state. Token `0` is never
/// issued, so it is a safe "no session" sentinel.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AttributionGeneration(u64);

impl AttributionGeneration {
    /// Raw token, for round-tripping across the JNI boundary as a `jlong`.
    #[must_use]
    pub const fn token(self) -> u64 {
        self.0
    }

    /// Reconstruct from a token previously handed out. An unknown token
    /// (including `0`) never matches, so [`end_attribution_session_if`] is then a
    /// safe no-op.
    #[must_use]
    pub const fn from_token(token: u64) -> Self {
        Self(token)
    }
}

struct State {
    cache: LruCache<IpAddr, Resolution>,
    uid_cache: LruCache<FlowResolveRequest, UidResolution>,
    pending: VecDeque<FlowResolutionJob>,
    generation: u64,
}

impl State {
    fn new() -> Self {
        Self {
            cache: LruCache::new(NonZeroUsize::new(CACHE_CAPACITY).expect("non-zero cache capacity")),
            uid_cache: LruCache::new(NonZeroUsize::new(CACHE_CAPACITY).expect("non-zero UID cache capacity")),
            pending: VecDeque::new(),
            generation: 0,
        }
    }

    fn clear(&mut self) {
        self.cache.clear();
        self.uid_cache.clear();
        self.pending.clear();
    }
}

fn state() -> &'static Mutex<State> {
    static STATE: std::sync::OnceLock<Mutex<State>> = std::sync::OnceLock::new();
    STATE.get_or_init(|| Mutex::new(State::new()))
}

/// Signaled when a request is pushed, so a worker can block in
/// [`pop_pending_request`] without busy-polling.
fn pending_signal() -> &'static Condvar {
    static SIGNAL: std::sync::OnceLock<Condvar> = std::sync::OnceLock::new();
    SIGNAL.get_or_init(Condvar::new)
}

static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);
static NEXT_FLOW_GENERATION: AtomicU64 = AtomicU64::new(1);

fn lock() -> std::sync::MutexGuard<'static, State> {
    state().lock().expect("flow-app-attribution state poisoned")
}

/// Record a flow and return its cached attribution plus the exact generation token its owner must release.
///
/// Non-blocking and hot-path safe: on a cache miss this marks the destination IP `Pending`, enqueues a [`FlowResolveRequest`] for the worker, and returns no context. A later flow to the same destination picks up the resolved answer.
pub fn note_flow(protocol: u8, local: SocketAddr, remote: SocketAddr) -> FlowObservation {
    let key = remote.ip();
    let request = FlowResolveRequest { protocol, local, remote };
    let mut guard = lock();
    let context = match guard.cache.get(&key) {
        Some(Resolution::Resolved(ctx)) => ctx.clone(),
        Some(Resolution::Pending) | None => None,
    };
    if !guard.cache.contains(&key) {
        guard.cache.put(key, Resolution::Pending);
    }
    if let Some(resolution) = guard.uid_cache.get(&request).copied() {
        return FlowObservation { context, token: FlowAttributionToken { request, generation: resolution.generation } };
    }
    let generation = NEXT_FLOW_GENERATION.fetch_add(1, Ordering::Relaxed);
    let token = FlowAttributionToken { request, generation };
    guard.uid_cache.put(
        request,
        UidResolution {
            generation,
            state: UidResolutionState::Pending,
            kind: FlowResolutionKind::Attribution,
            created_at: Instant::now(),
        },
    );
    if guard.pending.len() >= PENDING_CAPACITY
        && let Some(dropped) = guard.pending.pop_front()
        && guard.uid_cache.peek(&dropped.request).is_some_and(|entry| entry.generation == dropped.generation)
    {
        guard.uid_cache.pop(&dropped.request);
    }
    guard.pending.push_back(FlowResolutionJob { request, generation, kind: FlowResolutionKind::Attribution });
    drop(guard);
    pending_signal().notify_one();
    FlowObservation { context, token }
}

/// Ensure an exact tuple has an asynchronous UID resolution request without
/// creating or updating destination-level app attribution.
///
/// Repeated calls reuse the bounded full-5-tuple UID cache and therefore do not
/// enqueue a second Android lookup within [`ADMISSION_ABSOLUTE_TTL`]. Cache hits
/// never renew that security bound: the exact tuple is forcibly re-resolved
/// after five seconds in case the kernel assigned it to a different socket
/// owner. The cache is also bounded by LRU and cleared at session shutdown.
pub fn request_uid_admission(protocol: u8, local: SocketAddr, remote: SocketAddr) -> FlowAttributionToken {
    let request = FlowResolveRequest { protocol, local, remote };
    request_uid_admission_at(request, Instant::now())
}

fn request_uid_admission_at(request: FlowResolveRequest, now: Instant) -> FlowAttributionToken {
    let mut guard = lock();
    remove_expired_admission(&mut guard, request, now);
    if let Some(entry) = guard.uid_cache.get(&request) {
        return FlowAttributionToken { request, generation: entry.generation };
    }
    let generation = NEXT_FLOW_GENERATION.fetch_add(1, Ordering::Relaxed);
    guard.uid_cache.put(
        request,
        UidResolution {
            generation,
            state: UidResolutionState::Pending,
            kind: FlowResolutionKind::AdmissionOnly,
            created_at: now,
        },
    );
    if guard.pending.len() >= PENDING_CAPACITY
        && let Some(dropped) = guard.pending.pop_front()
        && guard.uid_cache.peek(&dropped.request).is_some_and(|entry| entry.generation == dropped.generation)
    {
        guard.uid_cache.pop(&dropped.request);
    }
    guard.pending.push_back(FlowResolutionJob { request, generation, kind: FlowResolutionKind::AdmissionOnly });
    drop(guard);
    pending_signal().notify_one();
    FlowAttributionToken { request, generation }
}

/// Read only this registration's UID; a reused tuple never supplies its successor's verdict.
#[must_use]
pub fn lookup_registered_flow_uid(token: &FlowAttributionToken) -> FlowUidLookup {
    let mut guard = lock();
    remove_expired_admission(&mut guard, token.request, Instant::now());
    match guard.uid_cache.get(&token.request) {
        Some(entry) if entry.generation == token.generation => match entry.state {
            UidResolutionState::Pending => FlowUidLookup::Pending,
            UidResolutionState::Resolved(uid) => FlowUidLookup::Resolved(uid),
        },
        Some(_) | None => FlowUidLookup::Missing,
    }
}

/// Read the asynchronous UID-resolution state for one complete flow tuple.
#[must_use]
pub fn lookup_flow_uid(protocol: u8, local: SocketAddr, remote: SocketAddr) -> FlowUidLookup {
    let request = FlowResolveRequest { protocol, local, remote };
    lookup_flow_uid_at(request, Instant::now())
}

fn lookup_flow_uid_at(request: FlowResolveRequest, now: Instant) -> FlowUidLookup {
    let mut guard = lock();
    remove_expired_admission(&mut guard, request, now);
    match guard.uid_cache.get(&request) {
        Some(UidResolution { state: UidResolutionState::Pending, .. }) => FlowUidLookup::Pending,
        Some(UidResolution { state: UidResolutionState::Resolved(uid), .. }) => FlowUidLookup::Resolved(*uid),
        None => FlowUidLookup::Missing,
    }
}

fn remove_expired_admission(guard: &mut State, request: FlowResolveRequest, now: Instant) {
    let expired = guard.uid_cache.peek(&request).is_some_and(|entry| {
        entry.kind == FlowResolutionKind::AdmissionOnly
            && now.saturating_duration_since(entry.created_at) >= ADMISSION_ABSOLUTE_TTL
    });
    if expired {
        guard.uid_cache.pop(&request);
        guard.pending.retain(|job| job.request != request);
    }
}

/// Store the UID result produced by the background JNI worker.
pub fn store_uid_resolution(job: &FlowResolutionJob, uid: Option<u32>) {
    let mut guard = lock();
    let current = guard.uid_cache.peek(&job.request).copied();
    if let Some(current) = current
        && current.generation == job.generation
        && current.state == UidResolutionState::Pending
    {
        guard.uid_cache.put(
            job.request,
            UidResolution {
                generation: job.generation,
                state: UidResolutionState::Resolved(uid),
                kind: job.kind,
                created_at: current.created_at,
            },
        );
    }
}

/// Store destination attribution only while the originating flow generation is current.
pub fn store_flow_resolution(job: &FlowResolutionJob, context: Option<FlowAppContext>) {
    let mut guard = lock();
    if guard.uid_cache.peek(&job.request).is_some_and(|entry| entry.generation == job.generation) {
        guard.cache.put(job.request.key(), Resolution::Resolved(context));
    }
}

/// Read-only attribution lookup for a destination IP (the policy-layer entry
/// point). Never enqueues. Returns `None` on miss or on the conservative
/// `Resolved(None)` verdict.
pub fn lookup_flow(dest_ip: IpAddr) -> Option<FlowAppContext> {
    let mut guard = lock();
    match guard.cache.get(&dest_ip) {
        Some(Resolution::Resolved(ctx)) => ctx.clone(),
        Some(Resolution::Pending) | None => None,
    }
}

/// First attribution found across `targets` — the policy layer holds a candidate
/// IP set for an authority and the flow used one of them.
pub fn lookup_flow_for_targets(targets: &[SocketAddr]) -> Option<FlowAppContext> {
    let mut guard = lock();
    for target in targets {
        if let Some(Resolution::Resolved(Some(ctx))) = guard.cache.get(&target.ip()) {
            return Some(ctx.clone());
        }
    }
    None
}

#[cfg(test)]
fn store_resolution(dest_ip: IpAddr, context: Option<FlowAppContext>) {
    lock().cache.put(dest_ip, Resolution::Resolved(context));
}

/// Release one exact flow registration if its generation is still current.
///
/// The destination-level attribution remains cached while any other exact flow to that IP is registered.
pub fn evict_flow(token: FlowAttributionToken) -> bool {
    let mut guard = lock();
    if guard.uid_cache.peek(&token.request).is_none_or(|entry| entry.generation != token.generation) {
        return false;
    }
    guard.uid_cache.pop(&token.request);
    guard.pending.retain(|job| job.request != token.request || job.generation != token.generation);
    let dest_ip = token.request.key();
    if !guard.uid_cache.iter().any(|(request, _)| request.key() == dest_ip) {
        guard.cache.pop(&dest_ip);
    }
    true
}

/// Clear all cached attributions and pending requests.
pub fn clear() {
    lock().clear();
}

/// Pop the next pending resolve request, blocking up to `timeout` for one to
/// arrive. Returns `None` on timeout. Drained by the background worker.
pub fn pop_pending_request(timeout: Duration) -> Option<FlowResolutionJob> {
    let mut guard = lock();
    if let Some(request) = guard.pending.pop_front() {
        return Some(request);
    }
    let (mut guard, _timed_out) =
        pending_signal().wait_timeout(guard, timeout).expect("flow-app-attribution state poisoned");
    guard.pending.pop_front()
}

/// Pop work only while `generation` is still the active attribution session.
/// A retired worker therefore cannot consume requests queued for a replacement
/// worker after re-registration.
pub fn pop_pending_request_for_session(
    generation: AttributionGeneration,
    timeout: Duration,
) -> Option<FlowResolutionJob> {
    let mut guard = lock();
    if guard.generation != generation.0 {
        return None;
    }
    if let Some(request) = guard.pending.pop_front() {
        return Some(request);
    }
    let (mut guard, _timed_out) =
        pending_signal().wait_timeout(guard, timeout).expect("flow-app-attribution state poisoned");
    if guard.generation != generation.0 {
        return None;
    }
    guard.pending.pop_front()
}

/// Remove the queued generation-stamped job for one exact request without consuming unrelated work.
#[must_use]
pub fn take_pending_request(request: FlowResolveRequest) -> Option<FlowResolutionJob> {
    let mut guard = lock();
    let index = guard.pending.iter().position(|job| job.request == request)?;
    guard.pending.remove(index)
}

/// Number of buffered pending requests (diagnostics / tests).
#[must_use]
pub fn pending_len() -> usize {
    lock().pending.len()
}

/// Begin an attribution session, clearing any prior state and returning a fresh
/// [`AttributionGeneration`]. Pair with [`end_attribution_session_if`].
pub fn begin_attribution_session() -> AttributionGeneration {
    let generation = NEXT_GENERATION.fetch_add(1, Ordering::Relaxed);
    let mut guard = lock();
    guard.clear();
    guard.generation = generation;
    AttributionGeneration(generation)
}

/// End the attribution session and clear state **only if** the stored generation
/// still matches `generation`. Returns `true` when cleared, `false` when a newer
/// session has superseded this one (a safe no-op).
pub fn end_attribution_session_if(generation: AttributionGeneration) -> bool {
    let mut guard = lock();
    if guard.generation == generation.0 {
        guard.clear();
        guard.generation = 0;
        true
    } else {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, SocketAddrV4};
    use std::sync::Mutex as StdMutex;

    // Serializes tests — the cache is process-global.
    static TEST_GUARD: StdMutex<()> = StdMutex::new(());

    fn sock(a: u8, b: u8, c: u8, d: u8, port: u16) -> SocketAddr {
        SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(a, b, c, d), port))
    }

    #[test]
    fn note_flow_miss_marks_pending_and_enqueues() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let remote = sock(93, 184, 216, 34, 443);
        let local = sock(10, 0, 0, 2, 50000);

        assert!(note_flow(6, local, remote).context.is_none());
        assert_eq!(pending_len(), 1);

        // The exact 5-tuple is deduplicated while pending.
        assert!(note_flow(6, local, remote).context.is_none());
        assert_eq!(pending_len(), 1);

        let job = pop_pending_request(Duration::from_millis(10)).expect("a pending request");
        assert_eq!(job.request().protocol, 6);
        assert_eq!(job.request().remote, remote);
        assert_eq!(job.request().key(), remote.ip());
    }

    #[test]
    fn uid_resolution_is_cached_by_complete_flow_tuple() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let remote = sock(93, 184, 216, 34, 443);
        let first_local = sock(10, 0, 0, 2, 50000);
        let second_local = sock(10, 0, 0, 2, 50001);

        let _first = note_flow(6, first_local, remote);
        let _second = note_flow(6, second_local, remote);
        assert_eq!(pending_len(), 2, "parallel flows to one destination need independent UID lookups");
        assert_eq!(lookup_flow_uid(6, first_local, remote), FlowUidLookup::Pending);

        let first = pop_pending_request(Duration::from_millis(10)).expect("first request");
        store_uid_resolution(&first, Some(10_123));
        assert_eq!(lookup_flow_uid(6, first_local, remote), FlowUidLookup::Resolved(Some(10_123)));
        assert_eq!(lookup_flow_uid(6, second_local, remote), FlowUidLookup::Pending);
    }

    #[test]
    fn admission_only_reuses_exact_tuple_without_destination_attribution() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let local = sock(10, 0, 0, 2, 53_000);
        let remote = sock(198, 18, 0, 10, 53);

        request_uid_admission(17, local, remote);
        request_uid_admission(17, local, remote);
        assert_eq!(pending_len(), 1, "the exact tuple must resolve only once");

        let job = pop_pending_request(Duration::from_millis(10)).expect("admission request");
        assert_eq!(job.kind(), FlowResolutionKind::AdmissionOnly);
        store_uid_resolution(&job, Some(10_123));
        assert_eq!(lookup_flow_uid(17, local, remote), FlowUidLookup::Resolved(Some(10_123)));
        assert!(lookup_flow(remote.ip()).is_none(), "admission-only work must not create destination attribution");

        request_uid_admission(17, local, remote);
        assert_eq!(pending_len(), 0, "a resolved exact tuple must not trigger a second lookup");
    }

    #[test]
    fn absolute_ttl_forces_re_resolution_when_another_uid_reuses_exact_tuple() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let local = sock(10, 0, 0, 2, 53_001);
        let remote = sock(198, 18, 0, 10, 53);
        let request = FlowResolveRequest { protocol: 17, local, remote };
        let started = Instant::now();
        request_uid_admission_at(request, started);
        let allowed = pop_pending_request(Duration::ZERO).expect("allowed owner lookup");
        store_uid_resolution(&allowed, Some(10_123));

        let last_cache_hit = started + ADMISSION_ABSOLUTE_TTL - Duration::from_millis(1);
        assert_eq!(lookup_flow_uid_at(request, last_cache_hit), FlowUidLookup::Resolved(Some(10_123)));
        assert_eq!(
            lookup_flow_uid_at(request, started + ADMISSION_ABSOLUTE_TTL),
            FlowUidLookup::Missing,
            "cache hits must not renew the absolute tuple-owner security bound"
        );

        let rebound_at = started + ADMISSION_ABSOLUTE_TTL;
        request_uid_admission_at(request, rebound_at);
        let denied = pop_pending_request(Duration::ZERO).expect("rebound owner lookup");
        assert_ne!(denied.generation, allowed.generation);
        store_uid_resolution(&denied, Some(20_000));
        assert_eq!(lookup_flow_uid_at(request, rebound_at), FlowUidLookup::Resolved(Some(20_000)));
        assert_eq!(pending_len(), 0);
    }

    #[test]
    fn late_uid_resolution_does_not_resurrect_evicted_flow() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let local = sock(10, 0, 0, 2, 50000);
        let remote = sock(93, 184, 216, 34, 443);
        let observation = note_flow(6, local, remote);
        let job = pop_pending_request(Duration::from_millis(10)).expect("pending job");
        assert!(evict_flow(observation.token));
        let current = note_flow(6, local, remote);
        store_uid_resolution(&job, Some(10_123));
        store_flow_resolution(&job, Some(FlowAppContext::new("com.stale", 1)));

        assert_eq!(lookup_flow_uid(6, local, remote), FlowUidLookup::Pending);
        assert!(lookup_flow(remote.ip()).is_none());
        assert!(evict_flow(current.token));
    }

    #[test]
    fn exact_cleanup_preserves_parallel_flow_to_same_destination() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let remote = sock(203, 0, 113, 7, 443);
        let first = note_flow(6, sock(10, 0, 0, 2, 50000), remote);
        let second = note_flow(6, sock(10, 0, 0, 3, 50000), remote);
        store_resolution(remote.ip(), Some(FlowAppContext::new("com.example", 1)));

        assert!(evict_flow(first.token));
        assert!(lookup_flow(remote.ip()).is_some(), "the other exact flow still owns the destination entry");
        assert_eq!(lookup_flow_uid(6, second.token.request().local, remote), FlowUidLookup::Pending);
    }

    #[test]
    fn stale_cleanup_cannot_remove_reused_tuple_generation() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let local = sock(10, 0, 0, 2, 50000);
        let remote = sock(203, 0, 113, 7, 443);
        let stale = note_flow(6, local, remote).token;
        let stale_request = stale.request;
        let stale_generation = stale.generation;
        assert!(evict_flow(stale));
        let current = note_flow(6, local, remote).token;

        let replayed_stale = FlowAttributionToken { request: stale_request, generation: stale_generation };
        assert!(!evict_flow(replayed_stale));
        assert_eq!(lookup_flow_uid(6, local, remote), FlowUidLookup::Pending);
        assert!(evict_flow(current));
    }

    #[test]
    fn store_then_lookup_returns_context() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let remote = sock(1, 1, 1, 1, 443);
        let _flow = note_flow(6, sock(10, 0, 0, 2, 40000), remote);

        store_resolution(remote.ip(), Some(FlowAppContext::new("com.example.app", 42)));

        let ctx = lookup_flow(remote.ip()).expect("resolved context");
        assert_eq!(ctx.package(), "com.example.app");
        assert_eq!(ctx.version_code(), 42);
        // App attribution remains destination-cached, while the distinct 5-tuple
        // still queues its own UID lookup for admission.
        let before = pending_len();
        assert_eq!(note_flow(6, sock(10, 0, 0, 2, 40001), remote).context.map(|c| c.version_code()), Some(42));
        assert_eq!(pending_len(), before + 1);
    }

    #[test]
    fn resolved_none_is_conservative_unattributed() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let remote = sock(8, 8, 8, 8, 853);
        store_resolution(remote.ip(), None);
        assert!(lookup_flow(remote.ip()).is_none());
        assert!(lookup_flow_for_targets(&[remote]).is_none());
    }

    #[test]
    fn lookup_for_targets_finds_any_resolved_member() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let a = sock(203, 0, 113, 1, 443);
        let b = sock(203, 0, 113, 2, 443);
        store_resolution(b.ip(), Some(FlowAppContext::new("com.example.b", 7)));
        let ctx = lookup_flow_for_targets(&[a, b]).expect("resolved member");
        assert_eq!(ctx.package(), "com.example.b");
    }

    #[test]
    fn evict_drops_the_entry() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let remote = sock(198, 51, 100, 7, 443);
        store_resolution(remote.ip(), Some(FlowAppContext::new("com.example.c", 1)));
        assert!(lookup_flow(remote.ip()).is_some());
        let token = note_flow(6, sock(10, 0, 0, 2, 40000), remote).token;
        evict_flow(token);
        assert!(lookup_flow(remote.ip()).is_none());
    }

    #[test]
    fn pop_pending_times_out_when_empty() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        assert!(pop_pending_request(Duration::from_millis(1)).is_none());
    }

    #[test]
    fn session_generation_guard_blocks_stale_teardown() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let stale = begin_attribution_session();
        let current = begin_attribution_session();

        // A stale end from a superseded session does not clear current state.
        store_resolution(IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4)), Some(FlowAppContext::new("com.x", 1)));
        assert!(!end_attribution_session_if(stale));
        assert!(lookup_flow(IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))).is_some());

        // The current generation clears it.
        assert!(end_attribution_session_if(current));
        assert!(lookup_flow(IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))).is_none());
    }

    #[test]
    fn stale_worker_cannot_consume_replacement_session_request() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let stale = begin_attribution_session();
        let current = begin_attribution_session();
        let local = sock(10, 0, 0, 2, 51_000);
        let remote = sock(93, 184, 216, 34, 443);
        let _observation = note_flow(6, local, remote);

        assert!(pop_pending_request_for_session(stale, Duration::ZERO).is_none());
        assert!(pop_pending_request_for_session(current, Duration::ZERO).is_some());
        assert!(end_attribution_session_if(current));
    }

    #[test]
    fn unknown_generation_token_is_a_safe_noop() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let current = begin_attribution_session();
        assert!(!end_attribution_session_if(AttributionGeneration::from_token(0)));
        assert!(end_attribution_session_if(current));
    }
}
