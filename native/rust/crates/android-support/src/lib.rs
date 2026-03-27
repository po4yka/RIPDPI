mod sync;

use std::collections::{HashMap, VecDeque};
use std::fmt;
use std::sync::Arc;
use std::sync::PoisonError;

use crate::sync::{fetch_add_u64, AtomicU64, Mutex, Ordering};
use jni::objects::{JString, JThrowable};
use jni::strings::JNIString;
use jni::sys::{jint, jstring};
use jni::Env;
use jni::EnvUnowned;
use jni::Outcome;
use log::LevelFilter;
use once_cell::sync::OnceCell;
use tracing::Subscriber;
#[cfg(target_os = "android")]
use tracing_log::LogTracer;
use tracing_subscriber::layer::{Context, Layer};
#[cfg(target_os = "android")]
use tracing_subscriber::prelude::*;
use tracing_subscriber::registry::LookupSpan;

pub const JNI_VERSION: jint = jni::sys::JNI_VERSION_1_6;

pub struct HandleRegistry<T> {
    next: AtomicU64,
    inner: Mutex<HashMap<u64, Arc<T>>>,
}

impl<T> Default for HandleRegistry<T> {
    fn default() -> Self {
        Self::new()
    }
}

impl<T> HandleRegistry<T> {
    pub fn new() -> Self {
        Self { next: AtomicU64::new(1), inner: Mutex::new(HashMap::new()) }
    }

    pub fn insert(&self, value: T) -> u64 {
        let handle = fetch_add_u64(&self.next, 1, Ordering::Relaxed);
        // Ensure handle stays in positive i64 range for JNI compatibility.
        // Wrap back to 1 if we exceed i64::MAX. In practice this never happens
        // (would require 9.2 quintillion sessions), but prevents sign mismatch
        // between Rust u64 handles and Kotlin Long (jlong) values.
        let handle = if handle > i64::MAX as u64 {
            self.next.store(2, Ordering::Relaxed);
            1
        } else {
            handle
        };
        self.inner.lock().unwrap_or_else(PoisonError::into_inner).insert(handle, Arc::new(value));
        handle
    }

    pub fn get(&self, handle: u64) -> Option<Arc<T>> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner).get(&handle).cloned()
    }

    pub fn remove(&self, handle: u64) -> Option<Arc<T>> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner).remove(&handle)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NativeEventRecord {
    pub source: String,
    pub level: String,
    pub message: String,
    pub created_at: u64,
    pub runtime_id: Option<String>,
    pub mode: Option<String>,
    pub policy_signature: Option<String>,
    pub fingerprint_hash: Option<String>,
    pub diagnostics_session_id: Option<String>,
    pub subsystem: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RingConfig {
    pub proxy_capacity: usize,
    pub tunnel_capacity: usize,
    pub diagnostics_capacity: usize,
}

impl Default for RingConfig {
    fn default() -> Self {
        Self { proxy_capacity: 128, tunnel_capacity: 128, diagnostics_capacity: 256 }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum EventRing {
    Proxy,
    Tunnel,
    Diagnostics,
}

impl EventRing {
    fn from_routing_field(value: &str) -> Option<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "proxy" => Some(Self::Proxy),
            "tunnel" => Some(Self::Tunnel),
            "diagnostics" | "monitor" => Some(Self::Diagnostics),
            _ => None,
        }
    }

    fn default_subsystem(self) -> &'static str {
        match self {
            Self::Proxy => "proxy",
            Self::Tunnel => "tunnel",
            Self::Diagnostics => "diagnostics",
        }
    }
}

struct EventRingBuffersInner {
    config: RingConfig,
    proxy: Mutex<VecDeque<NativeEventRecord>>,
    tunnel: Mutex<VecDeque<NativeEventRecord>>,
    diagnostics: Mutex<VecDeque<NativeEventRecord>>,
}

#[derive(Clone)]
pub struct EventRingBuffers {
    inner: Arc<EventRingBuffersInner>,
}

impl Default for EventRingBuffers {
    fn default() -> Self {
        Self::new(RingConfig::default())
    }
}

