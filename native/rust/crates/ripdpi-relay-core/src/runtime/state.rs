use std::io;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};

use arc_swap::ArcSwapOption;

use crate::backend::RelayBackend;

pub(super) struct RuntimeState {
    stop_requested: AtomicBool,
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    backend: OnceLock<Arc<RelayBackend>>,
    listener_address: OnceLock<String>,
    last_target: ArcSwapOption<String>,
    last_error: ArcSwapOption<String>,
    last_handshake_error: ArcSwapOption<String>,
}

impl RuntimeState {
    pub(super) fn new() -> Self {
        Self {
            stop_requested: AtomicBool::new(false),
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            backend: OnceLock::new(),
            listener_address: OnceLock::new(),
            last_target: ArcSwapOption::empty(),
            last_error: ArcSwapOption::empty(),
            last_handshake_error: ArcSwapOption::empty(),
        }
    }

    pub(super) fn request_stop(&self) {
        self.stop_requested.store(true, Ordering::SeqCst);
    }

    pub(super) fn stop_requested(&self) -> bool {
        self.stop_requested.load(Ordering::SeqCst)
    }

    pub(super) fn set_running(&self, running: bool) {
        self.running.store(running, Ordering::SeqCst);
    }

    pub(super) fn is_running(&self) -> bool {
        self.running.load(Ordering::SeqCst)
    }

    pub(super) fn start_session(&self) {
        self.active_sessions.fetch_add(1, Ordering::SeqCst);
        self.total_sessions.fetch_add(1, Ordering::SeqCst);
    }

    pub(super) fn finish_session(&self) {
        self.active_sessions.fetch_sub(1, Ordering::SeqCst);
    }

    pub(super) fn active_sessions(&self) -> u64 {
        self.active_sessions.load(Ordering::SeqCst)
    }

    pub(super) fn total_sessions(&self) -> u64 {
        self.total_sessions.load(Ordering::SeqCst)
    }

    pub(super) fn set_backend(&self, backend: Arc<RelayBackend>) -> io::Result<()> {
        self.backend
            .set(backend)
            .map_err(|_| io::Error::new(io::ErrorKind::AlreadyExists, "relay backend was already initialized"))
    }

    pub(super) fn backend(&self) -> Option<&Arc<RelayBackend>> {
        self.backend.get()
    }

    pub(super) fn set_listener_address(&self, listener_address: String) -> io::Result<()> {
        self.listener_address
            .set(listener_address)
            .map_err(|_| io::Error::new(io::ErrorKind::AlreadyExists, "relay listener address was already initialized"))
    }

    pub(super) fn listener_address(&self) -> Option<String> {
        self.listener_address.get().cloned()
    }

    pub(super) fn record_target(&self, target: String) {
        self.last_target.store(Some(Arc::new(target)));
    }

    pub(super) fn record_error(&self, error: String) {
        self.last_error.store(Some(Arc::new(error)));
    }

    pub(super) fn record_handshake_error(&self, error: String) {
        self.last_handshake_error.store(Some(Arc::new(error)));
    }

    pub(super) fn last_target(&self) -> Option<String> {
        load_optional_string(&self.last_target)
    }

    pub(super) fn last_error(&self) -> Option<String> {
        load_optional_string(&self.last_error)
    }

    pub(super) fn last_handshake_error(&self) -> Option<String> {
        load_optional_string(&self.last_handshake_error)
    }
}

fn load_optional_string(slot: &ArcSwapOption<String>) -> Option<String> {
    slot.load_full().as_deref().cloned()
}
