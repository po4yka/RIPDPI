//! JNI-callable capture-set management for the PCAP bridge.
//!
//! Each tunnel session can have ONE active capture-set at a time
//! (extensibility for multiple-per-session is intentionally deferred).
//! The per-session registry is keyed by tunnel
//! handle (the same opaque `jlong` that `jniCreate` returns) so the
//! stop / list / redact entries can locate the capture without
//! re-plumbing it through Kotlin.
//!
//! The four entry functions in this module take Rust types (PathBuf,
//! i64, etc.) so they are unit-testable without spinning up a JVM.
//! The thin JNI wrappers in `crate::entry` convert JNI-layer types
//! (JString / jlong / jint) and dispatch into here.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufReader, BufWriter};
use std::os::fd::{FromRawFd, OwnedFd};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};

use ripdpi_pcap::rewrite_endpoints;
#[cfg(test)]
use ripdpi_tunnel_core::PacketObserver;
use ripdpi_tunnel_core::Stats;

use crate::pcap::{PcapCaptureSet, WriterStopResult, enforce_global_retention, list_captures};

struct ActiveCapture {
    set: PcapCaptureSet,
    stats: Arc<Stats>,
}

static REGISTRY: LazyLock<Mutex<HashMap<i64, ActiveCapture>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT_SET_ID: AtomicU64 = AtomicU64::new(1);

/// Inner entry: start a capture set bound to the given session handle.
/// Returns a positive capture-set ID on success, 0 on failure
/// (directory missing, writer-thread spawn failed, registry mutex
/// poisoned, or a capture is already active for this session).
pub(crate) fn pcap_start_entry(
    session_handle: i64,
    stats: Arc<Stats>,
    capture_dir: PathBuf,
    max_file_bytes: u64,
    max_files: u32,
) -> i64 {
    let Ok(mut reg) = REGISTRY.lock() else {
        log::error!("pcap_start_entry: registry mutex poisoned");
        return 0;
    };
    if reg.contains_key(&session_handle) {
        log::warn!("pcap_start_entry: session {session_handle} already has an active capture");
        return 0;
    }
    let active_set_ids =
        reg.values().filter(|active| active.set.dir() == capture_dir).map(|active| active.set.set_id()).collect();
    enforce_global_retention(&capture_dir, &active_set_ids);
    let set_id = NEXT_SET_ID.fetch_add(1, Ordering::Relaxed);
    let set = match PcapCaptureSet::start(set_id, capture_dir, max_file_bytes, max_files) {
        Ok(s) => s,
        Err(err) => {
            log::warn!("pcap_start_entry: PcapCaptureSet::start failed: {err}");
            return 0;
        }
    };
    let observer = set.observer_handle();
    reg.insert(session_handle, ActiveCapture { set, stats: stats.clone() });
    stats.set_packet_observer(observer);
    set_id as i64
}

fn stop_capture(session_handle: i64) -> Option<WriterStopResult> {
    let Ok(mut reg) = REGISTRY.lock() else {
        log::error!("pcap stop: registry mutex poisoned");
        return None;
    };
    let active = reg.remove(&session_handle)?;
    active.set.request_stop();
    active.stats.clear_packet_observer();
    let capture_dir = active.set.dir().to_path_buf();
    let result = active.set.stop();
    let active_set_ids =
        reg.values().filter(|other| other.set.dir() == capture_dir).map(|other| other.set.set_id()).collect();
    enforce_global_retention(&capture_dir, &active_set_ids);
    Some(result)
}

/// Inner entry: stop the capture-set bound to the session and return
/// JSON metadata (an array of file descriptors per `PcapCaptureMetadata`).
/// Returns `"[]"` if no capture is bound to this handle, the registry
/// mutex is poisoned, or JSON serialization fails.
pub(crate) fn pcap_stop_entry(session_handle: i64) -> String {
    let Some(result) = stop_capture(session_handle) else { return "[]".to_string() };
    serde_json::to_string(&result.files).unwrap_or_else(|err| {
        log::error!("pcap_stop_entry: serialize files: {err}");
        "[]".to_string()
    })
}

pub(crate) fn pcap_retire_entry(session_handle: i64) {
    let _ = stop_capture(session_handle);
}

