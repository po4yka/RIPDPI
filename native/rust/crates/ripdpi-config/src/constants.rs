pub const VERSION: &str = "17.3";

pub const DETECT_HTTP_LOCAT: u32 = 1;
pub const DETECT_TLS_HANDSHAKE_FAILURE: u32 = 2;
pub const DETECT_RECONN: u32 = 16;
pub const DETECT_CONNECT: u32 = 32;
pub const DETECT_TCP_RESET: u32 = 64;
pub const DETECT_SILENT_DROP: u32 = 128;
pub const DETECT_TLS_ALERT: u32 = 256;
pub const DETECT_HTTP_BLOCKPAGE: u32 = 512;
pub const DETECT_DNS_TAMPER: u32 = 1024;
pub const DETECT_QUIC_BREAKAGE: u32 = 2048;
pub const DETECT_TLS_ERR: u32 = DETECT_TLS_HANDSHAKE_FAILURE | DETECT_TLS_ALERT;
pub const DETECT_TORST: u32 = DETECT_TCP_RESET | DETECT_SILENT_DROP;

pub const AUTO_RECONN: u32 = 1;
pub const AUTO_NOPOST: u32 = 2;
pub const AUTO_SORT: u32 = 4;

pub const FM_RAND: u32 = 1;
pub const FM_ORIG: u32 = 2;
pub const FM_RNDSNI: u32 = 4;
pub const FM_DUPSID: u32 = 8;
pub const FM_PADENCAP: u32 = 16;

pub const HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS: i64 = 6 * 60 * 60;
pub const HOST_AUTOLEARN_DEFAULT_MAX_HOSTS: usize = 512;
pub const HOST_AUTOLEARN_DEFAULT_STORE_FILE: &str = "host-autolearn-v1.json";