impl EventRingBuffers {
    pub fn new(config: RingConfig) -> Self {
        Self {
            inner: Arc::new(EventRingBuffersInner {
                proxy: Mutex::new(VecDeque::with_capacity(config.proxy_capacity)),
                tunnel: Mutex::new(VecDeque::with_capacity(config.tunnel_capacity)),
                diagnostics: Mutex::new(VecDeque::with_capacity(config.diagnostics_capacity)),
                config,
            }),
        }
    }

    fn push(&self, ring: EventRing, event: NativeEventRecord) {
        let capacity = self.capacity(ring);
        let mut guard = self.ring(ring).lock().unwrap_or_else(PoisonError::into_inner);
        if guard.len() >= capacity {
            guard.pop_front();
        }
        guard.push_back(event);
    }

    fn drain(&self, ring: EventRing) -> Vec<NativeEventRecord> {
        self.ring(ring).lock().unwrap_or_else(PoisonError::into_inner).drain(..).collect()
    }

    fn clear(&self, ring: EventRing) {
        self.ring(ring).lock().unwrap_or_else(PoisonError::into_inner).clear();
    }

    pub fn drain_proxy(&self) -> Vec<NativeEventRecord> {
        self.drain(EventRing::Proxy)
    }

    pub fn drain_tunnel(&self) -> Vec<NativeEventRecord> {
        self.drain(EventRing::Tunnel)
    }

    pub fn drain_diagnostics(&self) -> Vec<NativeEventRecord> {
        self.drain(EventRing::Diagnostics)
    }

    pub fn clear_proxy(&self) {
        self.clear(EventRing::Proxy);
    }

    pub fn clear_tunnel(&self) {
        self.clear(EventRing::Tunnel);
    }

    pub fn clear_diagnostics(&self) {
        self.clear(EventRing::Diagnostics);
    }

    fn ring(&self, ring: EventRing) -> &Mutex<VecDeque<NativeEventRecord>> {
        match ring {
            EventRing::Proxy => &self.inner.proxy,
            EventRing::Tunnel => &self.inner.tunnel,
            EventRing::Diagnostics => &self.inner.diagnostics,
        }
    }

    fn capacity(&self, ring: EventRing) -> usize {
        match ring {
            EventRing::Proxy => self.inner.config.proxy_capacity,
            EventRing::Tunnel => self.inner.config.tunnel_capacity,
            EventRing::Diagnostics => self.inner.config.diagnostics_capacity,
        }
    }
}

fn global_event_rings() -> &'static EventRingBuffers {
    static EVENT_RINGS: OnceCell<EventRingBuffers> = OnceCell::new();
    EVENT_RINGS.get_or_init(EventRingBuffers::default)
}

pub fn drain_proxy_events() -> Vec<NativeEventRecord> {
    global_event_rings().drain_proxy()
}

pub fn drain_tunnel_events() -> Vec<NativeEventRecord> {
    global_event_rings().drain_tunnel()
}

pub fn drain_diagnostics_events() -> Vec<NativeEventRecord> {
    global_event_rings().drain_diagnostics()
}

pub fn clear_proxy_events() {
    global_event_rings().clear_proxy();
}

pub fn clear_tunnel_events() {
    global_event_rings().clear_tunnel();
}

pub fn clear_diagnostics_events() {
    global_event_rings().clear_diagnostics();
}

/// Install a global panic hook that logs the panic message and a full
/// backtrace via `log::error!`. Must be called **after** `init_android_logging`
/// so that the log backend is already wired up to logcat (on Android) or
/// stderr (on other targets).
///
/// Guarded by `OnceCell` -- safe to call from multiple `.so` loads.
pub fn install_panic_hook() {
    static HOOK: OnceCell<()> = OnceCell::new();
    HOOK.get_or_init(|| {
        std::panic::set_hook(Box::new(|info| {
            let backtrace = std::backtrace::Backtrace::force_capture();
            log::error!("PANIC: {info}\n{backtrace}");
        }));
    });
}

