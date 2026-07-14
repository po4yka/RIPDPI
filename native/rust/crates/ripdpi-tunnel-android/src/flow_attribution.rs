//! JNI bridge that pushes per-flow app-attribution requests up to Kotlin.
//!
//! The tun2socks `.so` is the only place that knows a flow's originating app
//! 5-tuple, but it cannot resolve the owning package (that needs Android APIs).
//! On flow birth the TUN core enqueues a [`FlowResolveRequest`] into the
//! process-global `ripdpi-flow-app-attribution` queue (a non-blocking,
//! hot-path-safe push). A **dedicated background worker thread** owned by this
//! module drains the queue and, for each request, calls a Kotlin bridge method
//! `noteFlow(int, String, int, String, int): int` via JNI — entirely off the per-flow
//! / per-packet path (see `.claude/rules/vpnservice-protect-invariant.md`).
//! Kotlin resolves UID → package → version and owns the attribution map; only
//! the numeric UID returns to the native full-5-tuple admission cache, while no
//! package identity is held natively.
//!
//! Registration mirrors `ripdpi-android-vpn-protect-adapter`: Kotlin calls
//! [`register_flow_attribution`] at tunnel start (receiving a generation token)
//! and [`unregister_flow_attribution`] at stop. The generation guard
//! (`begin_attribution_session` / `end_attribution_session_if`) makes a stale
//! unregister from a superseded session a safe no-op.
//!
//! This lives in `ripdpi-tunnel-android` (an L8 JNI crate) rather than a separate
//! adapter crate because flow attribution is only ever driven from the tun2socks
//! `.so`, and `NATIVE_RUST.md` restricts `jni` to the L8 allowlist.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::thread::JoinHandle;
use std::time::Duration;

use android_support::SharedJvm;
use jni::objects::{JObject, JValue};
use jni::refs::Global;
use jni::{EnvUnowned, JavaVM, Outcome};

use ripdpi_flow_app_attribution::{
    AttributionGeneration, FlowResolveRequest, begin_attribution_session, end_attribution_session_if,
    pop_pending_request_for_session, store_flow_resolution, store_uid_resolution,
};

/// How long the worker blocks waiting for a queued request before re-checking the
/// stop flag. Bounds worker shutdown latency.
const WORKER_POLL: Duration = Duration::from_millis(250);

/// Sink the worker forwards resolved-flow notifications into. Abstracted so the
/// worker drain logic is unit-testable without a live JVM.
trait FlowNotifier: Send {
    fn note(&self, request: FlowResolveRequest) -> Option<u32>;
}

/// Calls the Kotlin flow-attribution bridge's `noteFlow` over JNI. Holds the
/// `JavaVM` and a global ref to the bridge object so it can attach the worker
/// thread on demand. Mirrors `ripdpi-android-vpn-protect-adapter`'s
/// `JniProtectCallback`.
struct JniFlowNotifier {
    vm: SharedJvm,
    bridge: Global<JObject<'static>>,
}

// `JniFlowNotifier` auto-derives `Send + Sync`: `SharedJvm` (`Arc<JavaVM>`) and
// `Global<JObject<'static>>` are both `Send + Sync` in jni 0.22. Relying on the
// auto-derive rather than a manual `unsafe impl` keeps the compiler tripwire — a
// future non-thread-safe field breaks the `assert_send`/`assert_sync` guard below
// instead of being silently forced thread-safe.

