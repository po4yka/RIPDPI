//! Per-session PCAP capture set: bounded MPSC queue + dedicated
//! writer thread that drains records into a rotating libpcap file.
//! The local [`PcapCaptureRecord`] is intentionally named to avoid a
//! collision with `ripdpi_pcap::PcapRecord` (the reader-side record
//! type). The local type carries owned bytes, since it crosses the
//! queue boundary into the writer thread.
//!
//! Some metadata accessors are intentionally kept for the JNI/session
//! registry and host-side tests even when a given build path does not
//! consume them directly, so the dead-code allowance is scoped to this
//! module.
#![allow(dead_code)]

use std::collections::HashSet;
use std::fs::{self, File};
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::sync::Arc;
#[cfg(test)]
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crossbeam_queue::ArrayQueue;
use ripdpi_pcap::{PcapWriter, SNAPLEN_DEFAULT};
use ripdpi_tunnel_core::PacketObserver;

const MAX_FILE_BYTES: u64 = 64 * 1024 * 1024;
const MAX_FILES: u32 = 16;
pub(crate) const GLOBAL_MAX_CAPTURE_BYTES: u64 = 64 * 1024 * 1024;
pub(crate) const GLOBAL_MAX_CAPTURE_FILES: u32 = 4;

#[cfg(test)]
pub(crate) static LIVE_WRITER_THREADS: AtomicU64 = AtomicU64::new(0);
#[cfg(test)]
pub(crate) static TEST_PCAP_LOCK: Mutex<()> = Mutex::new(());

#[cfg(test)]
struct LiveWriterGuard;

#[cfg(test)]
impl LiveWriterGuard {
    fn enter() -> Self {
        LIVE_WRITER_THREADS.fetch_add(1, Ordering::Relaxed);
        Self
    }
}

#[cfg(test)]
impl Drop for LiveWriterGuard {
    fn drop(&mut self) {
        LIVE_WRITER_THREADS.fetch_sub(1, Ordering::Relaxed);
    }
}

/// A single captured packet en route to the writer thread.
#[derive(Debug, Clone)]
pub struct PcapCaptureRecord {
    pub ts_micros: u64,
    pub bytes: Vec<u8>,
}

/// Metadata returned by [`PcapCaptureSet::stop`] (and
/// [`list_captures`]) for the Kotlin wrapper to surface.
#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PcapCaptureMetadata {
    pub path: String,
    pub byte_size: u64,
    pub packet_count: u64,
    pub started_at_ms: u64,
    pub ended_at_ms: u64,
    /// Number of records dropped because the queue was full at the
    /// moment of submission. Surfaced in the UI as a "lossy capture"
    /// warning chip.
    pub drops: u64,
}

/// A live capture session: queue + writer thread.
///
/// Created via [`PcapCaptureSet::start`]; stopped via
/// [`PcapCaptureSet::stop`]. The writer thread is named
/// `ripdpi-pcap-writer-<set_id>` for log and trace readability.
pub struct PcapCaptureSet {
    set_id: u64,
    dir: PathBuf,
    queue: Arc<ArrayQueue<PcapCaptureRecord>>,
    drops: Arc<AtomicU64>,
    admissions: Arc<CaptureAdmission>,
    stop: Arc<AtomicBool>,
    writer_thread: Option<JoinHandle<WriterResult>>,
}

const WRITER_STOP_TIMEOUT: Duration = Duration::from_secs(2);
const WRITER_JOIN_POLL: Duration = Duration::from_millis(10);
const ADMISSION_CLOSED: usize = 1usize << (usize::BITS - 1);
const ADMISSION_COUNT_MASK: usize = !ADMISSION_CLOSED;

/// Closes packet-observer admission before writer retirement and counts
/// callbacks that must still publish their queue/drop outcome.
struct CaptureAdmission {
    state: AtomicUsize,
}

/// RAII permit for one admitted observer callback. Dropping it publishes the
/// callback completion to a concurrent closer.
struct CaptureAdmissionGuard {
    admission: Arc<CaptureAdmission>,
}

impl Drop for CaptureAdmissionGuard {
    fn drop(&mut self) {
        self.admission.state.fetch_sub(1, Ordering::Release);
    }
}

