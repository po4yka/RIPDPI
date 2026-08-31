mod accept_loop;
mod client_job;
mod worker_pool;

use std::io;
use std::net::{TcpListener, TcpStream};
use std::sync::Arc as StdArc;

use ripdpi_proxy_runtime_adapter::model::runtime_api::DesyncExecutionEvidence;
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
use ripdpi_proxy_runtime_adapter::platform::listener as listener_platform;
#[cfg(any(target_os = "linux", target_os = "android"))]
use ripdpi_proxy_runtime_adapter::platform::root_helper::RootHelperGeneration;
use ripdpi_ws_transport_port::WsTransport;

use crate::process;

use self::accept_loop::run_accept_loop;
use super::config::RuntimeConfig;
use super::state::RuntimeState;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProxyRuntimeCleanupReceipt {
    forced_abort: bool,
    worker_panicked: bool,
    desync_execution_evidence: Vec<DesyncExecutionEvidence>,
    desync_execution_evidence_overflowed: bool,
    connection_refused_count: usize,
    duplicate_refusal_count: usize,
    poll_error_kind: Option<io::ErrorKind>,
}

impl ProxyRuntimeCleanupReceipt {
    pub fn clean(
        forced_abort: bool,
        worker_panicked: bool,
        desync_execution_evidence: Vec<DesyncExecutionEvidence>,
        desync_execution_evidence_overflowed: bool,
        connection_refused_count: usize,
        duplicate_refusal_count: usize,
        poll_error_kind: Option<io::ErrorKind>,
    ) -> Self {
        Self {
            forced_abort,
            worker_panicked,
            desync_execution_evidence,
            desync_execution_evidence_overflowed,
            connection_refused_count,
            duplicate_refusal_count,
            poll_error_kind,
        }
    }

    pub fn forced_abort(&self) -> bool {
        self.forced_abort
    }

    pub fn worker_panicked(&self) -> bool {
        self.worker_panicked
    }

    pub fn desync_execution_evidence(&self) -> &[DesyncExecutionEvidence] {
        &self.desync_execution_evidence
    }

    pub fn desync_execution_evidence_overflowed(&self) -> bool {
        self.desync_execution_evidence_overflowed
    }

    pub fn connection_refused_count(&self) -> usize {
        self.connection_refused_count
    }
    pub fn duplicate_refusal_count(&self) -> usize {
        self.duplicate_refusal_count
    }
    pub(crate) fn poll_error_kind(&self) -> Option<io::ErrorKind> {
        self.poll_error_kind
    }
}

pub(super) fn build_listener(config: &RuntimeConfig) -> io::Result<TcpListener> {
    listener_platform::bind_tcp_listener(RuntimeState::listener_bind_addr(config))
}

pub(super) fn run_proxy_with_listener_internal(
    config: RuntimeConfig,
    listener: TcpListener,
    control: Option<StdArc<EmbeddedProxyControl>>,
    ws_transport: StdArc<dyn WsTransport>,
) -> io::Result<ProxyRuntimeCleanupReceipt> {
    let mut config = config;
    RuntimeState::ensure_config_default_ttl(&mut config, listener_platform::detect_default_ttl)?;
    let _root_helper = RootHelperRegistration::for_config(&config);
    let embedded_candidate_runtime = control.is_some();
    let state = RuntimeState::new(config, control.clone(), ws_transport);
    let client_capacity = state.listener_client_capacity();
    let listener_addr = listener.local_addr()?;
    state.note_listener_started(listener_addr, client_capacity, state.listener_route_group_count());
    // Drain any autolearn events accumulated during policy load so that
    // telemetry reflects the initial state before any connections arrive.
    // The policy port's ServicesState::drop handles persistence on shutdown.
    state.drain_autolearn_events();

    // Candidate diagnostics runtimes are stage-owned: they must not start
    // fire-and-forget warmup or reprobe work that could outlive their
    // supervisor. The long-lived service runtime retains these background
    // optimizations.
    if !embedded_candidate_runtime {
        super::warmup::spawn_warmup_thread(state.clone());

        // Check for network identity changes and trigger a lightweight reprobe
        // if the network switched (e.g. WiFi -> cellular).
        super::reprobe::maybe_spawn_reprobe(&state);
    }

    let result = run_accept_loop(listener, state.clone(), RuntimeShutdown::new(control.clone()), client_capacity).map(
        |outcome| {
            let evidence = control.as_ref().map_or_else(Vec::new, |control| control.desync_execution_evidence());
            let evidence_overflowed =
                control.as_ref().is_some_and(|control| control.desync_execution_evidence_overflowed());
            ProxyRuntimeCleanupReceipt::clean(
                outcome.drain_outcome.forced_abort,
                outcome.drain_outcome.worker_panicked,
                evidence,
                evidence_overflowed,
                state.candidate_refusal_counters().connection_refused_count,
                state.candidate_refusal_counters().duplicate_refusal_count,
                outcome.poll_error_kind,
            )
        },
    );
    state.flush_host_store();
    result
}

