use std::fs::File;
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Mutex;
use std::time::SystemTime;

// pcap global header constants
const PCAP_MAGIC: u32 = 0xa1b2c3d4; // microsecond resolution
const PCAP_VERSION_MAJOR: u16 = 2;
const PCAP_VERSION_MINOR: u16 = 4;
const PCAP_SNAPLEN: u32 = 65535;
const PCAP_LINKTYPE_RAW: u32 = 101; // LINKTYPE_RAW (raw IP)

const PCAP_GLOBAL_HEADER_LEN: u64 = 24;
const PCAP_PACKET_HEADER_LEN: u64 = 16;

pub struct PcapWriter {
    file: BufWriter<File>,
    max_bytes: u64,
    written_bytes: u64,
}

impl PcapWriter {
    pub fn new(path: &Path, max_bytes: u64) -> io::Result<Self> {
        let file = File::create(path)?;
        let mut writer = Self { file: BufWriter::new(file), max_bytes, written_bytes: 0 };
        writer.write_global_header()?;
        Ok(writer)
    }

    fn write_global_header(&mut self) -> io::Result<()> {
        self.file.write_all(&PCAP_MAGIC.to_le_bytes())?;
        self.file.write_all(&PCAP_VERSION_MAJOR.to_le_bytes())?;
        self.file.write_all(&PCAP_VERSION_MINOR.to_le_bytes())?;
        self.file.write_all(&0i32.to_le_bytes())?; // thiszone
        self.file.write_all(&0u32.to_le_bytes())?; // sigfigs
        self.file.write_all(&PCAP_SNAPLEN.to_le_bytes())?;
        self.file.write_all(&PCAP_LINKTYPE_RAW.to_le_bytes())?;
        self.written_bytes = PCAP_GLOBAL_HEADER_LEN;
        Ok(())
    }

    pub fn write_packet(&mut self, data: &[u8], timestamp: SystemTime) -> io::Result<bool> {
        let record_len = PCAP_PACKET_HEADER_LEN + data.len() as u64;
        if self.written_bytes + record_len > self.max_bytes {
            return Ok(false);
        }

        let duration = timestamp.duration_since(std::time::UNIX_EPOCH).unwrap_or_default();
        let ts_sec = duration.as_secs() as u32;
        let ts_usec = duration.subsec_micros();
        let incl_len = data.len() as u32;
        let orig_len = incl_len;

        self.file.write_all(&ts_sec.to_le_bytes())?;
        self.file.write_all(&ts_usec.to_le_bytes())?;
        self.file.write_all(&incl_len.to_le_bytes())?;
        self.file.write_all(&orig_len.to_le_bytes())?;
        self.file.write_all(data)?;

        self.written_bytes += record_len;
        Ok(true)
    }

    pub fn is_full(&self) -> bool {
        self.written_bytes >= self.max_bytes
    }

    pub fn flush(&mut self) -> io::Result<()> {
        self.file.flush()
    }
}

pub struct PcapRecordingSession {
    writer: Mutex<PcapWriter>,
    path: PathBuf,
    active: AtomicBool,
    connection_count: AtomicU32,
    max_connections: u32,
}

impl PcapRecordingSession {
    pub fn start(dir: &Path, max_bytes: u64, max_connections: u32) -> io::Result<Self> {
        let ts_millis = SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis();
        let filename = format!("capture-{ts_millis}.pcap");
        let path = dir.join(filename);
        let writer = PcapWriter::new(&path, max_bytes)?;
        Ok(Self {
            writer: Mutex::new(writer),
            path,
            active: AtomicBool::new(true),
            connection_count: AtomicU32::new(0),
            max_connections,
        })
    }

    pub fn record_packet(&self, data: &[u8]) -> io::Result<()> {
        if !self.active.load(Ordering::Acquire) {
            return Ok(());
        }
        let mut writer = self.writer.lock().map_err(|_| io::Error::other("pcap writer poisoned"))?;
        let written = writer.write_packet(data, SystemTime::now())?;
        if !written {
            self.active.store(false, Ordering::Release);
        }
        Ok(())
    }

    pub fn increment_connection(&self) -> bool {
        let prev = self.connection_count.fetch_add(1, Ordering::AcqRel);
        if prev + 1 >= self.max_connections {
            self.active.store(false, Ordering::Release);
            return false;
        }
        true
    }

