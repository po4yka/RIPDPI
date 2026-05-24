/// Classic libpcap magic number (microsecond resolution, little-endian).
/// `0xa1b2c3d4` indicates the file timestamps are in microseconds and
/// the writer used the host byte order (which all modern hosts treat as
/// little-endian; we always write LE).
pub const MAGIC: u32 = 0xa1b2_c3d4;

/// LINKTYPE_RAW = 101. The data link layer is raw IP packets (IPv4 or
/// IPv6) with NO Ethernet / loopback / SLIP wrapper. Matches what the
/// TUN device emits.
pub const LINKTYPE_RAW: u32 = 101;

/// Maximum bytes captured per packet. 65535 is large enough for any
/// IPv6 jumbogram we might see in practice (typical MTU is 1500).
pub const SNAPLEN_DEFAULT: u32 = 65535;

pub(crate) const PCAP_GLOBAL_HEADER_LEN: usize = 24;
pub(crate) const PCAP_PACKET_HEADER_LEN: usize = 16;
