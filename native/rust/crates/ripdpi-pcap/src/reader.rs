use std::io::{self, Read};

use crate::constants::{LINKTYPE_RAW, MAGIC, PCAP_GLOBAL_HEADER_LEN, PCAP_PACKET_HEADER_LEN};

/// A single record returned by [`PcapReader::next_record`].
#[derive(Debug, Clone)]
pub struct PcapRecord {
    pub ts_micros: u64,
    pub orig_len: u32,
    pub bytes: Vec<u8>,
}

/// Streaming reader over a classic-libpcap file.
///
/// Tolerant of truncated-tail records (returns the last good record
/// then `None` at the next call) - this is the common case after a
/// `SIGKILL` mid-write per the design's process-death contract.
pub struct PcapReader<R: Read> {
    inner: R,
    snaplen: u32,
}

impl<R: Read> PcapReader<R> {
    /// Read and validate the global header, then return a reader ready to
    /// stream packet records.
    pub fn new(mut inner: R) -> io::Result<Self> {
        let mut header = [0u8; PCAP_GLOBAL_HEADER_LEN];
        inner.read_exact(&mut header)?;
        let magic = u32::from_le_bytes([header[0], header[1], header[2], header[3]]);
        if magic != MAGIC {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("unsupported pcap magic 0x{magic:08x}, expected 0x{MAGIC:08x}"),
            ));
        }
        let snaplen = u32::from_le_bytes([header[16], header[17], header[18], header[19]]);
        let linktype = u32::from_le_bytes([header[20], header[21], header[22], header[23]]);
        if linktype != LINKTYPE_RAW {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("unsupported linktype {linktype}, expected {LINKTYPE_RAW}"),
            ));
        }
        Ok(Self { inner, snaplen })
    }

    /// Next record, or `None` at clean EOF or partial-tail.
    pub fn next_record(&mut self) -> io::Result<Option<PcapRecord>> {
        let mut header = [0u8; PCAP_PACKET_HEADER_LEN];
        match self.inner.read_exact(&mut header) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
            Err(e) => return Err(e),
        }
        let ts_sec = u32::from_le_bytes([header[0], header[1], header[2], header[3]]);
        let ts_usec = u32::from_le_bytes([header[4], header[5], header[6], header[7]]);
        let incl_len = u32::from_le_bytes([header[8], header[9], header[10], header[11]]);
        let orig_len = u32::from_le_bytes([header[12], header[13], header[14], header[15]]);
        if incl_len > self.snaplen {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("packet incl_len {incl_len} exceeds snaplen {}", self.snaplen),
            ));
        }
        let mut bytes = vec![0u8; incl_len as usize];
        match self.inner.read_exact(&mut bytes) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
            Err(e) => return Err(e),
        }
        Ok(Some(PcapRecord { ts_micros: u64::from(ts_sec) * 1_000_000 + u64::from(ts_usec), orig_len, bytes }))
    }
}

impl<R: Read> Iterator for PcapReader<R> {
    type Item = io::Result<PcapRecord>;
    fn next(&mut self) -> Option<Self::Item> {
        match self.next_record() {
            Ok(Some(record)) => Some(Ok(record)),
            Ok(None) => None,
            Err(e) => Some(Err(e)),
        }
    }
}