    pub fn stop(&self) -> io::Result<PathBuf> {
        self.active.store(false, Ordering::Release);
        let mut writer = self.writer.lock().map_err(|_| io::Error::other("pcap writer poisoned"))?;
        writer.flush()?;
        Ok(self.path.clone())
    }

    pub fn is_active(&self) -> bool {
        self.active.load(Ordering::Acquire)
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;
    use std::time::{Duration, UNIX_EPOCH};

    fn read_file_bytes(path: &Path) -> Vec<u8> {
        let mut f = File::open(path).expect("open file");
        let mut buf = Vec::new();
        f.read_to_end(&mut buf).expect("read file");
        buf
    }

    #[test]
    fn test_global_header_format() {
        let dir = std::env::temp_dir();
        let path = dir.join("test_global_header.pcap");
        let _writer = PcapWriter::new(&path, 1024).expect("create writer");
        drop(_writer);

        let bytes = read_file_bytes(&path);
        assert_eq!(bytes.len(), PCAP_GLOBAL_HEADER_LEN as usize);

        // magic
        assert_eq!(&bytes[0..4], &PCAP_MAGIC.to_le_bytes());
        // version major
        assert_eq!(&bytes[4..6], &PCAP_VERSION_MAJOR.to_le_bytes());
        // version minor
        assert_eq!(&bytes[6..8], &PCAP_VERSION_MINOR.to_le_bytes());
        // thiszone
        assert_eq!(&bytes[8..12], &0i32.to_le_bytes());
        // sigfigs
        assert_eq!(&bytes[12..16], &0u32.to_le_bytes());
        // snaplen
        assert_eq!(&bytes[16..20], &PCAP_SNAPLEN.to_le_bytes());
        // linktype
        assert_eq!(&bytes[20..24], &PCAP_LINKTYPE_RAW.to_le_bytes());

        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn test_write_and_read_packet() {
        let dir = std::env::temp_dir();
        let path = dir.join("test_write_packet.pcap");
        let mut writer = PcapWriter::new(&path, 4096).expect("create writer");

        let packet: &[u8] = &[0x45, 0x00, 0x00, 0x28, 0xab, 0xcd];
        let ts = UNIX_EPOCH + Duration::from_micros(1_700_000_000 * 1_000_000 + 123456);
        let written = writer.write_packet(packet, ts).expect("write packet");
        assert!(written);
        writer.flush().expect("flush");
        drop(writer);

        let bytes = read_file_bytes(&path);
        let header_end = PCAP_GLOBAL_HEADER_LEN as usize;
        let rec = &bytes[header_end..];

        let ts_sec = u32::from_le_bytes(rec[0..4].try_into().unwrap());
        let ts_usec = u32::from_le_bytes(rec[4..8].try_into().unwrap());
        let incl_len = u32::from_le_bytes(rec[8..12].try_into().unwrap());
        let orig_len = u32::from_le_bytes(rec[12..16].try_into().unwrap());

        assert_eq!(ts_sec, 1_700_000_000u32);
        assert_eq!(ts_usec, 123456u32);
        assert_eq!(incl_len, packet.len() as u32);
        assert_eq!(orig_len, packet.len() as u32);
        assert_eq!(&rec[16..16 + packet.len()], packet);

        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn test_size_cap_stops_recording() {
        let dir = std::env::temp_dir();
        let path = dir.join("test_size_cap.pcap");
        // global header is 24 bytes; leave no room for any packet record
        let mut writer = PcapWriter::new(&path, 100).expect("create writer");

        let big_packet = vec![0u8; 200];
        let result = writer.write_packet(&big_packet, SystemTime::now()).expect("write");
        assert!(!result, "write_packet should return false when full");

        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn test_session_connection_limit() {
        let dir = std::env::temp_dir();
        let session = PcapRecordingSession::start(&dir, 65536, 2).expect("start session");

        assert!(session.increment_connection(), "first connection should succeed");
        // second connection reaches the limit (prev=1, prev+1=2 >= max_connections=2) -> false
        assert!(!session.increment_connection(), "second connection should hit limit");
        assert!(!session.is_active(), "session should be inactive after limit");

        let _ = std::fs::remove_file(session.path());
    }

    #[test]
    fn test_session_stop_returns_path() {
        let dir = std::env::temp_dir();
        let session = PcapRecordingSession::start(&dir, 65536, 10).expect("start session");
        let expected_path = session.path().to_path_buf();

        let returned_path = session.stop().expect("stop session");
        assert_eq!(returned_path, expected_path);
        assert!(!session.is_active());

        let _ = std::fs::remove_file(&returned_path);
    }
}