impl FlowNotifier for JniFlowNotifier {
    fn note(&self, request: FlowResolveRequest) -> Option<u32> {
        let local_ip = request.local.ip().to_string();
        let remote_ip = request.remote.ip().to_string();
        let protocol = i32::from(request.protocol);
        let local_port = i32::from(request.local.port());
        let remote_port = i32::from(request.remote.port());

        // Permanent attach (NOT for_scope): unlike the one-shot readiness / per-socket
        // protect callbacks, this is a long-lived dedicated worker thread that calls
        // noteFlow once per drained flow. The first call attaches; subsequent calls hit
        // the cheap "already attached" TLS path instead of re-attaching+detaching every
        // flow (the jni 0.22 docs warn scoped attach is expensive on a reused thread).
        // Android owns the process-wide JVM for the lifetime of this library. A retired
        // worker may finish an in-flight callback asynchronously, then its TLS attach
        // guard detaches at thread exit.
        let result: Result<i32, jni::errors::Error> =
            self.vm.attach_current_thread(|env| -> jni::errors::Result<i32> {
                let local = env.new_string(&local_ip)?;
                let remote = env.new_string(&remote_ip)?;
                env.call_method(
                    &self.bridge,
                    jni::jni_str!("noteFlow"),
                    jni::jni_sig!("(ILjava/lang/String;ILjava/lang/String;I)I"),
                    &[
                        JValue::Int(protocol),
                        JValue::Object(&local),
                        JValue::Int(local_port),
                        JValue::Object(&remote),
                        JValue::Int(remote_port),
                    ],
                )?
                .i()
            });

        match result {
            Ok(uid) => u32::try_from(uid).ok(),
            Err(err) => {
                // Off the hot path; a failed attribution just leaves the flow
                // unattributed (conservative). Never propagate across the worker loop.
                tracing::warn!("flow-attribution noteFlow JNI call failed: {err}");
                None
            }
        }
    }
}

/// Drain one queued request (blocking up to [`WORKER_POLL`]) and forward it to
/// `notifier`, then mark its destination resolved so it is not re-notified.
/// Returns `true` if a request was processed.
fn drain_once(notifier: &dyn FlowNotifier, generation: AttributionGeneration) -> bool {
    match pop_pending_request_for_session(generation, WORKER_POLL) {
        Some(job) => {
            let request = job.request();
            let uid = notifier.note(request);
            store_uid_resolution(&job, uid);
            // Mark the destination "notified". The authoritative attribution lives
            // in the Kotlin map; the native cache only needs the dedupe marker.
            store_flow_resolution(&job, None);
            true
        }
        None => false,
    }
}

fn worker_loop(notifier: Box<dyn FlowNotifier>, stop: Arc<AtomicBool>, generation: AttributionGeneration) {
    while !stop.load(Ordering::Acquire) {
        drain_once(notifier.as_ref(), generation);
    }
}

struct Registered {
    generation: AttributionGeneration,
    stop: Arc<AtomicBool>,
    join: Option<JoinHandle<()>>,
}

static REGISTERED: Mutex<Option<Registered>> = Mutex::new(None);

fn registered() -> std::sync::MutexGuard<'static, Option<Registered>> {
    // The guarded value is a plain `Option<Registered>` whose consistency is owned
    // by the generation guard, not the lock; a panic on another thread cannot leave
    // it in a torn state. Recover from poison rather than panicking in production
    // (rust-discipline panic policy; llm-rust-prompts.md `.expect` outside tests).
    REGISTERED.lock().unwrap_or_else(PoisonError::into_inner)
}

fn retire_worker(mut reg: Registered) {
    end_attribution_session_if(reg.generation);
    reg.stop.store(true, Ordering::Release);
    // Dropping a JoinHandle detaches the worker. It may be inside a blocking JNI
    // callback, so unregister/re-register must not wait for it. The generation
    // check prevents it from draining any replacement session's queue.
    drop(reg.join.take());
}

/// Spawn the worker that drives `notifier`, replacing any prior registration, and
/// return the session [`AttributionGeneration`].
///
/// Returns an error if the worker thread cannot be spawned; in that case the
/// just-begun session is rolled back via [`end_attribution_session_if`] so no
/// orphaned generation is left behind.
fn register_with_notifier(notifier: Box<dyn FlowNotifier>) -> std::io::Result<AttributionGeneration> {
    let mut slot = registered();
    let previous = slot.take();
    if let Some(previous) = previous {
        retire_worker(previous);
    }

    let generation = begin_attribution_session();
    let stop = Arc::new(AtomicBool::new(false));
    let worker_stop = Arc::clone(&stop);
    let join = match std::thread::Builder::new()
        .name("ripdpi-flow-attribution".to_owned())
        .spawn(move || worker_loop(notifier, worker_stop, generation))
    {
        Ok(join) => join,
        Err(err) => {
            // Roll back the session we just began so a failed register does not
            // leave a live generation with no worker draining it.
            end_attribution_session_if(generation);
            return Err(err);
        }
    };
    *slot = Some(Registered { generation, stop, join: Some(join) });
    Ok(generation)
}