/// Inner entry: list captures on disk for the given directory. Used by
/// the capture-list UI to enumerate previously-captured files.
pub(crate) fn pcap_list_captures_entry(capture_dir: PathBuf) -> String {
    let files = list_captures(&capture_dir);
    serde_json::to_string(&files).unwrap_or_else(|err| {
        log::error!("pcap_list_captures_entry: serialize files: {err}");
        "[]".to_string()
    })
}

/// Adopt the destination fd transferred by `ParcelFileDescriptor.detachFd()`.
/// This must be called before any fallible JNI or filesystem work so every
/// exit path closes the transferred descriptor.
pub(crate) fn adopt_pcap_dest_fd(dest_fd: i32) -> Option<OwnedFd> {
    if dest_fd < 0 {
        return None;
    }
    // SAFETY: Kotlin transfers exclusive ownership via
    // `ParcelFileDescriptor.detachFd()`. The returned `OwnedFd` is therefore
    // the sole owner and closes the descriptor exactly once on drop.
    Some(unsafe { OwnedFd::from_raw_fd(dest_fd) })
}

/// Inner entry: read `source_path`, redact endpoint addresses + zero
/// transport checksums, write the rewritten pcap stream to `dest_fd`.
/// Returns bytes-written on success or 0 on any failure (source open
/// failure, invalid fd, IO error during rewrite).
///
pub(crate) fn pcap_redact_entry(source_path: PathBuf, dest_owned: OwnedFd) -> u64 {
    let source = match File::open(&source_path) {
        Ok(f) => BufReader::new(f),
        Err(err) => {
            log::warn!("pcap_redact_entry: open source {}: {err}", source_path.display());
            return 0;
        }
    };
    let dest_file = File::from(dest_owned);
    let dest = BufWriter::new(dest_file);
    rewrite_endpoints(source, dest).unwrap_or_else(|err| {
        log::warn!("pcap_redact_entry: rewrite_endpoints: {err}");
        0
    })
}