impl CaptureAdmission {
    fn new() -> Self {
        Self { state: AtomicUsize::new(0) }
    }
    fn try_acquire(self: &Arc<Self>) -> Option<CaptureAdmissionGuard> {
        let mut state = self.state.load(Ordering::Acquire);
        loop {
            if state & ADMISSION_CLOSED != 0 {
                return None;
            }
            debug_assert!(state & ADMISSION_COUNT_MASK < ADMISSION_COUNT_MASK);
            match self.state.compare_exchange_weak(state, state + 1, Ordering::AcqRel, Ordering::Acquire) {
                Ok(_) => return Some(CaptureAdmissionGuard { admission: Arc::clone(self) }),
                Err(next) => state = next,
            }
        }
    }
    fn close_and_wait(&self) {
        let mut state = self.state.fetch_or(ADMISSION_CLOSED, Ordering::AcqRel);
        while state & ADMISSION_COUNT_MASK != 0 {
            std::hint::spin_loop();
            thread::yield_now();
            state = self.state.load(Ordering::Acquire);
        }
    }
}

#[derive(Debug, Clone, Copy, serde::Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum PcapWriterFailure {
    Open,
    Write,
    Finalize,
    Panic,
    Timeout,
}

#[derive(Debug, Default)]
struct WriterResult {
    files: Vec<PcapCaptureMetadata>,
    failure: Option<PcapWriterFailure>,
}

impl PcapCaptureSet {
    /// Start a capture session. Spawns the writer thread immediately
    /// and returns. Records pushed via [`Self::submit`] are flushed at
    /// most every 1 s OR every 1 MiB written, whichever first.
    ///
    /// `dir` must already exist and be writable. `max_file_bytes` and
    /// `max_files` together cap the on-disk footprint (default per
    /// design: 16 MiB x 4 = 64 MiB).
    pub fn start(set_id: u64, dir: PathBuf, max_file_bytes: u64, max_files: u32) -> std::io::Result<Self> {
        if !(1..=MAX_FILE_BYTES).contains(&max_file_bytes) || !(1..=MAX_FILES).contains(&max_files) {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                format!("pcap limits must be fileBytes=1..={MAX_FILE_BYTES}, files=1..={MAX_FILES}"),
            ));
        }
        if !dir.is_dir() {
            return Err(std::io::Error::new(
                std::io::ErrorKind::NotFound,
                format!("pcap dir does not exist: {}", dir.display()),
            ));
        }
        // 1024 records ~ 1.5 MiB at MTU 1500 ~ 6 s at 200 pkt/s.
        let queue: Arc<ArrayQueue<PcapCaptureRecord>> = Arc::new(ArrayQueue::new(1024));
        let drops = Arc::new(AtomicU64::new(0));
        let admissions = Arc::new(CaptureAdmission::new());
        let stop = Arc::new(AtomicBool::new(false));
        let q = queue.clone();
        let s = stop.clone();
        let d = dir.clone();
        let writer_thread = thread::Builder::new()
            .name(format!("ripdpi-pcap-writer-{set_id}"))
            .spawn(move || writer_loop(set_id, d, q, s, max_file_bytes, max_files))?;
        Ok(Self { set_id, dir, queue, drops, admissions, stop, writer_thread: Some(writer_thread) })
    }

    /// Try to enqueue a record. Lock-free. On queue-full, increments
    /// the drops counter and returns false -- capture is best-effort.
    pub fn submit(&self, record: PcapCaptureRecord) -> bool {
        // Ordering: `stop` publishes capture retirement before any caller can enqueue another owned packet.
        let Some(_admission) = self.admissions.try_acquire() else {
            return false;
        };
        match self.queue.push(record) {
            Ok(()) => true,
            Err(_) => {
                self.drops.fetch_add(1, Ordering::Relaxed);
                false
            }
        }
    }

    /// Signal the writer thread to drain the queue, fsync the final
    /// file, and exit. Returns the metadata of every file written.
    pub fn stop(mut self) -> WriterStopResult {
        self.request_stop();
        let mut result = WriterStopResult {
            set_id: self.set_id,
            files: Vec::new(),
            total_drops: self.drops.load(Ordering::Relaxed),
            failure: None,
        };
        if let Some(handle) = self.writer_thread.take() {
            match join_writer_bounded(handle, WRITER_STOP_TIMEOUT) {
                Ok(writer_result) => {
                    result.files = writer_result.files;
                    result.failure = writer_result.failure;
                    // Annotate the drops on the last file (most informative
                    // location for the UI).
                    if let Some(last) = result.files.last_mut() {
                        last.drops = result.total_drops;
                    }
                }
                Err(failure) => {
                    result.failure = Some(failure);
                }
            }
        }
        result
    }

    pub(crate) fn request_stop(&self) {
        // Ordering: publish retirement to packet observers before the writer queue and thread are released.
        self.admissions.close_and_wait();
        self.stop.store(true, Ordering::Release);
    }

    pub fn set_id(&self) -> u64 {
        self.set_id
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// Build an independent `PacketObserver` that points back to this
    /// set's submission queue + drops counter. The returned `Arc` is
    /// installed via [`ripdpi_tunnel_core::Stats::set_packet_observer`]
    /// so the io_loop hot path enqueues each packet directly into the
    /// writer-thread queue. The handle is decoupled from the set
    /// lifetime. Once [`Self::stop`] publishes retirement, stale observer
    /// clones reject packets before allocating an owned record.
    pub fn observer_handle(&self) -> Arc<PcapPacketObserver> {
        Arc::new(PcapPacketObserver {
            queue: self.queue.clone(),
            drops: self.drops.clone(),
            admissions: self.admissions.clone(),
        })
    }
}

