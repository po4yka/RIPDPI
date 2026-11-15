use std::io::{self, Write};

use crate::constants::{
    LINKTYPE_RAW, MAGIC, MAX_CAPTURED_BYTES, MICROS_PER_SECOND, PCAP_GLOBAL_HEADER_LEN, PCAP_PACKET_HEADER_LEN,
    PCAP_VERSION,
};

/// Writes classic-libpcap records to any `Write` sink.
///
/// Caller-owned `Write`. The writer does NOT auto-flush; callers must
/// drive `flush()` on their schedule (1 Hz or every 1 MiB whichever
/// first). Drop is a no-op for the inner sink (caller
/// retains ownership semantics).
pub struct PcapWriter<W: Write> {
    inner: W,
    snaplen: u32,
    bytes_written: u64,
}

impl<W: Write> PcapWriter<W> {
    /// Create a writer and emit the 24-byte global header immediately.
    /// Pass a nonzero `snaplen`. Use [`crate::SNAPLEN_DEFAULT`] for TUN captures.
    pub fn new(mut inner: W, snaplen: u32) -> io::Result<Self> {
        if snaplen == 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "pcap snaplen must be nonzero"));
        }
        // Global header layout (LE):
        //  0..4   magic
        //  4..6   version_major = 2
        //  6..8   version_minor = 4
        //  8..12  thiszone = 0 (GMT)
        // 12..16  sigfigs = 0
        // 16..20  snaplen
        // 20..24  network = LINKTYPE_RAW
        let mut header = [0u8; PCAP_GLOBAL_HEADER_LEN];
        header[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        header[4..8].copy_from_slice(&PCAP_VERSION);
        // bytes 8..16 stay zero
        header[16..20].copy_from_slice(&snaplen.to_le_bytes());
        header[20..24].copy_from_slice(&LINKTYPE_RAW.to_le_bytes());
        inner.write_all(&header)?;
        Ok(Self { inner, snaplen, bytes_written: PCAP_GLOBAL_HEADER_LEN as u64 })
    }

    /// Append one packet record. `ts_micros` is the wall-clock timestamp
    /// in microseconds since the Unix epoch. `bytes` is the raw IP packet
    /// (IPv4 or IPv6 header + payload).
    ///
    /// Packets longer than `snaplen` are truncated in the file but the
    /// `orig_len` field records the original length. Records that would
    /// store more than 262144 bytes are rejected.
    pub fn write_packet(&mut self, ts_micros: u64, bytes: &[u8]) -> io::Result<()> {
        let micros_per_second = u64::from(MICROS_PER_SECOND);
        let ts_sec = u32::try_from(ts_micros / micros_per_second)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "pcap timestamp exceeds u32 seconds"))?;
        let ts_usec = (ts_micros % micros_per_second) as u32;
        let orig_len = u32::try_from(bytes.len())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "pcap original length exceeds u32"))?;
        let incl_len = orig_len.min(self.snaplen);
        if incl_len > MAX_CAPTURED_BYTES {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "pcap captured length exceeds allocation limit"));
        }
        let mut header = [0u8; PCAP_PACKET_HEADER_LEN];
        header[0..4].copy_from_slice(&ts_sec.to_le_bytes());
        header[4..8].copy_from_slice(&ts_usec.to_le_bytes());
        header[8..12].copy_from_slice(&incl_len.to_le_bytes());
        header[12..16].copy_from_slice(&orig_len.to_le_bytes());
        self.inner.write_all(&header)?;
        self.inner.write_all(&bytes[..incl_len as usize])?;
        self.bytes_written = self.bytes_written.saturating_add(PCAP_PACKET_HEADER_LEN as u64 + u64::from(incl_len));
        Ok(())
    }

    /// Force the inner writer's buffer to disk. Caller drives the
    /// schedule (1 Hz or 1 MiB).
    pub fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }

    /// Total bytes (including headers) written so far. Used by the
    /// rotation logic to know when to rotate to a new file.
    pub fn bytes_written(&self) -> u64 {
        self.bytes_written
    }

    /// Reclaim the inner sink.
    pub fn into_inner(self) -> W {
        self.inner
    }
}