pub fn init_android_logging(tag: &'static str) {
    static INIT: OnceCell<()> = OnceCell::new();
    INIT.get_or_init(|| {
        #[cfg(target_os = "android")]
        {
            android_logger::init_once(android_logger::Config::default().with_tag(tag));

            let _ = LogTracer::init();

            let _ = tracing_subscriber::registry().with(AndroidLogLayer).with(EventRingLayer::global()).try_init();
        }

        log::set_max_level(default_android_log_level());

        #[cfg(not(target_os = "android"))]
        {
            let _ = tag;
        }
    });
}

pub fn default_android_log_level() -> LevelFilter {
    if cfg!(debug_assertions) {
        LevelFilter::Debug
    } else {
        LevelFilter::Info
    }
}

pub fn android_log_level_from_str(level: &str) -> Option<LevelFilter> {
    match level.trim().to_ascii_lowercase().as_str() {
        "trace" => Some(LevelFilter::Trace),
        "debug" => Some(LevelFilter::Debug),
        "info" => Some(LevelFilter::Info),
        "warn" | "warning" => Some(LevelFilter::Warn),
        "error" => Some(LevelFilter::Error),
        "off" => Some(LevelFilter::Off),
        _ => None,
    }
}

pub fn android_log_level_from_debug_verbosity(debug: i32) -> LevelFilter {
    match debug {
        i32::MIN..=0 => LevelFilter::Info,
        1 => LevelFilter::Debug,
        _ => LevelFilter::Trace,
    }
}

pub fn set_android_log_scope_level(scope: impl Into<String>, level: LevelFilter) {
    let mut scopes = log_scope_levels().lock().unwrap_or_else(PoisonError::into_inner);
    scopes.insert(scope.into(), level);
    apply_android_log_level(&scopes);
}

pub fn clear_android_log_scope_level(scope: &str) {
    let mut scopes = log_scope_levels().lock().unwrap_or_else(PoisonError::into_inner);
    scopes.remove(scope);
    apply_android_log_level(&scopes);
}

pub fn log_with_level(level: &str, message: impl AsRef<str>) {
    let message = message.as_ref();
    match level.trim().to_ascii_lowercase().as_str() {
        "trace" => log::trace!("{message}"),
        "debug" => log::debug!("{message}"),
        "warn" | "warning" => log::warn!("{message}"),
        "error" => log::error!("{message}"),
        _ => log::info!("{message}"),
    }
}

fn log_scope_levels() -> &'static Mutex<HashMap<String, LevelFilter>> {
    static LOG_SCOPE_LEVELS: OnceCell<Mutex<HashMap<String, LevelFilter>>> = OnceCell::new();
    LOG_SCOPE_LEVELS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn apply_android_log_level(scopes: &HashMap<String, LevelFilter>) {
    let level = scopes.values().copied().max().unwrap_or_else(default_android_log_level);
    log::set_max_level(level);
}

/// Ignore SIGPIPE so that socket peer disconnects don't crash the process.
///
/// On Android, the ART runtime does not ignore SIGPIPE by default for native
/// code. Writing to a closed socket/pipe delivers SIGPIPE, which terminates
/// the process unless handled. This must be called once from `JNI_OnLoad`.
pub fn ignore_sigpipe() {
    use nix::sys::signal::{signal, SigHandler, Signal};
    // SAFETY: Ignoring SIGPIPE is async-signal-safe. The previous handler is
    // discarded; we don't need to restore it.
    let _ = unsafe { signal(Signal::SIGPIPE, SigHandler::SigIgn) };
}

pub fn throw_illegal_argument(env: &mut EnvUnowned<'_>, message: impl AsRef<str>) {
    throw_exception(env, "java/lang/IllegalArgumentException", "IllegalArgumentException", message);
}

pub fn throw_illegal_argument_env(env: &mut Env<'_>, message: impl AsRef<str>) {
    throw_exception_env(env, "java/lang/IllegalArgumentException", "IllegalArgumentException", message);
}

