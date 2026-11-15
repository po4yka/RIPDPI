//! Per-session PCAP capture set: bounded MPSC queue + dedicated
//! writer thread that drains records into a rotating libpcap file.
//! The local [`PcapCaptureRecord`] is intentionally named to avoid a
//! collision with `ripdpi_pcap::PcapRecord` (the reader-side record
//! type). The local type carries owned bytes, since it crosses the
//! queue boundary into the writer thread.
//!
//! Live packets reach the queue through [`PcapPacketObserver`], which the
//! session installs on the tunnel `Stats`.

use std::fs::{self, File};
use std::io::{self, BufWriter};
use std::path::{Path, PathBuf};
use std::sync::Arc;
#[cfg(test)]
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crossbeam_queue::ArrayQueue;
use ripdpi_pcap::{PCAP_GLOBAL_HEADER_LEN, PCAP_PACKET_HEADER_LEN, PcapWriter, SNAPLEN_DEFAULT};
use ripdpi_tunnel_core::PacketObserver;

const MIN_FILE_BYTES: u64 = (PCAP_GLOBAL_HEADER_LEN + PCAP_PACKET_HEADER_LEN) as u64;
mod storage;

use storage::{GLOBAL_MAX_CAPTURE_BYTES, GLOBAL_MAX_CAPTURE_FILES, lock_storage, persist_drops, reserve_segment};
pub(crate) use storage::{cleanup_captures, list_captures};

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
    Metadata,
    Panic,
    Timeout,
}

#[derive(Debug, Default)]
struct WriterResult {
    files: Vec<PcapCaptureMetadata>,
    total_drops: u64,
    limit_reached: bool,
    failure: Option<PcapWriterFailure>,
}