pub(super) fn close_rejected_client(client: &TcpStream) {
    listener_platform::close_rejected_client(client);
}

/// Registration token carried by the `RootHelperRegistration` RAII guard.
///
/// A `RootHelperGeneration` on platforms with a root helper; `()` elsewhere,
/// where there is nothing to register and the guard is inert. The portable
/// alias keeps the guard struct compilable on every target while the
/// `root_helper` module itself is `linux`/`android`-only.
#[cfg(any(target_os = "linux", target_os = "android"))]
type RootHelperToken = RootHelperGeneration;
#[cfg(not(any(target_os = "linux", target_os = "android")))]
type RootHelperToken = ();

struct RootHelperRegistration {
    /// `Some(token)` once this guard has registered the root helper client;
    /// `None` when registration was skipped. Releasing through the generation
    /// token makes a stale `Drop` from a superseded session a no-op instead
    /// of clobbering a newer session's registration.
    generation: Option<RootHelperToken>,
}

impl RootHelperRegistration {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn for_config(config: &RuntimeConfig) -> Self {
        let Some(path) =
            config.process.root_helper_socket_path.as_deref().map(str::trim).filter(|path| !path.is_empty())
        else {
            return Self { generation: None };
        };
        let generation =
            ripdpi_proxy_runtime_adapter::platform::root_helper::register_root_helper_versioned(path.to_owned());
        Self { generation: Some(generation) }
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    fn for_config(_config: &RuntimeConfig) -> Self {
        Self { generation: None }
    }
}

impl Drop for RootHelperRegistration {
    fn drop(&mut self) {
        if let Some(generation) = self.generation {
            unregister_root_helper(generation);
        }
    }
}

// Compile-fail regressions for soundness issue #11: `RootHelperRegistration`
// is a move-only RAII guard whose `registered: bool` field gates the
// `unregister_root_helper()` call in Drop. Soundness requires that safe
// code cannot duplicate the guard — otherwise two guards could each see
// `registered == true` and Drop into a double-unregister.
//
// The two blocks below assert at compile time that the guard implements
// neither `Copy` nor `Clone`. The trait-dispatch ambiguity trick is the
// stable-Rust equivalent of `static_assertions::assert_not_impl_any!`:
// if a future change ever adds `Copy`/`Clone` to the struct, both
// `AmbiguousIf*<()>` and `AmbiguousIf*<u8>` impls apply and the `_` in
// the call site cannot be resolved → compile error.
const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfCopy<A> {
        fn check() {}
    }
    impl<T> AmbiguousIfCopy<()> for Check<T> {}
    impl<T: Copy> AmbiguousIfCopy<u8> for Check<T> {}
    <Check<RootHelperRegistration> as AmbiguousIfCopy<_>>::check();
};

const _: fn() = || {
    #[allow(dead_code)]
    struct Check<T>(core::marker::PhantomData<T>);
    #[allow(dead_code)]
    trait AmbiguousIfClone<A> {
        fn check() {}
    }
    impl<T> AmbiguousIfClone<()> for Check<T> {}
    impl<T: Clone> AmbiguousIfClone<u8> for Check<T> {}
    <Check<RootHelperRegistration> as AmbiguousIfClone<_>>::check();
};