/// Register the Kotlin flow-attribution bridge and start the worker.
///
/// `bridge` is a global ref to a Kotlin object exposing
/// `int noteFlow(int protocol, String localIp, int localPort, String remoteIp, int remotePort)`.
/// Returns the generation token Kotlin threads back to
/// [`unregister_flow_attribution`].
fn register_flow_attribution(vm: &JavaVM, bridge: Global<JObject<'static>>) -> i64 {
    // The single auditable `JavaVM::from_raw` site lives in `SharedJvm::new`.
    let vm = SharedJvm::new(vm);
    match register_with_notifier(Box::new(JniFlowNotifier { vm, bridge })) {
        Ok(generation) => {
            tracing::info!(generation = generation.token(), "flow-attribution bridge registered via JNI");
            generation.token() as i64
        }
        Err(err) => {
            // Map the spawn failure onto the existing `0` failed-register sentinel,
            // which `unregister_flow_attribution` treats as a safe no-op.
            tracing::error!("flow-attribution worker spawn failed: {err}");
            0
        }
    }
}

/// JNI entry for `jniRegisterFlowAttribution`: capture the `JavaVM` and a global
/// ref to the Kotlin bridge, then register. Returns the generation token, or `0`
/// on error / panic (Kotlin threads it back to [`unregister_flow_attribution`]).
pub(crate) fn register_from_jni(mut env: EnvUnowned<'_>, bridge: JObject<'_>) -> i64 {
    match env
        .with_env(|env| -> jni::errors::Result<i64> {
            let vm = env.get_java_vm()?;
            let bridge: Global<JObject<'static>> = env.new_global_ref(bridge)?;
            Ok(register_flow_attribution(&vm, bridge))
        })
        .into_outcome()
    {
        Outcome::Ok(token) => token,
        Outcome::Err(err) => {
            tracing::error!("flow-attribution registration failed: {err}");
            0
        }
        Outcome::Panic(payload) => {
            let msg = payload
                .downcast_ref::<String>()
                .map(String::as_str)
                .or_else(|| payload.downcast_ref::<&str>().copied())
                .unwrap_or("unknown panic");
            tracing::error!("flow-attribution registration panicked: {msg}");
            0
        }
    }
}

