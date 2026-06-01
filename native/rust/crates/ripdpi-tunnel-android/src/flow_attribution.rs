//! JNI bridge that pushes per-flow app-attribution requests up to Kotlin.
//!
//! The tun2socks `.so` is the only place that knows a flow's originating app
//! 5-tuple, but it cannot resolve the owning package (that needs Android APIs).
//! On flow birth the TUN core enqueues a [`FlowResolveRequest`] into the
//! process-global `ripdpi-flow-app-attribution` queue (a non-blocking,
//! hot-path-safe push). A **dedicated background worker thread** owned by this
//! module drains the queue and, for each request, calls a Kotlin bridge method
//! `noteFlow(int, String, int, String, int)` via JNI — entirely off the per-flow
//! / per-packet path (see `.claude/rules/vpnservice-protect-invariant.md`).
//! Kotlin resolves UID → package → version and owns the attribution map; the
//! resolved result never crosses back into Rust, so no package identity is held
//! natively.
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
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

use jni::objects::{JObject, JValue};
use jni::refs::Global;
use jni::{EnvUnowned, JavaVM, Outcome};

use ripdpi_flow_app_attribution::{
    AttributionGeneration, FlowResolveRequest, begin_attribution_session, end_attribution_session_if,
    pop_pending_request, store_resolution,
};

/// How long the worker blocks waiting for a queued request before re-checking the
/// stop flag. Bounds worker shutdown latency.
const WORKER_POLL: Duration = Duration::from_millis(250);

/// Sink the worker forwards resolved-flow notifications into. Abstracted so the
/// worker drain logic is unit-testable without a live JVM.
trait FlowNotifier: Send {
    fn note(&self, request: FlowResolveRequest);
}

/// Calls the Kotlin flow-attribution bridge's `noteFlow` over JNI. Holds the
/// `JavaVM` and a global ref to the bridge object so it can attach the worker
/// thread on demand. Mirrors `ripdpi-android-vpn-protect-adapter`'s
/// `JniProtectCallback`.
struct JniFlowNotifier {
    vm: JavaVM,
    bridge: Global<JObject<'static>>,
}

// SAFETY: JavaVM is Send+Sync and Global<JObject<'static>> keeps the Java object
// alive across threads. note() only attaches the current thread and reads the
// global ref.
unsafe impl Send for JniFlowNotifier {}
// SAFETY: see above; no interior mutability is shared without JNI's own guards.
unsafe impl Sync for JniFlowNotifier {}

impl FlowNotifier for JniFlowNotifier {
    fn note(&self, request: FlowResolveRequest) {
        let local_ip = request.local.ip().to_string();
        let remote_ip = request.remote.ip().to_string();
        let protocol = i32::from(request.protocol);
        let local_port = i32::from(request.local.port());
        let remote_port = i32::from(request.remote.port());

        let result: Result<(), jni::errors::Error> = self.vm.attach_current_thread(|env| -> jni::errors::Result<()> {
            let local = env.new_string(&local_ip)?;
            let remote = env.new_string(&remote_ip)?;
            env.call_method(
                &self.bridge,
                jni::jni_str!("noteFlow"),
                jni::jni_sig!("(ILjava/lang/String;ILjava/lang/String;I)V"),
                &[
                    JValue::Int(protocol),
                    JValue::Object(&local),
                    JValue::Int(local_port),
                    JValue::Object(&remote),
                    JValue::Int(remote_port),
                ],
            )?;
            Ok(())
        });

        if let Err(err) = result {
            // Off the hot path; a failed attribution just leaves the flow
            // unattributed (conservative). Never propagate across the worker loop.
            tracing::warn!("flow-attribution noteFlow JNI call failed: {err}");
        }
    }
}

/// Drain one queued request (blocking up to [`WORKER_POLL`]) and forward it to
/// `notifier`, then mark its destination resolved so it is not re-notified.
/// Returns `true` if a request was processed.
fn drain_once(notifier: &dyn FlowNotifier) -> bool {
    match pop_pending_request(WORKER_POLL) {
        Some(request) => {
            notifier.note(request);
            // Mark the destination "notified". The authoritative attribution lives
            // in the Kotlin map; the native cache only needs the dedupe marker.
            store_resolution(request.key(), None);
            true
        }
        None => false,
    }
}