/// Return a `PacketObserver` view of the active capture-set for the
/// given session, suitable for installing via
/// `Stats::set_packet_observer`. `None` if no capture is bound.
#[cfg(test)]
pub(crate) fn observer_for_session(session_handle: i64) -> Option<Arc<dyn PacketObserver>> {
    let reg = REGISTRY.lock().ok()?;
    Some(reg.get(&session_handle)?.set.observer_handle())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::OpenOptions;
    use std::os::fd::IntoRawFd;
    use std::sync::atomic::{AtomicI64, Ordering as TestOrdering};
    use tempfile::TempDir;

    /// Per-test session handles are drawn from a high-numbered atomic
    /// counter so concurrent tests cannot collide on the shared global
    /// REGISTRY map.
    static TEST_SESSION_HANDLE: AtomicI64 = AtomicI64::new(1_000_000);

    fn next_test_handle() -> i64 {
        TEST_SESSION_HANDLE.fetch_add(1, TestOrdering::Relaxed)
    }

    fn test_stats() -> Arc<Stats> {
        Arc::new(Stats::new())
    }

    fn serial_pcap_test() -> std::sync::MutexGuard<'static, ()> {
        crate::pcap::TEST_PCAP_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn wait_for_live_writer_count(expected: u64) {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(1);
        while crate::pcap::LIVE_WRITER_THREADS.load(TestOrdering::Relaxed) != expected
            && std::time::Instant::now() < deadline
        {
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        assert_eq!(crate::pcap::LIVE_WRITER_THREADS.load(TestOrdering::Relaxed), expected);
    }

    #[test]
    fn pcap_start_entry_then_stop_entry_roundtrips_metadata() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let handle = next_test_handle();
        let set_id = pcap_start_entry(handle, test_stats(), dir.path().to_path_buf(), 1024 * 1024, 4);
        assert!(set_id > 0, "expected positive set id, got {set_id}");
        let json = pcap_stop_entry(handle);
        // Must be parseable JSON array (possibly empty).
        let parsed: serde_json::Value = serde_json::from_str(&json).expect("metadata must be valid json");
        assert!(parsed.is_array(), "stop should return a JSON array, got {parsed}");
    }

    #[test]
    fn pcap_stop_entry_unknown_session_returns_empty_array() {
        let handle = next_test_handle();
        assert_eq!(pcap_stop_entry(handle), "[]");
    }

    #[test]
    fn pcap_start_entry_rejects_missing_directory() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let missing = dir.path().join("does-not-exist");
        let handle = next_test_handle();
        let set_id = pcap_start_entry(handle, test_stats(), missing, 1024, 1);
        assert_eq!(set_id, 0, "expected 0 (failure) for missing dir, got {set_id}");
        // Nothing should be left in the registry.
        assert_eq!(pcap_stop_entry(handle), "[]");
    }

    #[test]
    fn pcap_start_and_stop_enforce_global_completed_capture_budget() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        for set_id in 10..=14u64 {
            std::fs::write(dir.path().join(format!("{set_id:016x}-1-00.pcap")), b"old").unwrap();
        }
        let handle = next_test_handle();

        assert!(pcap_start_entry(handle, test_stats(), dir.path().to_path_buf(), 1024, 1) > 0);
        assert!(
            list_captures(dir.path()).len() <= crate::pcap::GLOBAL_MAX_CAPTURE_FILES as usize,
            "start must prune completed captures before creating a new set",
        );

        for set_id in 20..=24u64 {
            std::fs::write(dir.path().join(format!("{set_id:016x}-1-00.pcap")), b"old").unwrap();
        }
        let _ = pcap_stop_entry(handle);

        assert!(
            list_captures(dir.path()).len() <= crate::pcap::GLOBAL_MAX_CAPTURE_FILES as usize,
            "stop must prune completed captures after finalizing the set",
        );
    }

    #[test]
    fn pcap_start_entry_refuses_double_start_for_same_session() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let handle = next_test_handle();
        let first = pcap_start_entry(handle, test_stats(), dir.path().to_path_buf(), 1024, 1);
        assert!(first > 0);
        let second = pcap_start_entry(handle, test_stats(), dir.path().to_path_buf(), 1024, 1);
        assert_eq!(second, 0, "second start for same session must return 0");
        let other_handle = next_test_handle();
        let next = pcap_start_entry(other_handle, test_stats(), dir.path().to_path_buf(), 1024, 1);
        assert_eq!(next, first + 1, "rejected double-start must not allocate or spawn a capture");
        let _ = pcap_stop_entry(handle);
        let _ = pcap_stop_entry(other_handle);
    }

    #[test]
    fn pcap_list_captures_entry_empty_dir_returns_empty_array() {
        let dir = TempDir::new().unwrap();
        let json = pcap_list_captures_entry(dir.path().to_path_buf());
        let parsed: serde_json::Value = serde_json::from_str(&json).expect("must parse");
        assert!(parsed.is_array());
        assert_eq!(parsed.as_array().unwrap().len(), 0);
    }

    #[test]
    fn pcap_list_captures_entry_skips_non_pcap_files() {
        let dir = TempDir::new().unwrap();
        std::fs::write(dir.path().join("foo.txt"), b"hi").unwrap();
        std::fs::write(dir.path().join("real.pcap"), b"\xa1\xb2\xc3\xd4").unwrap();
        let json = pcap_list_captures_entry(dir.path().to_path_buf());
        let parsed: serde_json::Value = serde_json::from_str(&json).expect("must parse");
        let arr = parsed.as_array().expect("must be array");
        assert_eq!(arr.len(), 1, "should only list .pcap files");
    }

    #[test]
    fn pcap_redact_entry_invalid_source_returns_zero() {
        let dir = TempDir::new().unwrap();
        let dest = dir.path().join("out.pcap");
        let file = File::create(&dest).expect("create dest");
        // IntoRawFd transfers ownership; the entry function takes
        // responsibility for closing.
        let dest_fd = file.into_raw_fd();
        let dest_owned = adopt_pcap_dest_fd(dest_fd).expect("adopt destination fd");
        let bytes = pcap_redact_entry(dir.path().join("does-not-exist.pcap"), dest_owned);
        assert_eq!(bytes, 0);
        // SAFETY: `dest_fd` is used only as an integer probe after ownership
        // was consumed; F_GETFD does not dereference memory or take ownership.
        assert_eq!(unsafe { libc::fcntl(dest_fd, libc::F_GETFD) }, -1);
        assert_eq!(std::io::Error::last_os_error().raw_os_error(), Some(libc::EBADF));
    }

    #[test]
    fn pcap_redact_entry_negative_fd_returns_zero() {
        assert!(adopt_pcap_dest_fd(-1).is_none());
    }

    #[test]
    fn pcap_redact_entry_roundtrips_an_empty_pcap() {
        // Author a minimal but valid pcap (just the 24-byte global
        // header, no records). rewrite_endpoints should accept it and
        // emit a header-only output pcap of the same shape.
        let dir = TempDir::new().unwrap();
        let src_path = dir.path().join("src.pcap");
        {
            use ripdpi_pcap::{PcapWriter, SNAPLEN_DEFAULT};
            let src_file = File::create(&src_path).expect("create src");
            let mut writer = PcapWriter::new(src_file, SNAPLEN_DEFAULT).expect("pcap header");
            writer.flush().expect("flush header");
        }
        let dest_path = dir.path().join("dest.pcap");
        let dest_file = OpenOptions::new().write(true).create(true).truncate(true).open(&dest_path).expect("dest");
        let dest_fd = dest_file.into_raw_fd();
        let dest_owned = adopt_pcap_dest_fd(dest_fd).expect("adopt destination fd");
        let bytes = pcap_redact_entry(src_path, dest_owned);
        // 24-byte pcap global header must be present.
        assert!(bytes >= 24, "expected >=24 bytes (pcap global header), got {bytes}");
    }

    #[test]
    fn observer_for_session_returns_none_when_no_capture() {
        let handle = next_test_handle();
        assert!(observer_for_session(handle).is_none());
    }

    #[test]
    fn observer_for_session_returns_some_when_capture_active() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let handle = next_test_handle();
        let set_id = pcap_start_entry(handle, test_stats(), dir.path().to_path_buf(), 1024, 1);
        assert!(set_id > 0);
        let observer = observer_for_session(handle);
        assert!(observer.is_some(), "expected observer Arc once capture is active");
        // Drop the observer Arc explicitly so the stop path can
        // try_unwrap the set without contention.
        drop(observer);
        let _ = pcap_stop_entry(handle);
    }

    #[test]
    fn observer_for_session_packets_reach_capture_queue() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let handle = next_test_handle();
        let set_id = pcap_start_entry(handle, test_stats(), dir.path().to_path_buf(), 1024 * 1024, 4);
        assert!(set_id > 0);
        // Build a tiny IPv4 packet (header only).
        let mut pkt = vec![0u8; 28];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&28u16.to_be_bytes());
        pkt[9] = 17;
        let observer = observer_for_session(handle).expect("observer present");
        for _ in 0..3 {
            observer.on_inbound(&pkt);
            observer.on_outbound(&pkt);
        }
        // Give the writer thread time to drain.
        std::thread::sleep(std::time::Duration::from_millis(150));
        drop(observer);
        let json = pcap_stop_entry(handle);
        let parsed: serde_json::Value = serde_json::from_str(&json).expect("metadata json");
        let arr = parsed.as_array().expect("array");
        let total_packets: u64 =
            arr.iter().filter_map(|f| f.get("packetCount").and_then(serde_json::Value::as_u64)).sum();
        assert!(total_packets >= 1, "expected >=1 packet captured, got {total_packets}: {json}");
    }

    #[test]
    fn retiring_capture_stops_writer_and_clears_stats_observer() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let handle = next_test_handle();
        let stats = test_stats();
        let live_before = crate::pcap::LIVE_WRITER_THREADS.load(TestOrdering::Relaxed);
        let set_id = pcap_start_entry(handle, stats.clone(), dir.path().to_path_buf(), 1024, 1);
        assert!(set_id > 0);
        wait_for_live_writer_count(live_before + 1);
        assert!(stats.packet_observer_present.load(TestOrdering::Relaxed));

        pcap_retire_entry(handle);

        wait_for_live_writer_count(live_before);
        assert!(!stats.packet_observer_present.load(TestOrdering::Relaxed));
        assert!(observer_for_session(handle).is_none());
    }
}