pub fn throw_illegal_state(env: &mut EnvUnowned<'_>, message: impl AsRef<str>) {
    throw_exception(env, "java/lang/IllegalStateException", "IllegalStateException", message);
}

pub fn throw_illegal_state_env(env: &mut Env<'_>, message: impl AsRef<str>) {
    throw_exception_env(env, "java/lang/IllegalStateException", "IllegalStateException", message);
}

pub fn throw_io_exception(env: &mut EnvUnowned<'_>, message: impl AsRef<str>) {
    throw_exception(env, "java/io/IOException", "IOException", message);
}

pub fn throw_io_exception_env(env: &mut Env<'_>, message: impl AsRef<str>) {
    throw_exception_env(env, "java/io/IOException", "IOException", message);
}

pub fn throw_runtime_exception(env: &mut EnvUnowned<'_>, message: impl AsRef<str>) {
    throw_exception(env, "java/lang/RuntimeException", "RuntimeException", message);
}

pub fn throw_runtime_exception_env(env: &mut Env<'_>, message: impl AsRef<str>) {
    throw_exception_env(env, "java/lang/RuntimeException", "RuntimeException", message);
}

/// Produce a user-safe error message, stripping internal details in release builds.
pub fn sanitize_error_message(detail: &str, user_message: &str) -> String {
    if cfg!(debug_assertions) {
        format!("{user_message}: {detail}")
    } else {
        user_message.to_string()
    }
}

pub fn describe_exception(env: &mut EnvUnowned<'_>) -> Option<String> {
    match env
        .with_env(|env| -> jni::errors::Result<Option<String>> {
            if !env.exception_check() {
                return Ok(None);
            }
            let Some(throwable) = env.exception_occurred() else {
                return Ok(None);
            };
            env.exception_clear();
            Ok(throwable_to_string(env, throwable))
        })
        .into_outcome()
    {
        Outcome::Ok(description) => description,
        Outcome::Err(err) => {
            log::error!("Failed to describe pending Java exception: {err}");
            None
        }
        Outcome::Panic(_) => {
            log::error!("Panic while describing pending Java exception");
            None
        }
    }
}

fn throw_exception(env: &mut EnvUnowned<'_>, class_name: &str, exception_name: &str, message: impl AsRef<str>) {
    match env
        .with_env(|env| -> jni::errors::Result<()> {
            throw_exception_env(env, class_name, exception_name, message);
            Ok(())
        })
        .into_outcome()
    {
        Outcome::Ok(()) => {}
        Outcome::Err(err) => {
            log::error!("Failed to enter JNI env while throwing {exception_name}: {err}");
        }
        Outcome::Panic(_) => {
            log::error!("Panic while preparing to throw {exception_name}");
        }
    }
}

fn throw_exception_env(env: &mut Env<'_>, class_name: &str, exception_name: &str, message: impl AsRef<str>) {
    let message = message.as_ref();
    let message_text = message.to_string();
    let class_name = JNIString::new(class_name);
    let message = JNIString::new(message);
    match env.throw_new(class_name.borrowed(), message.borrowed()) {
        Ok(()) | Err(jni::errors::Error::JavaException) => {}
        Err(err) => {
            log::error!("Failed to throw {exception_name}: {message_text}: {err}");
        }
    }
}

fn throwable_to_string(env: &mut Env<'_>, throwable: JThrowable) -> Option<String> {
    let text = env
        .call_method(throwable, jni::jni_str!("toString"), jni::jni_sig!("()Ljava/lang/String;"), &[])
        .ok()?
        .l()
        .ok()?;
    let text = unsafe { JString::from_raw(env, text.into_raw() as jstring) };
    text.try_to_string(env).ok()
}

#[cfg(target_os = "android")]
struct AndroidLogLayer;