/// Stop the worker and end the session **only if** the slot still carries `token`.
/// A stale token (superseded session) or `0` (failed register) is a safe no-op.
pub(crate) fn unregister_flow_attribution(token: i64) {
    let generation = AttributionGeneration::from_token(token as u64);
    let to_stop = {
        let mut guard = registered();
        if guard.as_ref().is_some_and(|reg| reg.generation == generation) { guard.take() } else { None }
    };
    if let Some(reg) = to_stop {
        retire_worker(reg);
        tracing::info!(generation = generation.token(), "flow-attribution bridge unregistered");
    } else {
        tracing::info!(token, "flow-attribution unregister ignored (stale token or no registration)");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
    use std::sync::{Condvar, Mutex as StdMutex, mpsc};
    use std::time::Instant;

    use ripdpi_flow_app_attribution::{clear, lookup_flow, note_flow, pending_len};

    // The attribution queue/cache is process-global; serialize the tests.
    static TEST_GUARD: StdMutex<()> = StdMutex::new(());

    fn sock(a: u8, b: u8, c: u8, d: u8, port: u16) -> SocketAddr {
        SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(a, b, c, d), port))
    }

    struct RecordingNotifier {
        seen: StdMutex<Vec<FlowResolveRequest>>,
    }

    impl FlowNotifier for RecordingNotifier {
        fn note(&self, request: FlowResolveRequest) -> Option<u32> {
            self.seen.lock().expect("seen lock").push(request);
            Some(10_123)
        }
    }

    const _: fn() = || {
        fn assert_send<T: Send>() {}
        fn assert_sync<T: Sync>() {}
        assert_send::<JniFlowNotifier>();
        assert_sync::<JniFlowNotifier>();
    };

    #[test]
    fn drain_once_forwards_the_request_and_marks_resolved() {
        let _g = TEST_GUARD.lock().expect("test guard");
        let generation = begin_attribution_session();
        let remote = sock(93, 184, 216, 34, 443);
        let local = sock(10, 0, 0, 2, 51000);
        // A flow birth enqueues a request and leaves the dest unresolved.
        assert!(note_flow(6, local, remote).context.is_none());

        let notifier = RecordingNotifier { seen: StdMutex::new(Vec::new()) };
        assert!(drain_once(&notifier, generation));

        let seen = notifier.seen.lock().expect("seen");
        assert_eq!(seen.len(), 1);
        assert_eq!(seen[0].remote, remote);
        // After draining, the exact tuple carries a UID verdict and does not re-enqueue.
        assert!(lookup_flow(IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34))).is_none());
        assert_eq!(
            ripdpi_flow_app_attribution::lookup_flow_uid(6, local, remote),
            ripdpi_flow_app_attribution::FlowUidLookup::Resolved(Some(10_123))
        );
        assert!(note_flow(6, local, remote).context.is_none());
        assert_eq!(pending_len(), 0);
        drop(seen);
        assert!(end_attribution_session_if(generation));
    }

    #[test]
    fn drain_once_returns_false_on_empty_queue() {
        let _g = TEST_GUARD.lock().expect("test guard");
        let generation = begin_attribution_session();
        let notifier = RecordingNotifier { seen: StdMutex::new(Vec::new()) };
        assert!(!drain_once(&notifier, generation));
        assert!(notifier.seen.lock().expect("seen").is_empty());
        assert!(end_attribution_session_if(generation));
    }

    struct BlockingNotifier {
        started: mpsc::SyncSender<()>,
        release: Arc<(StdMutex<bool>, Condvar)>,
        retired: mpsc::SyncSender<()>,
    }

    impl FlowNotifier for BlockingNotifier {
        fn note(&self, _request: FlowResolveRequest) -> Option<u32> {
            let _ = self.started.send(());
            let (released, wake) = &*self.release;
            let guard = released.lock().unwrap_or_else(PoisonError::into_inner);
            drop(wake.wait_while(guard, |released| !*released));
            Some(10_123)
        }
    }

    impl Drop for BlockingNotifier {
        fn drop(&mut self) {
            let _ = self.retired.send(());
        }
    }

    #[test]
    fn reregister_does_not_wait_for_blocked_notifier() {
        let _g = TEST_GUARD.lock().expect("test guard");
        if let Some(registration) = registered().take() {
            retire_worker(registration);
        }
        clear();
        let (started_tx, started_rx) = mpsc::sync_channel(1);
        let (retired_tx, retired_rx) = mpsc::sync_channel(1);
        let release = Arc::new((StdMutex::new(false), Condvar::new()));
        let first = register_with_notifier(Box::new(BlockingNotifier {
            started: started_tx,
            release: Arc::clone(&release),
            retired: retired_tx,
        }))
        .expect("register blocking notifier");
        let _observation = note_flow(6, sock(10, 0, 0, 2, 51_000), sock(93, 184, 216, 34, 443));
        started_rx.recv_timeout(Duration::from_secs(1)).expect("notifier entered callback");

        let started = Instant::now();
        let second = register_with_notifier(Box::new(RecordingNotifier { seen: StdMutex::new(Vec::new()) }))
            .expect("replace notifier");
        assert!(started.elapsed() < Duration::from_millis(500));
        assert_ne!(first, second);

        let (released, wake) = &*release;
        *released.lock().unwrap_or_else(PoisonError::into_inner) = true;
        wake.notify_all();
        retired_rx.recv_timeout(Duration::from_secs(1)).expect("retired worker exited");
        unregister_flow_attribution(second.token() as i64);
    }
}
