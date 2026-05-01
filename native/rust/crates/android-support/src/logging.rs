use std::collections::HashMap;
use std::sync::PoisonError;

use log::LevelFilter;
use once_cell::sync::OnceCell;
#[cfg(target_os = "android")]
use tracing_log::LogTracer;
#[cfg(target_os = "android")]
use tracing_subscriber::prelude::*;

use crate::sync::Mutex;
#[cfg(target_os = "android")]
use crate::tracing_layer::AndroidLogLayer;
#[cfg(target_os = "android")]
use crate::tracing_layer::EventRingLayer;

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

fn log_scope_levels() -> &'static Mutex<HashMap<String, LevelFilter>> {
    static LOG_SCOPE_LEVELS: OnceCell<Mutex<HashMap<String, LevelFilter>>> = OnceCell::new();
    LOG_SCOPE_LEVELS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn apply_android_log_level(scopes: &HashMap<String, LevelFilter>) {
    let level = scopes.values().copied().max().unwrap_or_else(default_android_log_level);
    log::set_max_level(level);
}