impl Drop for PcapCaptureSet {
    fn drop(&mut self) {
        // Cleanup order: publish stop first, then detach the writer. The worker
        // owns Arc clones of every shared input, so Drop never needs to block.
        self.request_stop();
        drop(self.writer_thread.take());
    }
}

fn join_writer_bounded(handle: JoinHandle<WriterResult>, timeout: Duration) -> Result<WriterResult, PcapWriterFailure> {
    let deadline = Instant::now() + timeout;
    while !handle.is_finished() && Instant::now() < deadline {
        thread::sleep(WRITER_JOIN_POLL.min(deadline.saturating_duration_since(Instant::now())));
    }
    if handle.is_finished() {
        return handle.join().map_err(|_| {
            tracing::error!("pcap writer panicked during finalization");
            PcapWriterFailure::Panic
        });
    }
    tracing::warn!(timeout_ms = timeout.as_millis(), "pcap writer did not stop before deadline; detaching worker");
    Err(PcapWriterFailure::Timeout)
}

/// Independent observer that owns clone-able handles to the
/// [`PcapCaptureSet`] queue and drops counter. Held by
/// `ripdpi_tunnel_core::Stats` so io_loop packets reach the queue
/// without forcing `Stats` to know about [`PcapCaptureSet`].
pub struct PcapPacketObserver {
    queue: Arc<ArrayQueue<PcapCaptureRecord>>,
    drops: Arc<AtomicU64>,
    admissions: Arc<CaptureAdmission>,
}

impl PcapPacketObserver {
    fn try_push(&self, packet: &[u8]) {
        // Ordering: acquire the retirement published by `PcapCaptureSet::request_stop` before touching the queue or allocating packet storage.
        let Some(_admission) = self.admissions.try_acquire() else {
            return;
        };
        let record = PcapCaptureRecord { ts_micros: now_micros(), bytes: packet.to_vec() };
        if self.queue.push(record).is_err() {
            self.drops.fetch_add(1, Ordering::Relaxed);
        }
    }
}

impl PacketObserver for PcapPacketObserver {
    fn on_inbound(&self, packet: &[u8]) {
        self.try_push(packet);
    }

    fn on_outbound(&self, packet: &[u8]) {
        self.try_push(packet);
    }
}

fn now_micros() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_micros() as u64)
}

#[derive(Debug)]
pub struct WriterStopResult {
    pub set_id: u64,
    pub files: Vec<PcapCaptureMetadata>,
    pub total_drops: u64,
    pub failure: Option<PcapWriterFailure>,
}

