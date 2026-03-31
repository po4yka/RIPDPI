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
pub const DETECT_CONNECTION_FREEZE: u32 = 4096;
pub const DETECT_TLS_ERR: u32 = DETECT_TLS_HANDSHAKE_FAILURE | DETECT_TLS_ALERT;
pub const DETECT_TORST: u32 = DETECT_TCP_RESET | DETECT_SILENT_DROP | DETECT_CONNECTION_FREEZE;

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
pub const HOST_AUTOLEARN_DEFAULT_STORE_FILE: &str = "host-autolearn-v2.json";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detect_constants_are_distinct_powers_of_two() {
        let flags = [
            DETECT_HTTP_LOCAT,
            DETECT_TLS_HANDSHAKE_FAILURE,
            DETECT_RECONN,
            DETECT_CONNECT,
            DETECT_TCP_RESET,
            DETECT_SILENT_DROP,
            DETECT_TLS_ALERT,
            DETECT_HTTP_BLOCKPAGE,
            DETECT_DNS_TAMPER,
            DETECT_QUIC_BREAKAGE,
            DETECT_CONNECTION_FREEZE,
        ];
        for &flag in &flags {
            assert!(flag.is_power_of_two(), "flag {flag} is not a power of two");
        }
        // All flags are unique
        for i in 0..flags.len() {
            for j in (i + 1)..flags.len() {
                assert_ne!(flags[i], flags[j], "flags at {i} and {j} collide");
            }
        }
    }

    #[test]
    fn detect_composite_constants() {
        assert_eq!(DETECT_TLS_ERR, DETECT_TLS_HANDSHAKE_FAILURE | DETECT_TLS_ALERT);
        assert_eq!(DETECT_TORST, DETECT_TCP_RESET | DETECT_SILENT_DROP | DETECT_CONNECTION_FREEZE);
    }

    // --- New coverage gap tests ---

    #[test]
    fn auto_flags_are_distinct_powers_of_two() {
        let flags = [AUTO_RECONN, AUTO_NOPOST, AUTO_SORT];
        for &flag in &flags {
            assert!(flag.is_power_of_two(), "AUTO flag {flag} is not a power of two");
        }
        for i in 0..flags.len() {
            for j in (i + 1)..flags.len() {
                assert_ne!(flags[i], flags[j], "AUTO flags at {i} and {j} collide");
            }
        }
    }

    #[test]
    fn fm_flags_are_distinct_powers_of_two() {
        let flags = [FM_RAND, FM_ORIG, FM_RNDSNI, FM_DUPSID, FM_PADENCAP];
        for &flag in &flags {
            assert!(flag.is_power_of_two(), "FM flag {flag} is not a power of two");
        }
        for i in 0..flags.len() {
            for j in (i + 1)..flags.len() {
                assert_ne!(flags[i], flags[j], "FM flags at {i} and {j} collide");
            }
        }
    }
}
