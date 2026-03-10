//! Android JNI shim for RIPDPI's embedded hev-socks5-tunnel runtime.
//!
//! The Kotlin side expects `TProxyStartService` to return immediately and own
//! its worker thread internally, so this bridge intentionally differs from the
//! sample Android integration in the upstream Rust repo.

use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlongArray};
use jni::JNIEnv;
use once_cell::sync::OnceCell;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

use hs5t_core::Stats;

// ── Global state ──────────────────────────────────────────────────────────────

/// Single Tokio runtime for the lifetime of the process.
static RUNTIME: OnceCell<Runtime> = OnceCell::new();

/// Cancellation token for the currently-running tunnel.
/// Reset on each `TProxyStartService` call to support restart.
static CANCEL: Mutex<Option<Arc<CancellationToken>>> = Mutex::new(None);

/// Traffic statistics for the currently-running tunnel.
static STATS: Mutex<Option<Arc<Stats>>> = Mutex::new(None);

/// Worker thread that owns the blocking tunnel execution.
static WORKER: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);

fn get_runtime() -> Option<&'static Runtime> {
    RUNTIME
        .get_or_try_init(|| {
            tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
        })
        .ok()
}

// ── JNI entry points ──────────────────────────────────────────────────────────

/// Start the tunnel and return immediately.
#[no_mangle]
pub extern "system" fn Java_com_poyka_ripdpi_core_TProxyService_TProxyStartService(
    mut env: JNIEnv,
    _class: JClass,
    config_path: JString,
    tun_fd: jint,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        start_service_impl(&mut env, config_path, tun_fd)
    }));
}

/// Stop the running tunnel.  Safe to call from any thread.
#[no_mangle]
pub extern "system" fn Java_com_poyka_ripdpi_core_TProxyService_TProxyStopService(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = std::panic::catch_unwind(|| {
        if let Ok(guard) = CANCEL.lock() {
            if let Some(ref token) = *guard {
                token.cancel();
            }
        }
        if let Ok(mut guard) = WORKER.lock() {
            if let Some(handle) = guard.take() {
                let _ = handle.join();
            }
        }
        if let Ok(mut guard) = CANCEL.lock() {
            *guard = None;
        }
        if let Ok(mut guard) = STATS.lock() {
            *guard = None;
        }
    });
}

/// Return traffic statistics as a Java `long[4]` in the order
/// `[tx_pkt, tx_bytes, rx_pkt, rx_bytes]`.
#[no_mangle]
pub extern "system" fn Java_com_poyka_ripdpi_core_TProxyService_TProxyGetStats(
    env: JNIEnv,
    _class: JClass,
) -> jlongArray {
    let snapshot = std::panic::catch_unwind(get_stats_snapshot)
        .ok()
        .flatten()
        .unwrap_or((0, 0, 0, 0));

    match env.new_long_array(4) {
        Ok(arr) => {
            let values: [i64; 4] = [
                snapshot.0 as i64,
                snapshot.1 as i64,
                snapshot.2 as i64,
                snapshot.3 as i64,
            ];
            if env.set_long_array_region(&arr, 0, &values).is_ok() {
                arr.into_raw()
            } else {
                std::ptr::null_mut()
            }
        }
        Err(_) => std::ptr::null_mut(),
    }
}

// ── Internal helpers ──────────────────────────────────────────────────────────

fn start_service_impl(env: &mut JNIEnv, config_path: JString, tun_fd: jint) {
    if let Ok(mut guard) = WORKER.lock() {
        if let Some(handle) = guard.as_ref() {
            if !handle.is_finished() {
                return;
            }
        }
        if let Some(handle) = guard.take() {
            let _ = handle.join();
        }
    }

    let path: String = match env.get_string(&config_path) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    let config = match hs5t_config::Config::from_file(&path) {
        Ok(c) => std::sync::Arc::new(c),
        Err(_) => return,
    };

    let rt = match get_runtime() {
        Some(r) => r,
        None => return,
    };

    let cancel = Arc::new(CancellationToken::new());
    let stats = Arc::new(Stats::new());

    if let Ok(mut guard) = CANCEL.lock() {
        *guard = Some(cancel.clone());
    }
    if let Ok(mut guard) = STATS.lock() {
        *guard = Some(stats.clone());
    }

    let handle = std::thread::spawn(move || {
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _ = rt.block_on(hs5t_core::run_tunnel(
                config,
                tun_fd,
                (*cancel).clone(),
                stats,
            ));
        }));
    });

    if let Ok(mut guard) = WORKER.lock() {
        *guard = Some(handle);
    }
}

/// Returns `(tx_pkt, tx_bytes, rx_pkt, rx_bytes)` from the active stats cell,
/// or `None` if the tunnel is not running.
fn get_stats_snapshot() -> Option<(u64, u64, u64, u64)> {
    let guard = STATS.lock().ok()?;
    let s = guard.as_ref()?;
    Some(s.snapshot())
}