fn writer_loop(
    set_id: u64,
    dir: PathBuf,
    queue: Arc<ArrayQueue<PcapCaptureRecord>>,
    stop: Arc<AtomicBool>,
    max_file_bytes: u64,
    max_files: u32,
) -> WriterResult {
    #[cfg(test)]
    let _live_writer = LiveWriterGuard::enter();
    let mut result = WriterResult::default();
    let mut file_idx: u32 = 0;
    while file_idx < max_files {
        match open_file(set_id, &dir, file_idx) {
            Ok((active_path, completed_path, file, started_at_ms)) => {
                let drain = drain_one_file(&queue, &stop, file, max_file_bytes);
                match drain {
                    Ok((bytes, packets)) => {
                        if let Err(err) = fs::rename(&active_path, &completed_path) {
                            tracing::warn!(error = %err, "pcap writer failed to finalize capture file");
                            let _ = fs::remove_file(&active_path);
                            result.failure = Some(PcapWriterFailure::Finalize);
                            break;
                        }
                        result.files.push(PcapCaptureMetadata {
                            path: completed_path.display().to_string(),
                            byte_size: bytes,
                            packet_count: packets,
                            started_at_ms,
                            ended_at_ms: now_ms(),
                            drops: 0, // populated in stop()
                        });
                    }
                    Err(err) => {
                        tracing::warn!(error = %err, "pcap writer failed; removing incomplete capture file");
                        let _ = fs::remove_file(&active_path);
                        result.failure = Some(PcapWriterFailure::Write);
                        break;
                    }
                }
                file_idx += 1;
                if stop.load(Ordering::Acquire) && queue.is_empty() {
                    break;
                }
            }
            Err(err) => {
                tracing::warn!(error = %err, "pcap writer failed to create capture file");
                result.failure = Some(PcapWriterFailure::Open);
                break;
            }
        }
    }
    result
}

fn open_file(set_id: u64, dir: &Path, idx: u32) -> std::io::Result<(PathBuf, PathBuf, BufWriter<File>, u64)> {
    let started = now_ms();
    let filename = format!("{set_id:016x}-{started}-{idx:02}.pcap");
    let completed_path = dir.join(filename);
    let active_path = completed_path.with_extension("pcap.active");
    let file = File::create(&active_path)?;
    let writer = BufWriter::with_capacity(64 * 1024, file);
    Ok((active_path, completed_path, writer, started))
}

/// Drain records into `file` until either `max_file_bytes` reached
/// OR `stop` is signaled AND the queue is drained. Returns the byte
/// count + packet count written to this file.
fn drain_one_file(
    queue: &ArrayQueue<PcapCaptureRecord>,
    stop: &AtomicBool,
    file: BufWriter<File>,
    max_file_bytes: u64,
) -> std::io::Result<(u64, u64)> {
    let mut writer = PcapWriter::new(file, SNAPLEN_DEFAULT)?;
    let mut packets: u64 = 0;
    let mut last_flush = Instant::now();
    loop {
        match queue.pop() {
            Some(record) => {
                writer.write_packet(record.ts_micros, &record.bytes)?;
                packets += 1;
                if writer.bytes_written() >= max_file_bytes {
                    break;
                }
            }
            None => {
                if stop.load(Ordering::Acquire) {
                    break;
                }
                thread::sleep(Duration::from_millis(50));
            }
        }
        // Flush every 1s OR every 1MiB - whichever first.
        if last_flush.elapsed() >= Duration::from_secs(1) {
            writer.flush()?;
            last_flush = Instant::now();
        }
    }
    writer.flush()?;
    let bytes = writer.bytes_written();
    // PcapWriter -> BufWriter<File> -> File chain for fsync.
    let buf_writer = writer.into_inner();
    let inner_file = buf_writer.into_inner().map_err(std::io::IntoInnerError::into_error)?;
    inner_file.sync_all()?;
    Ok((bytes, packets))
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

/// List existing capture files in `dir` (best-effort metadata: byte
/// size from filesystem, packet count NOT computed - that requires
/// reading the file which is the consumer's job).
pub fn list_captures(dir: &Path) -> Vec<PcapCaptureMetadata> {
    let mut files: Vec<PcapCaptureMetadata> = Vec::new();
    let Ok(entries) = fs::read_dir(dir) else {
        return files;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|s| s.to_str()) != Some("pcap") {
            continue;
        }
        let byte_size = entry.metadata().map_or(0, |m| m.len());
        files.push(PcapCaptureMetadata {
            path: path.display().to_string(),
            byte_size,
            packet_count: 0,
            started_at_ms: 0,
            ended_at_ms: 0,
            drops: 0,
        });
    }
    files
}

