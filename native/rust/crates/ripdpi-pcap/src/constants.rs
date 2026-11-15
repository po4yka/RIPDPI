/// Classic libpcap magic number (microsecond resolution, little-endian).
/// `0xa1b2c3d4` indicates the file timestamps are in microseconds and
/// the writer used the host byte order (which all modern hosts treat as
/// little-endian; we always write LE).
pub const MAGIC: u32 = 0xa1b2_c3d4;

/// LINKTYPE_RAW = 101. The data link layer is raw IP packets (IPv4 or
/// IPv6) with NO Ethernet / loopback / SLIP wrapper. Matches what the
/// TUN device emits.
pub const LINKTYPE_RAW: u32 = 101;

/// Default snapshot length for packets captured at the TUN interface.
pub const SNAPLEN_DEFAULT: u32 = 65535;

/// Encoded length of the classic PCAP file header.
pub const PCAP_GLOBAL_HEADER_LEN: usize = 24;
/// Encoded length of the header that precedes each packet.
pub const PCAP_PACKET_HEADER_LEN: usize = 16;

pub(crate) const PCAP_VERSION: [u8; 4] = [2, 0, 4, 0];
pub(crate) const MAX_CAPTURED_BYTES: u32 = 262_144;
pub(crate) const MICROS_PER_SECOND: u32 = 1_000_000;