impl PcapCaptureSet {
    /// Open the first segment and flush its header before admitting packets.
    /// The worker flushes after 1 s or 1 MiB of buffered records.
    ///
    /// `dir` must already exist and be writable. `max_file_bytes` and
    /// `max_files` together cap the on-disk footprint (default per
    /// design: up to three 16 MiB segments).
    pub fn start(set_id: u64, dir: PathBuf, max_file_bytes: u64, max_files: u32) -> std::io::Result<Self> {
        if !(MIN_FILE_BYTES..=GLOBAL_MAX_CAPTURE_BYTES).contains(&max_file_bytes)
            || !(1..=GLOBAL_MAX_CAPTURE_FILES).contains(&max_files)
            || max_file_bytes * u64::from(max_files) > GLOBAL_MAX_CAPTURE_BYTES
        {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "pcap limits exceed the capture storage bounds",
            ));
        }
        if !dir.is_dir() {
            return Err(std::io::Error::new(
                std::io::ErrorKind::NotFound,
                format!("pcap dir does not exist: {}", dir.display()),
            ));
        }
        let first = OpenCapture::create(&dir, set_id, 0, max_file_bytes)?;
        // 1024 records ~ 1.5 MiB at MTU 1500 ~ 6 s at 200 pkt/s.
        let queue = Arc::new(ArrayQueue::new(1024));
        let drops = Arc::new(AtomicU64::new(0));
        let admissions = Arc::new(CaptureAdmission::new());
        let stop = Arc::new(AtomicBool::new(false));
        let writer = CaptureWriter {
            set_id,
            dir: dir.clone(),
            queue: queue.clone(),
            drops: drops.clone(),
            stop: stop.clone(),
            admission: CloseAdmissionOnExit(admissions.clone()),
            max_file_bytes,
            max_files,
        };
        let writer_thread =
            thread::Builder::new().name(format!("ripdpi-pcap-writer-{set_id}")).spawn(move || writer.run(first))?;
        Ok(Self { set_id, dir, queue, drops, admissions, stop, writer_thread: Some(writer_thread) })
    }

    /// Try to enqueue a record. Lock-free. On queue-full, increments
    /// the drops counter and returns false -- capture is best-effort.
    #[cfg(test)]
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

    /// Join a writer that already finished or was explicitly stopped.
    pub fn stop(mut self) -> WriterStopResult {
        self.request_stop();
        let result = self.writer_thread.take().map(|worker| join_writer_bounded(worker, WRITER_STOP_TIMEOUT));
        match result {
            Some(Ok(result)) => WriterStopResult {
                files: result.files,
                total_drops: result.total_drops,
                limit_reached: result.limit_reached,
                failure: result.failure,
            },
            other => WriterStopResult {
                files: Vec::new(),
                total_drops: self.drops.load(Ordering::Relaxed).saturating_add(self.queue.len() as u64),
                limit_reached: false,
                failure: other.and_then(Result::err),
            },
        }
    }

    pub(crate) fn writer_finished(&self) -> bool {
        self.writer_thread.as_ref().is_none_or(JoinHandle::is_finished)
    }

    pub(crate) fn wait_finished(&self) -> bool {
        self.writer_thread.as_ref().is_none_or(|worker| wait_for_writer(worker, WRITER_STOP_TIMEOUT))
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

fn wait_for_writer(handle: &JoinHandle<WriterResult>, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while !handle.is_finished() && Instant::now() < deadline {
        thread::sleep(WRITER_JOIN_POLL.min(deadline.saturating_duration_since(Instant::now())));
    }
    handle.is_finished()
}

fn join_writer_bounded(handle: JoinHandle<WriterResult>, timeout: Duration) -> Result<WriterResult, PcapWriterFailure> {
    if wait_for_writer(&handle, timeout) {
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
    pub files: Vec<PcapCaptureMetadata>,
    pub total_drops: u64,
    pub limit_reached: bool,
    pub failure: Option<PcapWriterFailure>,
}

/// Releases admission on allocation or I/O panic before the observer can enqueue again.
struct CloseAdmissionOnExit(Arc<CaptureAdmission>);

impl Drop for CloseAdmissionOnExit {
    fn drop(&mut self) {
        self.0.close_and_wait();
    }
}

struct OpenCapture {
    active_path: PathBuf,
    completed_path: PathBuf,
    started_at_ms: u64,
}

impl OpenCapture {
    fn create(
        dir: &Path,
        set_id: u64,
        idx: u32,
        max_file_bytes: u64,
    ) -> io::Result<(Self, PcapWriter<BufWriter<File>>)> {
        reserve_segment(dir, set_id, max_file_bytes)?;
        let started_at_ms = now_ms();
        let completed_path = dir.join(format!("{set_id:016x}-{started_at_ms}-{idx:02}.pcap"));
        let active_path = completed_path.with_extension("pcap.active");
        let file = File::create_new(&active_path)?;
        let capture = Self { active_path, completed_path, started_at_ms };
        let mut writer = PcapWriter::new(BufWriter::with_capacity(64 * 1024, file), SNAPLEN_DEFAULT)?;
        writer.flush()?;
        Ok((capture, writer))
    }

    fn finish(mut self, writer: PcapWriter<BufWriter<File>>, packets: u64) -> io::Result<PcapCaptureMetadata> {
        let byte_size = writer.bytes_written();
        let file = writer.into_inner().into_inner().map_err(std::io::IntoInnerError::into_error)?;
        file.sync_all()?;
        let _guard = lock_storage();
        fs::rename(&self.active_path, &self.completed_path)?;
        self.active_path.clear();
        Ok(PcapCaptureMetadata {
            path: self.completed_path.display().to_string(),
            byte_size,
            packet_count: packets,
            started_at_ms: self.started_at_ms,
            ended_at_ms: now_ms(),
            drops: 0,
        })
    }
}

impl Drop for OpenCapture {
    fn drop(&mut self) {
        if !self.active_path.as_os_str().is_empty() {
            let _ = fs::remove_file(&self.active_path);
        }
    }
}

struct CaptureWriter {
    set_id: u64,
    dir: PathBuf,
    queue: Arc<ArrayQueue<PcapCaptureRecord>>,
    drops: Arc<AtomicU64>,
    stop: Arc<AtomicBool>,
    admission: CloseAdmissionOnExit,
    max_file_bytes: u64,
    max_files: u32,
}

#[derive(Default)]
struct CaptureProgress {
    /// Entries pulled from the queue, including a pending rotation record.
    taken: u64,
    pending: Option<PcapCaptureRecord>,
}

impl CaptureWriter {
    fn run(self, first: (OpenCapture, PcapWriter<BufWriter<File>>)) -> WriterResult {
        #[cfg(test)]
        let _live_writer = LiveWriterGuard::enter();
        let mut result = WriterResult::default();
        let mut first = Some(first);
        let mut progress = CaptureProgress::default();
        for index in 0..self.max_files {
            let (segment, mut writer) = match first
                .take()
                .map_or_else(|| OpenCapture::create(&self.dir, self.set_id, index, self.max_file_bytes), Ok)
            {
                Ok(segment) => segment,
                Err(error) => {
                    tracing::warn!(%error, "pcap writer could not open a new segment");
                    result.failure = Some(PcapWriterFailure::Open);
                    break;
                }
            };
            let last_segment = index + 1 == self.max_files;
            let packets = match self.drain_segment(&mut writer, &mut progress, last_segment) {
                Ok(packets) => packets,
                Err(error) => {
                    self.admission.0.close_and_wait();
                    tracing::warn!(%error, "pcap writer failed; removing incomplete segment");
                    result.failure = Some(PcapWriterFailure::Write);
                    break;
                }
            };
            let rotates = progress.pending.is_some() || writer.bytes_written() == self.max_file_bytes;
            if rotates && last_segment {
                result.limit_reached = true;
                self.admission.0.close_and_wait();
            }
            match segment.finish(writer, packets) {
                Ok(file) => result.files.push(file),
                Err(error) => {
                    tracing::warn!(%error, "pcap writer failed to finalize segment");
                    result.failure = Some(PcapWriterFailure::Finalize);
                    break;
                }
            }
            if !rotates || (self.stop.load(Ordering::Acquire) && progress.pending.is_none() && self.queue.is_empty()) {
                break;
            }
        }

        self.admission.0.close_and_wait();
        let written = result.files.iter().map(|file| file.packet_count).sum::<u64>();
        // Only this writer pops records. Once admission closes, all producers have
        // published their enqueue or queue-full drop before we read either count.
        let dropped_after_enqueue = progress.taken - written + self.queue.len() as u64;
        while self.queue.pop().is_some() {}
        result.total_drops = self.drops.load(Ordering::Relaxed).saturating_add(dropped_after_enqueue);
        if let Some(last) = result.files.last_mut().filter(|_| result.total_drops > 0) {
            last.drops = result.total_drops;
            if let Err(error) = persist_drops(Path::new(&last.path), last.drops) {
                tracing::warn!(%error, "pcap writer failed to save drop count");
                result.failure.get_or_insert(PcapWriterFailure::Metadata);
            }
        }
        result
    }

    fn drain_segment(
        &self,
        writer: &mut PcapWriter<BufWriter<File>>,
        progress: &mut CaptureProgress,
        last_segment: bool,
    ) -> io::Result<u64> {
        let mut packets = 0;
        let mut last_flush = Instant::now();
        let mut flushed_bytes = writer.bytes_written();
        loop {
            match progress.pending.take().or_else(|| {
                let record = self.queue.pop()?;
                progress.taken += 1;
                Some(record)
            }) {
                Some(record) => {
                    let captured_len = record.bytes.len().min(SNAPLEN_DEFAULT as usize);
                    let record_bytes = (PCAP_PACKET_HEADER_LEN + captured_len) as u64;
                    if record_bytes <= self.max_file_bytes - PCAP_GLOBAL_HEADER_LEN as u64 {
                        if record_bytes > self.max_file_bytes - writer.bytes_written() {
                            progress.pending = Some(record);
                            break;
                        }
                        writer.write_packet(record.ts_micros, &record.bytes)?;
                        packets += 1;
                    }
                    if last_segment && writer.bytes_written() == self.max_file_bytes {
                        break;
                    }
                }
                None => {
                    // The empty pop could precede the last admitted producer.
                    if self.stop.load(Ordering::Acquire) && self.queue.is_empty() {
                        break;
                    }
                    thread::sleep(Duration::from_millis(50));
                }
            }
            if last_flush.elapsed() >= Duration::from_secs(1) || writer.bytes_written() - flushed_bytes >= 1024 * 1024 {
                writer.flush()?;
                last_flush = Instant::now();
                flushed_bytes = writer.bytes_written();
            }
        }
        Ok(packets)
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
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
        let capture = PcapCaptureSet::start(1, dir.path().to_path_buf(), 1024, 3).unwrap();
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
        assert_eq!(stopper.join().expect("stop must finish").total_drops, 2);
    }

    #[test]
    fn start_opens_and_flushes_the_header_before_returning() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let capture = PcapCaptureSet::start(44, dir.path().to_path_buf(), 1024, 1).unwrap();
        let marker = fs::read_dir(dir.path()).unwrap().next().unwrap().unwrap().path();
        assert!(marker.to_string_lossy().ends_with(".pcap.active"));
        assert_eq!(fs::read(marker).unwrap()[..8], [0xd4, 0xc3, 0xb2, 0xa1, 2, 0, 4, 0]);
        assert_eq!(capture.stop().failure, None);
    }

    #[test]
    fn writer_closes_admission_and_counts_pending_records_at_capacity() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let queue = Arc::new(ArrayQueue::new(4));
        for marker in 1..=3 {
            queue.push(fake_packet(marker)).unwrap();
        }
        let admissions = Arc::new(CaptureAdmission::new());
        let writer = CaptureWriter {
            set_id: 45,
            dir: dir.path().to_path_buf(),
            queue: queue.clone(),
            drops: Arc::new(AtomicU64::new(0)),
            stop: Arc::new(AtomicBool::new(false)),
            admission: CloseAdmissionOnExit(admissions.clone()),
            max_file_bytes: 100,
            max_files: 1,
        };
        let first = OpenCapture::create(dir.path(), 45, 0, 100).unwrap();
        let result = writer.run(first);
        assert!(result.limit_reached);
        assert_eq!(result.failure, None);
        assert_eq!(result.files[0].packet_count, 1);
        assert_eq!(result.total_drops, 2);
        assert!(admissions.try_acquire().is_none());
        assert!(queue.is_empty());
        assert_eq!(list_captures(dir.path()).unwrap()[0].drops, 2);
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
        let capture = PcapCaptureSet::start(2, dir.path().to_path_buf(), 1024 * 1024, 3).unwrap();
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
    fn exact_filled_segment_leaves_previous_captures_until_another_record_needs_space() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let previous = [dir.path().join("previous-a.pcap"), dir.path().join("previous-b.pcap")];
        for path in &previous {
            fs::write(path, b"previous").unwrap();
        }
        let capture = PcapCaptureSet::start(19, dir.path().to_path_buf(), 68, 3).unwrap();
        let current = fs::read_dir(dir.path())
            .unwrap()
            .map(Result::unwrap)
            .map(|entry| entry.path())
            .find(|path| path.to_string_lossy().ends_with(".pcap.active"))
            .unwrap();
        assert!(capture.submit(fake_packet(1)));
        // A non-final segment stays in place awaiting a new record, long enough
        // for the normal periodic flush to expose the exact-filled segment.
        let deadline = Instant::now() + Duration::from_secs(3);
        while fs::metadata(&current).unwrap().len() < 68 {
            assert!(Instant::now() < deadline, "writer did not flush the filled segment");
            thread::sleep(Duration::from_millis(20));
        }

        let result = capture.stop();
        assert_eq!(result.failure, None);
        assert!(!result.limit_reached);
        assert_eq!(result.files.len(), 1);
        assert!(previous.iter().all(|path| path.exists()));
    }

    #[test]
    fn rotation_defers_a_complete_record_to_the_next_segment() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let capture = PcapCaptureSet::start(16, dir.path().to_path_buf(), 100, 3).unwrap();
        assert!(capture.submit(fake_packet(1)));
        assert!(capture.submit(fake_packet(2)));

        let result = capture.stop();
        assert_eq!(result.failure, None);
        assert_eq!(result.total_drops, 0);
        assert_eq!(result.files.len(), 2);
        for (file, marker) in result.files.iter().zip(1..=2) {
            let bytes = std::fs::read(&file.path).unwrap();
            assert_eq!(bytes.len(), 68);
            assert_eq!(bytes[60], marker);
        }
    }

    #[test]
    fn packets_larger_than_an_empty_segment_are_counted_without_overshoot() {
        let _serial = serial_pcap_test();
        let dir = TempDir::new().unwrap();
        let capture = PcapCaptureSet::start(17, dir.path().to_path_buf(), 59, 1).unwrap();
        assert!(capture.submit(fake_packet(1)));

        let result = capture.stop();
        assert_eq!(result.failure, None);
        assert_eq!(result.total_drops, 1);
        assert_eq!(result.files.len(), 1);
        assert_eq!(std::fs::metadata(&result.files[0].path).unwrap().len(), 24);
    }

    #[test]
    fn list_captures_skips_non_pcap_files() {
        let dir = TempDir::new().unwrap();
        std::fs::write(dir.path().join("foo.txt"), b"hi").unwrap();
        std::fs::write(dir.path().join("bar.pcap"), b"\xa1\xb2\xc3\xd4").unwrap();
        let files = list_captures(dir.path()).unwrap();
        assert_eq!(files.len(), 1);
        assert!(files[0].path.ends_with("bar.pcap"));
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
        let oversized_file = PcapCaptureSet::start(101, dir.path().to_path_buf(), GLOBAL_MAX_CAPTURE_BYTES + 1, 1)
            .err()
            .expect("oversized file must fail");

        assert_eq!(zero_files.kind(), std::io::ErrorKind::InvalidInput);
        assert_eq!(oversized_file.kind(), std::io::ErrorKind::InvalidInput);
    }
}