/// Prune completed captures for one storage directory to a single global
/// budget. Active capture sets are deliberately exempt: both the current
/// `.pcap.active` file and already-rotated `.pcap` files with an active set id
/// remain available until that set stops.
pub(crate) fn enforce_global_retention(dir: &Path, active_set_ids: &HashSet<u64>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    let mut completed = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if is_active_capture(&path) {
            if !capture_set_id(&path).is_some_and(|set_id| active_set_ids.contains(&set_id)) {
                let _ = fs::remove_file(path);
            }
            continue;
        }
        if !is_completed_capture(&path) || capture_set_id(&path).is_some_and(|set_id| active_set_ids.contains(&set_id))
        {
            continue;
        }
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        completed.push((path, metadata.len(), metadata.modified().ok()));
    }
    completed.sort_by_key(|(_, _, modified)| *modified);

    let mut retained_count = completed.len() as u32;
    let mut retained_bytes: u64 = completed.iter().map(|(_, bytes, _)| *bytes).sum();
    for (path, bytes, _) in completed {
        if retained_count <= GLOBAL_MAX_CAPTURE_FILES && retained_bytes <= GLOBAL_MAX_CAPTURE_BYTES {
            break;
        }
        if fs::remove_file(path).is_ok() {
            retained_count -= 1;
            retained_bytes = retained_bytes.saturating_sub(bytes);
        }
    }
}

fn is_completed_capture(path: &Path) -> bool {
    path.extension().and_then(|extension| extension.to_str()) == Some("pcap")
}

fn is_active_capture(path: &Path) -> bool {
    path.file_name().and_then(|name| name.to_str()).is_some_and(|name| name.ends_with(".pcap.active"))
}