#[cfg(target_os = "android")]
impl<S> Layer<S> for AndroidLogLayer
where
    S: Subscriber + for<'span> LookupSpan<'span>,
{
    fn on_event(&self, event: &tracing::Event<'_>, _ctx: Context<'_, S>) {
        let mut visitor = MessageFieldFormatter::default();
        event.record(&mut visitor);
        let metadata = event.metadata();
        let message = visitor.finish(metadata.target());

        match *metadata.level() {
            tracing::Level::ERROR => log::error!("{message}"),
            tracing::Level::WARN => log::warn!("{message}"),
            tracing::Level::INFO => log::info!("{message}"),
            tracing::Level::DEBUG => log::debug!("{message}"),
            tracing::Level::TRACE => log::trace!("{message}"),
        }
    }
}

#[derive(Clone)]
pub struct EventRingLayer {
    buffers: EventRingBuffers,
}

impl EventRingLayer {
    pub fn new(buffers: EventRingBuffers) -> Self {
        Self { buffers }
    }

    pub fn global() -> Self {
        Self::new(global_event_rings().clone())
    }
}

impl<S> Layer<S> for EventRingLayer
where
    S: Subscriber + for<'span> LookupSpan<'span>,
{
    fn on_event(&self, event: &tracing::Event<'_>, _ctx: Context<'_, S>) {
        let mut visitor = MessageFieldFormatter::default();
        event.record(&mut visitor);
        let metadata = event.metadata();
        let Some(ring) = visitor.ring().as_deref().and_then(EventRing::from_routing_field) else {
            return;
        };

        self.buffers.push(
            ring,
            NativeEventRecord {
                source: visitor.source().unwrap_or_else(|| metadata.target().to_string()),
                level: metadata.level().as_str().to_ascii_lowercase(),
                message: visitor.message_or_target(metadata.target()),
                created_at: now_ms(),
                runtime_id: visitor.runtime_id(),
                mode: visitor.mode().map(|value| value.to_ascii_lowercase()),
                policy_signature: visitor.policy_signature(),
                fingerprint_hash: visitor.fingerprint_hash(),
                diagnostics_session_id: visitor.diagnostics_session_id(),
                subsystem: visitor.subsystem().or_else(|| Some(ring.default_subsystem().to_string())),
            },
        );
    }
}

#[derive(Default)]
#[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
pub(crate) struct MessageFieldFormatter {
    message: Option<String>,
    visible_fields: Vec<(String, String)>,
    ring: Option<String>,
    subsystem: Option<String>,
    session: Option<String>,
    profile: Option<String>,
    path_mode: Option<String>,
    source: Option<String>,
    runtime_id: Option<String>,
    mode: Option<String>,
    policy_signature: Option<String>,
    fingerprint_hash: Option<String>,
    diagnostics_session_id: Option<String>,
}