fn worker_loop(notifier: Box<dyn FlowNotifier>, stop: Arc<AtomicBool>) {
    while !stop.load(Ordering::Acquire) {
        drain_once(notifier.as_ref());
    }
}

struct Registered {
    generation: AttributionGeneration,
    stop: Arc<AtomicBool>,
    join: Option<JoinHandle<()>>,
}

static REGISTERED: Mutex<Option<Registered>> = Mutex::new(None);

fn registered() -> std::sync::MutexGuard<'static, Option<Registered>> {
    REGISTERED.lock().expect("flow-attribution registration lock poisoned")
}

fn stop_worker(mut reg: Registered) {
    reg.stop.store(true, Ordering::Release);
    if let Some(join) = reg.join.take() {
        let _ = join.join();
    }
}

/// Spawn the worker that drives `notifier`, replacing any prior registration, and
/// return the session [`AttributionGeneration`].
fn register_with_notifier(notifier: Box<dyn FlowNotifier>) -> AttributionGeneration {
    // Stop a prior worker (if any) before starting a new session. Taken out of the
    // lock so the join does not hold REGISTERED.
    let previous = registered().take();
    if let Some(previous) = previous {
        stop_worker(previous);
    }

    let generation = begin_attribution_session();
    let stop = Arc::new(AtomicBool::new(false));
    let worker_stop = Arc::clone(&stop);
    let join = std::thread::Builder::new()
        .name("ripdpi-flow-attribution".to_owned())
        .spawn(move || worker_loop(notifier, worker_stop))
        .expect("spawn flow-attribution worker");
    *registered() = Some(Registered { generation, stop, join: Some(join) });
    generation
}

/// Register the Kotlin flow-attribution bridge and start the worker.
///
/// `bridge` is a global ref to a Kotlin object exposing
/// `noteFlow(int protocol, String localIp, int localPort, String remoteIp, int remotePort)`.
/// Returns the generation token Kotlin threads back to
/// [`unregister_flow_attribution`].
fn register_flow_attribution(vm: &JavaVM, bridge: Global<JObject<'static>>) -> i64 {
    // SAFETY: the JavaVM pointer is valid for the life of the process (held by the
    // JNI runtime); `from_raw` only copies the pointer.
    let vm = unsafe { JavaVM::from_raw(vm.get_raw()) };
    let generation = register_with_notifier(Box::new(JniFlowNotifier { vm, bridge }));
    tracing::info!(generation = generation.token(), "flow-attribution bridge registered via JNI");
    generation.token() as i64
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
        stop_worker(reg);
        end_attribution_session_if(generation);
        tracing::info!(generation = generation.token(), "flow-attribution bridge unregistered");
    } else {
        tracing::info!(token, "flow-attribution unregister ignored (stale token or no registration)");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
    use std::sync::Mutex as StdMutex;

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
        fn note(&self, request: FlowResolveRequest) {
            self.seen.lock().expect("seen lock").push(request);
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
        clear();
        let remote = sock(93, 184, 216, 34, 443);
        let local = sock(10, 0, 0, 2, 51000);
        // A flow birth enqueues a request and leaves the dest unresolved.
        assert!(note_flow(6, local, remote).is_none());

        let notifier = RecordingNotifier { seen: StdMutex::new(Vec::new()) };
        assert!(drain_once(&notifier));

        let seen = notifier.seen.lock().expect("seen");
        assert_eq!(seen.len(), 1);
        assert_eq!(seen[0].remote, remote);
        // After draining, the destination carries the "notified" marker so a later
        // flow does not re-enqueue.
        assert!(lookup_flow(IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34))).is_none());
        assert!(note_flow(6, sock(10, 0, 0, 2, 51001), remote).is_none());
        assert_eq!(pending_len(), 0);
    }

    #[test]
    fn drain_once_returns_false_on_empty_queue() {
        let _g = TEST_GUARD.lock().expect("test guard");
        clear();
        let notifier = RecordingNotifier { seen: StdMutex::new(Vec::new()) };
        assert!(!drain_once(&notifier));
        assert!(notifier.seen.lock().expect("seen").is_empty());
    }
}