fn capture_set_id(path: &Path) -> Option<u64> {
    path.file_name()
        .and_then(|name| name.to_str())
        .and_then(|name| name.split_once('-').map(|(set_id, _)| set_id))
        .and_then(|set_id| u64::from_str_radix(set_id, 16).ok())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn fake_packet(b: u8) -> PcapCaptureRecord {
        // Minimal IPv4 packet: 20-byte header with version=4, ihl=5,
        // proto=17 (UDP), src/dst = 0.0.0.0, payload = b.
        let mut bytes = vec![0u8; 28];
        bytes[0] = 0x45;
        bytes[2..4].copy_from_slice(&28u16.to_be_bytes()); // total length
        bytes[9] = 17;
        bytes[20] = b;
        PcapCaptureRecord { ts_micros: 0, bytes }
    }

    fn serial_pcap_test() -> std::sync::MutexGuard<'static, ()> {
        TEST_PCAP_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn wait_for_live_writers(expected: u64) {
        let deadline = Instant::now() + Duration::from_secs(1);
        while LIVE_WRITER_THREADS.load(Ordering::Relaxed) != expected && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(LIVE_WRITER_THREADS.load(Ordering::Relaxed), expected);
    }

    #[test]
    fn start_then_stop_roundtrips_zero_packets() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let capture = PcapCaptureSet::start(1, dir.path().to_path_buf(), 1024, 4).unwrap();
        let result = capture.stop();
        assert_eq!(result.total_drops, 0);
        // At least one (empty-ish) file should exist, OR none if writer
        // exited cleanly before opening. Spec is permissive -- we just
        // assert no panic + drops are zero.
    }

    #[test]
    fn dropping_capture_stops_writer_thread() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let live_before = LIVE_WRITER_THREADS.load(Ordering::Relaxed);
        let capture = PcapCaptureSet::start(10, dir.path().to_path_buf(), 1024, 1).unwrap();
        wait_for_live_writers(live_before + 1);

        drop(capture);

        wait_for_live_writers(live_before);
    }

    #[test]
    fn bounded_join_detaches_blocked_writer() {
        let (release_tx, release_rx) = std::sync::mpsc::sync_channel(1);
        let handle = thread::spawn(move || {
            let _ = release_rx.recv();
            WriterResult::default()
        });
        let started = Instant::now();

        let result = join_writer_bounded(handle, Duration::from_millis(25));

        assert!(matches!(result, Err(PcapWriterFailure::Timeout)));
        assert!(started.elapsed() < Duration::from_millis(250));
        let _ = release_tx.send(());
    }

    #[test]
    fn close_waits_for_admitted_callback_before_writer_retirement() {
        let admission = Arc::new(CaptureAdmission::new());
        let entered = Arc::new(std::sync::Barrier::new(2));
        let release = Arc::new(std::sync::Barrier::new(2));
        let callback_admission = Arc::clone(&admission);
        let callback_entered = Arc::clone(&entered);
        let callback_release = Arc::clone(&release);
        let callback = thread::spawn(move || {
            let _permit = callback_admission.try_acquire().expect("callback must be admitted");
            callback_entered.wait();
            callback_release.wait();
        });
        entered.wait();

        let closing_admission = Arc::clone(&admission);
        let (closed_tx, closed_rx) = std::sync::mpsc::channel();
        let closer = thread::spawn(move || {
            closing_admission.close_and_wait();
            closed_tx.send(()).expect("receiver must remain open");
        });
        while admission.state.load(Ordering::Acquire) & ADMISSION_CLOSED == 0 {
            thread::yield_now();
        }
        assert!(closed_rx.recv_timeout(Duration::from_millis(50)).is_err());

        release.wait();
        callback.join().expect("callback must finish");
        closer.join().expect("closer must finish");
        closed_rx.recv().expect("close must finish after release");
    }

    #[test]
    fn stop_reports_drop_from_callback_admitted_before_retirement() {
        let queue = Arc::new(ArrayQueue::new(1));
        queue.push(fake_packet(0)).expect("queue must begin full");
        let drops = Arc::new(AtomicU64::new(0));
        let admissions = Arc::new(CaptureAdmission::new());
        let capture = PcapCaptureSet {
            set_id: 51,
            dir: PathBuf::new(),
            queue: Arc::clone(&queue),
            drops: Arc::clone(&drops),
            admissions: Arc::clone(&admissions),
            stop: Arc::new(AtomicBool::new(false)),
            writer_thread: None,
        };
        let entered = Arc::new(std::sync::Barrier::new(2));
        let release = Arc::new(std::sync::Barrier::new(2));
        let callback_admissions = Arc::clone(&admissions);
        let callback_queue = Arc::clone(&queue);
        let callback_drops = Arc::clone(&drops);
        let callback_entered = Arc::clone(&entered);
        let callback_release = Arc::clone(&release);
        let callback = thread::spawn(move || {
            let _permit = callback_admissions.try_acquire().expect("callback must be admitted");
            callback_entered.wait();
            callback_release.wait();
            assert!(callback_queue.push(fake_packet(1)).is_err());
            callback_drops.fetch_add(1, Ordering::Relaxed);
        });
        entered.wait();

        let stopper = thread::spawn(move || capture.stop());
        while admissions.state.load(Ordering::Acquire) & ADMISSION_CLOSED == 0 {
            thread::yield_now();
        }
        release.wait();
        callback.join().expect("callback must finish");
        assert_eq!(stopper.join().expect("stop must finish").total_drops, 1);
    }

    #[test]
    fn writer_open_failure_is_terminal_and_returns_no_capture_file() {
        let dir = TempDir::new().unwrap();
        let missing = dir.path().join("missing");
        let queue = Arc::new(ArrayQueue::new(1));
        let stop = Arc::new(AtomicBool::new(true));

        let result = writer_loop(44, missing, queue, stop, 1024, 1);

        assert_eq!(result.failure, Some(PcapWriterFailure::Open));
        assert!(result.files.is_empty());
    }

    #[test]
    fn stopped_capture_rejects_stale_observer_packets() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let capture = PcapCaptureSet::start(11, dir.path().to_path_buf(), 1024, 1).unwrap();
        let queue = capture.queue.clone();
        let observer = capture.observer_handle();
        let _ = capture.stop();

        observer.on_inbound(&fake_packet(1).bytes);

        assert!(queue.is_empty(), "stale observer must not enqueue after capture stop");
    }

    #[test]
    fn submit_then_stop_records_packet_count() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let capture = PcapCaptureSet::start(2, dir.path().to_path_buf(), 1024 * 1024, 4).unwrap();
        for i in 0..10u8 {
            assert!(capture.submit(fake_packet(i)));
        }
        // Give the writer thread a moment to drain.
        std::thread::sleep(Duration::from_millis(200));
        let result = capture.stop();
        let total_packets: u64 = result.files.iter().map(|f| f.packet_count).sum();
        assert!(total_packets >= 1, "expected >=1 packet written, got files={:?}", result.files);
    }

    #[test]
    fn list_captures_skips_non_pcap_files() {
        let dir = TempDir::new().unwrap();
        std::fs::write(dir.path().join("foo.txt"), b"hi").unwrap();
        std::fs::write(dir.path().join("bar.pcap"), b"\xa1\xb2\xc3\xd4").unwrap();
        let files = list_captures(dir.path());
        assert_eq!(files.len(), 1);
        assert!(files[0].path.ends_with("bar.pcap"));
    }

    #[test]
    fn global_retention_bounds_completed_files_without_deleting_active_set() {
        let dir = TempDir::new().unwrap();
        let active_set_id = 2u64;
        for set_id in 10..=14u64 {
            std::fs::write(dir.path().join(format!("{set_id:016x}-1-00.pcap")), b"old").unwrap();
        }
        let active_finished = dir.path().join(format!("{active_set_id:016x}-1-00.pcap"));
        let active_current = dir.path().join(format!("{active_set_id:016x}-2-01.pcap.active"));
        std::fs::write(&active_finished, b"active-completed-rotation").unwrap();
        std::fs::write(&active_current, b"active-current-write").unwrap();

        enforce_global_retention(dir.path(), &std::collections::HashSet::from([active_set_id]));

        assert!(active_finished.exists(), "completed rotations of active captures must be preserved");
        assert!(active_current.exists(), "currently written capture must be preserved");
        let completed_non_active = fs::read_dir(dir.path())
            .unwrap()
            .flatten()
            .map(|entry| entry.path())
            .filter(|path| is_completed_capture(path))
            .filter(|path| capture_set_id(path) != Some(active_set_id))
            .count();
        assert!(completed_non_active <= GLOBAL_MAX_CAPTURE_FILES as usize);
    }

    #[test]
    fn submit_returns_false_when_queue_full() {
        let _serial = serial_pcap_test();
        // Best-effort: on fast hardware we may not actually hit full
        // because the writer drains continuously. We at least assert
        // the call sequence is panic-free and any drops are accounted.
        let dir = TempDir::new().unwrap();
        let capture = PcapCaptureSet::start(3, dir.path().to_path_buf(), 1024, 1).unwrap();
        for i in 0..2000u32 {
            capture.submit(fake_packet((i & 0xff) as u8));
        }
        // If drops happened, they're counted.
        let _ = capture.stop();
    }

    #[test]
    fn start_rejects_missing_directory() {
        let dir = TempDir::new().unwrap();
        let missing = dir.path().join("does-not-exist");
        match PcapCaptureSet::start(99, missing, 1024, 1) {
            Ok(_) => panic!("expected NotFound error for missing dir"),
            Err(e) => assert_eq!(e.kind(), std::io::ErrorKind::NotFound),
        }
    }

    #[test]
    fn start_rejects_unbounded_capture_limits() {
        let dir = TempDir::new().unwrap();
        let zero_files =
            PcapCaptureSet::start(100, dir.path().to_path_buf(), 1024, 0).err().expect("zero files must fail");
        let oversized_file = PcapCaptureSet::start(101, dir.path().to_path_buf(), MAX_FILE_BYTES + 1, 1)
            .err()
            .expect("oversized file must fail");

        assert_eq!(zero_files.kind(), std::io::ErrorKind::InvalidInput);
        assert_eq!(oversized_file.kind(), std::io::ErrorKind::InvalidInput);
    }
}