impl MessageFieldFormatter {
    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn record_named_debug(&mut self, field: &str, value: &dyn fmt::Debug) {
        self.record_value(field, format!("{value:?}"));
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn record_named_str(&mut self, field: &str, value: &str) {
        self.record_value(field, value.to_string());
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn finish(self, target: &str) -> String {
        let mut parts = Vec::new();

        for prefix in [
            self.subsystem.as_ref().map(|value| format!("subsystem={value}")),
            self.session.as_ref().map(|value| format!("session={value}")),
            self.profile.as_ref().map(|value| format!("profile={value}")),
            self.path_mode.as_ref().map(|value| format!("pathMode={value}")),
            self.source.as_ref().map(|value| format!("source={value}")),
        ]
        .into_iter()
        .flatten()
        {
            parts.push(prefix);
        }

        if let Some(message) = self.message {
            parts.push(message);
        }

        for (field, value) in self.visible_fields {
            parts.push(format!("{field}={value}"));
        }

        if parts.is_empty() {
            target.to_string()
        } else {
            parts.join(" ")
        }
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    fn record_value(&mut self, field: &str, value: String) {
        if field != "message" && value.trim().is_empty() {
            return;
        }
        match field {
            "message" => self.message = Some(value),
            "ring" => self.ring = Some(value),
            "subsystem" => self.subsystem = Some(value),
            "session" => self.session = Some(value),
            "profile" => self.profile = Some(value),
            "path_mode" | "pathMode" => self.path_mode = Some(value),
            "source" => self.source = Some(value),
            "runtime_id" | "runtimeId" => self.runtime_id = Some(value),
            "mode" => self.mode = Some(value),
            "policy_signature" | "policySignature" => self.policy_signature = Some(value),
            "fingerprint_hash" | "fingerprintHash" => self.fingerprint_hash = Some(value),
            "diagnostics_session_id" | "diagnosticsSessionId" => self.diagnostics_session_id = Some(value),
            _ => self.visible_fields.push((field.to_string(), value)),
        }
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn message_or_target(&self, target: &str) -> String {
        self.message.clone().unwrap_or_else(|| target.to_string())
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn ring(&self) -> Option<String> {
        self.ring.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn subsystem(&self) -> Option<String> {
        self.subsystem.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn source(&self) -> Option<String> {
        self.source.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn runtime_id(&self) -> Option<String> {
        self.runtime_id.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn mode(&self) -> Option<String> {
        self.mode.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn policy_signature(&self) -> Option<String> {
        self.policy_signature.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn fingerprint_hash(&self) -> Option<String> {
        self.fingerprint_hash.clone()
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn diagnostics_session_id(&self) -> Option<String> {
        self.diagnostics_session_id.clone()
    }
}

impl tracing::field::Visit for MessageFieldFormatter {
    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn fmt::Debug) {
        self.record_named_debug(field.name(), value);
    }

    fn record_str(&mut self, field: &tracing::field::Field, value: &str) {
        self.record_named_str(field.name(), value);
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use golden_test_support::assert_text_golden;
    use jni::{Env, EnvUnowned, InitArgsBuilder, JNIVersion, JavaVM};
    use once_cell::sync::{Lazy, OnceCell};
    use std::sync::Mutex;
    use tracing_subscriber::prelude::*;

    static TEST_JVM: OnceCell<JavaVM> = OnceCell::new();
    static JNI_TEST_MUTEX: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));
    static LOG_LEVEL_TEST_MUTEX: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));

    #[test]
    fn install_panic_hook_is_idempotent() {
        install_panic_hook();
        install_panic_hook(); // second call must not panic
    }

    #[test]
    fn formatter_renders_plain_message_golden() {
        let mut formatter = MessageFieldFormatter::default();
        formatter.record_named_str("message", "proxy started");

        assert_text_golden(
            env!("CARGO_MANIFEST_DIR"),
            "tests/golden/plain_message.txt",
            &formatter.finish("fallback.target"),
        );
    }

    #[test]
    fn formatter_renders_structured_fields_golden() {
        let mut formatter = MessageFieldFormatter::default();
        formatter.record_named_str("message", "route selected");
        formatter.record_named_str("target", "203.0.113.10:443");
        formatter.record_named_debug("group", &2);

        assert_text_golden(
            env!("CARGO_MANIFEST_DIR"),
            "tests/golden/structured_fields.txt",
            &formatter.finish("fallback.target"),
        );
    }

    #[test]
    fn formatter_falls_back_to_target_when_message_is_absent() {
        let formatter = MessageFieldFormatter::default();
        assert_text_golden(
            env!("CARGO_MANIFEST_DIR"),
            "tests/golden/fallback_target.txt",
            &formatter.finish("ripdpi.native"),
        );
    }

    #[test]
    fn formatter_preserves_debug_quotes_for_non_message_fields() {
        let mut formatter = MessageFieldFormatter::default();
        formatter.record_named_str("message", "tunnel error");
        formatter.record_named_debug("error", &"unexpected eof");

        assert_text_golden(
            env!("CARGO_MANIFEST_DIR"),
            "tests/golden/debug_quotes.txt",
            &formatter.finish("fallback.target"),
        );
    }

    #[test]
    fn formatter_renders_structured_prefix_fields_before_message() {
        let mut formatter = MessageFieldFormatter::default();
        formatter.record_named_str("subsystem", "diagnostics");
        formatter.record_named_str("session", "diag-7");
        formatter.record_named_str("profile", "connectivity");
        formatter.record_named_str("path_mode", "RAW_PATH");
        formatter.record_named_str("source", "dns");
        formatter.record_named_str("message", "probe started");

        assert_eq!(
            formatter.finish("fallback.target"),
            "subsystem=diagnostics session=diag-7 profile=connectivity pathMode=RAW_PATH source=dns probe started",
        );
    }

    #[test]
    fn event_ring_layer_routes_and_drains_correlation_fields() {
        let buffers =
            EventRingBuffers::new(RingConfig { proxy_capacity: 8, tunnel_capacity: 8, diagnostics_capacity: 8 });
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));

        tracing::subscriber::with_default(subscriber, || {
            tracing::warn!(
                ring = "diagnostics",
                subsystem = "diagnostics",
                session = "diag-42",
                profile = "connectivity",
                path_mode = "RAW_PATH",
                source = "dns",
                runtime_id = "vpn-runtime-1",
                mode = "VPN",
                policy_signature = "policy-123",
                fingerprint_hash = "fingerprint-abc",
                diagnostics_session_id = "diag-42",
                "probe failed target=example.org"
            );
        });

        let events = buffers.drain_diagnostics();
        assert_eq!(events.len(), 1);
        assert!(buffers.drain_diagnostics().is_empty(), "drain must empty the ring");
        assert_eq!(
            events[0],
            NativeEventRecord {
                source: "dns".to_string(),
                level: "warn".to_string(),
                message: "probe failed target=example.org".to_string(),
                created_at: events[0].created_at,
                runtime_id: Some("vpn-runtime-1".to_string()),
                mode: Some("vpn".to_string()),
                policy_signature: Some("policy-123".to_string()),
                fingerprint_hash: Some("fingerprint-abc".to_string()),
                diagnostics_session_id: Some("diag-42".to_string()),
                subsystem: Some("diagnostics".to_string()),
            },
        );
    }

    #[test]
    fn event_ring_layer_respects_capacity_per_ring() {
        let buffers =
            EventRingBuffers::new(RingConfig { proxy_capacity: 2, tunnel_capacity: 2, diagnostics_capacity: 2 });
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));

        tracing::subscriber::with_default(subscriber, || {
            tracing::info!(ring = "proxy", source = "proxy", "one");
            tracing::info!(ring = "proxy", source = "proxy", "two");
            tracing::info!(ring = "proxy", source = "proxy", "three");
        });

        let messages: Vec<String> = buffers.drain_proxy().into_iter().map(|event| event.message).collect();
        assert_eq!(messages, vec!["two".to_string(), "three".to_string()]);
    }

    #[test]
    fn throw_helpers_map_expected_java_exception_classes() {
        let _serial = JNI_TEST_MUTEX.lock().expect("lock android-support JNI tests");

        with_unowned_env(|env| {
            throw_illegal_argument(env, "bad arg");
            assert_eq!(take_exception(env), "java.lang.IllegalArgumentException: bad arg",);
        });

        with_unowned_env(|env| {
            throw_illegal_state(env, "bad state");
            assert_eq!(take_exception(env), "java.lang.IllegalStateException: bad state",);
        });

        with_unowned_env(|env| {
            throw_io_exception(env, "disk boom");
            assert_eq!(take_exception(env), "java.io.IOException: disk boom",);
        });

        with_unowned_env(|env| {
            throw_runtime_exception(env, "runtime boom");
            assert_eq!(take_exception(env), "java.lang.RuntimeException: runtime boom",);
        });
    }

    #[test]
    fn describe_exception_reads_and_clears_pending_exception() {
        let _serial = JNI_TEST_MUTEX.lock().expect("lock android-support JNI tests");

        with_env(|env| {
            let err = env
                .throw_new(jni::jni_str!("java/lang/RuntimeException"), jni::jni_str!("direct boom"))
                .expect_err("throw direct runtime exception");
            assert!(matches!(err, jni::errors::Error::JavaException));

            with_borrowed_unowned_env(env, |env| {
                assert_eq!(describe_exception(env), Some("java.lang.RuntimeException: direct boom".to_string()),);
                assert!(describe_exception(env).is_none(), "describe_exception should clear the pending throwable");
            });
        });
    }

    #[test]
    fn describe_exception_returns_none_when_no_exception_is_pending() {
        let _serial = JNI_TEST_MUTEX.lock().expect("lock android-support JNI tests");

        with_unowned_env(|env| {
            assert!(describe_exception(env).is_none());
        });
    }

    #[test]
    fn android_log_level_parser_supports_expected_values() {
        assert_eq!(android_log_level_from_str("trace"), Some(LevelFilter::Trace));
        assert_eq!(android_log_level_from_str("debug"), Some(LevelFilter::Debug));
        assert_eq!(android_log_level_from_str("info"), Some(LevelFilter::Info));
        assert_eq!(android_log_level_from_str("warning"), Some(LevelFilter::Warn));
        assert_eq!(android_log_level_from_str("error"), Some(LevelFilter::Error));
        assert_eq!(android_log_level_from_str("off"), Some(LevelFilter::Off));
        assert_eq!(android_log_level_from_str("nope"), None);
    }

    #[test]
    fn scoped_log_levels_keep_the_most_verbose_active_request() {
        let _serial = LOG_LEVEL_TEST_MUTEX.lock().expect("lock android-support log level tests");
        clear_android_log_scope_level("android-support:test:a");
        clear_android_log_scope_level("android-support:test:b");
        log::set_max_level(default_android_log_level());

        set_android_log_scope_level("android-support:test:a", LevelFilter::Warn);
        assert_eq!(log::max_level(), LevelFilter::Warn);

        set_android_log_scope_level("android-support:test:b", LevelFilter::Trace);
        assert_eq!(log::max_level(), LevelFilter::Trace);

        clear_android_log_scope_level("android-support:test:b");
        assert_eq!(log::max_level(), LevelFilter::Warn);

        clear_android_log_scope_level("android-support:test:a");
        assert_eq!(log::max_level(), default_android_log_level());
    }

    fn test_jvm() -> &'static JavaVM {
        TEST_JVM.get_or_init(|| {
            let args = InitArgsBuilder::new()
                .version(JNIVersion::V1_8)
                .option("-Xcheck:jni")
                .build()
                .expect("build test JVM init args");
            JavaVM::new(args).expect("create in-process test JVM")
        })
    }

    fn with_env<R>(f: impl FnOnce(&mut Env<'_>) -> R) -> R {
        test_jvm()
            .attach_current_thread(|env| Ok::<R, jni::errors::Error>(f(env)))
            .expect("attach current thread to test JVM")
    }

    fn with_unowned_env<R>(f: impl FnOnce(&mut EnvUnowned<'_>) -> R) -> R {
        with_env(|env| with_borrowed_unowned_env(env, f))
    }

    fn with_borrowed_unowned_env<R>(env: &mut Env<'_>, f: impl FnOnce(&mut EnvUnowned<'_>) -> R) -> R {
        // SAFETY: `env` is attached for this callback scope, and the unowned wrapper
        // is only used synchronously before the callback returns.
        let mut unowned_env = unsafe { EnvUnowned::from_raw(env.get_raw()) };
        f(&mut unowned_env)
    }

    fn take_exception(env: &mut EnvUnowned<'_>) -> String {
        describe_exception(env).expect("expected Java exception")
    }

    #[test]
    fn handle_sentinel_matches_contract_fixture() {
        use golden_test_support::assert_contract_fixture;
        use serde_json::json;

        let registry = HandleRegistry::<String>::new();

        // Sentinel: handle 0 is always invalid
        assert!(registry.get(0).is_none(), "handle 0 must be invalid");

        // First allocated handle must be >= 1
        let first = registry.insert("test".to_string());
        assert!(first >= 1, "first valid handle must be >= 1, got {first}");

        let fixture = json!({
            "invalidSentinel": 0,
            "minimumValidHandle": 1,
        });
        let actual = serde_json::to_string_pretty(&fixture).expect("serialize fixture");
        assert_contract_fixture("handle_contract.json", &actual);
    }
}