#[cfg(any(target_os = "linux", target_os = "android"))]
fn unregister_root_helper(generation: RootHelperToken) {
    ripdpi_proxy_runtime_adapter::platform::root_helper::unregister_root_helper_if(generation);
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn unregister_root_helper(_generation: RootHelperToken) {}

#[derive(Clone)]
pub(super) struct RuntimeShutdown {
    control: Option<StdArc<EmbeddedProxyControl>>,
}

impl RuntimeShutdown {
    fn new(control: Option<StdArc<EmbeddedProxyControl>>) -> Self {
        Self { control }
    }

    pub(super) fn requested(&self) -> bool {
        self.control.as_ref().map_or_else(process::shutdown_requested, |value| value.shutdown_requested())
    }
}

#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
mod tests {
    use std::sync::Mutex;

    use super::*;

    static ROOT_HELPER_TEST_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn root_helper_registration_uses_nonblank_proxy_socket_path_until_drop() {
        let _lock = ROOT_HELPER_TEST_LOCK.lock().expect("root helper test lock");
        ripdpi_proxy_runtime_adapter::platform::root_helper::unregister_root_helper();
        let mut config = RuntimeConfig::default();
        config.process.root_helper_socket_path = Some(" /tmp/ripdpi-proxy-root-helper.sock ".to_string());

        let guard = RootHelperRegistration::for_config(&config);

        assert!(guard.generation.is_some());
        assert!(ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());
        assert_eq!(ripdpi_proxy_runtime_adapter::platform::root_helper::with_root_helper(|_| ()), Some(()));

        drop(guard);
        assert!(!ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());
    }

    #[test]
    fn blank_proxy_socket_path_does_not_register_root_helper() {
        let _lock = ROOT_HELPER_TEST_LOCK.lock().expect("root helper test lock");
        ripdpi_proxy_runtime_adapter::platform::root_helper::unregister_root_helper();
        let mut config = RuntimeConfig::default();
        config.process.root_helper_socket_path = Some(" \n\t ".to_string());

        let guard = RootHelperRegistration::for_config(&config);

        assert!(guard.generation.is_none());
        assert!(!ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());
    }

    /// Issue #11 regression: prove sequential register/drop cycles
    /// leave no stale state. The "no double-destroy" invariant follows
    /// from Rust's move semantics + Drop running exactly once per
    /// moved value; this test exercises the runtime half by chaining
    /// two guard lifecycles and asserting clean state between them.
    #[test]
    fn sequential_registrations_each_cleanup_exactly_once() {
        let _lock = ROOT_HELPER_TEST_LOCK.lock().expect("root helper test lock");
        ripdpi_proxy_runtime_adapter::platform::root_helper::unregister_root_helper();
        let mut config = RuntimeConfig::default();
        config.process.root_helper_socket_path = Some("/tmp/ripdpi-proxy-root-helper-seq.sock".to_string());

        {
            let guard1 = RootHelperRegistration::for_config(&config);
            assert!(guard1.generation.is_some());
            assert!(ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());
        }
        // First guard dropped — registration must be cleared exactly once.
        assert!(!ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());

        {
            let guard2 = RootHelperRegistration::for_config(&config);
            assert!(guard2.generation.is_some());
            assert!(ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());
        }
        // Second guard dropped — registration must be cleared again.
        assert!(!ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());
    }

    /// Issue #11 regression: prove the conditional `if self.registered`
    /// branch in `Drop` actually gates the unregister call. A guard
    /// constructed with a blank path (`registered == false`) must NOT
    /// touch an unrelated foreign registration on drop — that would
    /// be the "stale handle" failure mode where one guard's lifecycle
    /// silently invalidates another's resource.
    #[test]
    fn unregistered_guard_drop_does_not_touch_foreign_registration() {
        let _lock = ROOT_HELPER_TEST_LOCK.lock().expect("root helper test lock");
        ripdpi_proxy_runtime_adapter::platform::root_helper::unregister_root_helper();

        // Install a registered guard first.
        let mut config = RuntimeConfig::default();
        config.process.root_helper_socket_path = Some("/tmp/ripdpi-proxy-root-helper-foreign.sock".to_string());
        let foreign = RootHelperRegistration::for_config(&config);
        assert!(foreign.generation.is_some());
        assert!(ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());

        // Construct + drop an UNregistered guard. Its Drop must be a no-op.
        {
            let mut blank_config = RuntimeConfig::default();
            blank_config.process.root_helper_socket_path = Some(" ".to_string());
            let skip_guard = RootHelperRegistration::for_config(&blank_config);
            assert!(skip_guard.generation.is_none());
        }

        // Foreign registration survives — proves the conditional gate.
        assert!(ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());

        // Cleanup.
        drop(foreign);
        assert!(!ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());
    }

    /// Issue #11 documented failure mode: `mem::forget(guard)` suppresses
    /// Drop, leaking the registration. Rust's safety model explicitly
    /// allows leaks (they are not UB), but soundness depends on no
    /// follow-up code assuming Drop ran. This test pins the leak
    /// behaviour so future contributors don't add silent assumptions.
    #[test]
    fn mem_forget_leaks_registration_documented_failure_mode() {
        let _lock = ROOT_HELPER_TEST_LOCK.lock().expect("root helper test lock");
        ripdpi_proxy_runtime_adapter::platform::root_helper::unregister_root_helper();
        let mut config = RuntimeConfig::default();
        config.process.root_helper_socket_path = Some("/tmp/ripdpi-proxy-root-helper-leak.sock".to_string());

        let guard = RootHelperRegistration::for_config(&config);
        assert!(guard.generation.is_some());
        assert!(ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());

        std::mem::forget(guard);

        // Drop did not run; registration LEAKS. Documented limitation.
        assert!(ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());

        // Manual cleanup for the next test.
        ripdpi_proxy_runtime_adapter::platform::root_helper::unregister_root_helper();
        assert!(!ripdpi_proxy_runtime_adapter::platform::root_helper::has_root_helper());
    }
}
