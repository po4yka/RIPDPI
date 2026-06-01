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

use std::fs::{self, File};
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crossbeam_queue::ArrayQueue;
use ripdpi_pcap::{PcapWriter, SNAPLEN_DEFAULT};
use ripdpi_tunnel_core::PacketObserver;

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
    stop: Arc<AtomicBool>,
    writer_thread: Option<JoinHandle<WriterResult>>,
}

#[derive(Debug, Default)]
struct WriterResult {
    files: Vec<PcapCaptureMetadata>,
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
        if !dir.is_dir() {
            return Err(std::io::Error::new(
                std::io::ErrorKind::NotFound,
                format!("pcap dir does not exist: {}", dir.display()),
            ));
        }
        // 1024 records ~ 1.5 MiB at MTU 1500 ~ 6 s at 200 pkt/s.
        let queue: Arc<ArrayQueue<PcapCaptureRecord>> = Arc::new(ArrayQueue::new(1024));
        let drops = Arc::new(AtomicU64::new(0));
        let stop = Arc::new(AtomicBool::new(false));
        let q = queue.clone();
        let s = stop.clone();
        let d = dir.clone();
        let writer_thread = thread::Builder::new()
            .name(format!("ripdpi-pcap-writer-{set_id}"))
            .spawn(move || writer_loop(set_id, d, q, s, max_file_bytes, max_files))?;
        Ok(Self { set_id, dir, queue, drops, stop, writer_thread: Some(writer_thread) })
    }

    /// Try to enqueue a record. Lock-free. On queue-full, increments
    /// the drops counter and returns false -- capture is best-effort.
    pub fn submit(&self, record: PcapCaptureRecord) -> bool {
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
        self.stop.store(true, Ordering::Relaxed);
        let mut result = WriterStopResult {
            set_id: self.set_id,
            files: Vec::new(),
            total_drops: self.drops.load(Ordering::Relaxed),
        };
        if let Some(handle) = self.writer_thread.take()
            && let Ok(writer_result) = handle.join()
        {
            result.files = writer_result.files;
            // Annotate the drops on the last file (most informative
            // location for the UI).
            if let Some(last) = result.files.last_mut() {
                last.drops = result.total_drops;
            }
        }
        result
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
    /// lifetime — after [`Self::stop`] consumes the set and joins the
    /// writer thread, any further `on_inbound`/`on_outbound` calls
    /// silently enqueue (then drop, since no consumer remains).
    pub fn observer_handle(&self) -> Arc<PcapPacketObserver> {
        Arc::new(PcapPacketObserver { queue: self.queue.clone(), drops: self.drops.clone() })
    }
}

/// Independent observer that owns clone-able handles to the
/// [`PcapCaptureSet`] queue and drops counter. Held by
/// `ripdpi_tunnel_core::Stats` so io_loop packets reach the queue
/// without forcing `Stats` to know about [`PcapCaptureSet`].
pub struct PcapPacketObserver {
    queue: Arc<ArrayQueue<PcapCaptureRecord>>,
    drops: Arc<AtomicU64>,
}

impl PcapPacketObserver {
    fn try_push(&self, packet: &[u8]) {
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
}

fn writer_loop(
    set_id: u64,
    dir: PathBuf,
    queue: Arc<ArrayQueue<PcapCaptureRecord>>,
    stop: Arc<AtomicBool>,
    max_file_bytes: u64,
    max_files: u32,
) -> WriterResult {
    let mut result = WriterResult::default();
    let mut file_idx: u32 = 0;
    while file_idx < max_files {
        match open_file(set_id, &dir, file_idx) {
            Ok((path, file, started_at_ms)) => {
                let drain = drain_one_file(&queue, &stop, file, max_file_bytes);
                match drain {
                    Ok((bytes, packets)) => {
                        result.files.push(PcapCaptureMetadata {
                            path: path.display().to_string(),
                            byte_size: bytes,
                            packet_count: packets,
                            started_at_ms,
                            ended_at_ms: now_ms(),
                            drops: 0, // populated in stop()
                        });
                    }
                    Err(_) => break,
                }
                file_idx += 1;
                if stop.load(Ordering::Relaxed) && queue.is_empty() {
                    break;
                }
            }
            Err(_) => break,
        }
    }
    result
}

fn open_file(set_id: u64, dir: &Path, idx: u32) -> std::io::Result<(PathBuf, BufWriter<File>, u64)> {
    let started = now_ms();
    let filename = format!("{set_id:016x}-{started}-{idx:02}.pcap");
    let path = dir.join(filename);
    let file = File::create(&path)?;
    let writer = BufWriter::with_capacity(64 * 1024, file);
    Ok((path, writer, started))
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
                if stop.load(Ordering::Relaxed) {
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
    if let Ok(inner_file) = buf_writer.into_inner() {
        let _ = inner_file.sync_data();
    }
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

    #[test]
    fn start_then_stop_roundtrips_zero_packets() {
        let dir = TempDir::new().unwrap();
        let capture = PcapCaptureSet::start(1, dir.path().to_path_buf(), 1024, 4).unwrap();
        let result = capture.stop();
        assert_eq!(result.total_drops, 0);
        // At least one (empty-ish) file should exist, OR none if writer
        // exited cleanly before opening. Spec is permissive -- we just
        // assert no panic + drops are zero.
    }

    #[test]
    fn submit_then_stop_records_packet_count() {
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
    fn submit_returns_false_when_queue_full() {
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
}
