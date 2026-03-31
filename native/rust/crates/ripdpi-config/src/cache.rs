use std::fs;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::str::FromStr;

use crate::ConfigError;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CacheEntry {
    pub addr: IpAddr,
    pub bits: u16,
    pub port: u16,
    pub time: i64,
    pub host: Option<String>,
}

pub fn load_cache_entries(text: &str) -> Vec<CacheEntry> {
    let mut out = Vec::new();
    for line in text.lines() {
        let parts: Vec<_> = line.split_whitespace().collect();
        if parts.len() != 6 || parts[0] != "0" {
            continue;
        }
        let Ok(bits) = parts[2].parse::<u16>() else {
            continue;
        };
        let Ok(port) = parts[3].parse::<u16>() else {
            continue;
        };
        let Ok(time) = parts[4].parse::<i64>() else {
            continue;
        };
        let Ok(addr) = IpAddr::from_str(parts[1]) else {
            continue;
        };
        out.push(CacheEntry {
            addr,
            bits,
            port,
            time,
            host: if parts[5] == "-" { None } else { Some(parts[5].to_owned()) },
        });
    }
    out
}

pub fn load_cache_entries_from_path(path: &Path) -> Result<Vec<CacheEntry>, ConfigError> {
    let text =
        fs::read_to_string(path).map_err(|_| ConfigError::invalid("cache-file", Some(path.display().to_string())))?;
    Ok(load_cache_entries(&text))
}

pub fn dump_cache_entries(entries: &[CacheEntry]) -> String {
    let mut out = String::new();
    for entry in entries {
        let host = entry.host.as_deref().unwrap_or("-");
        out.push_str(&format!("0 {} {} {} {} {}\n", entry.addr, entry.bits, entry.port, entry.time, host));
    }
    out
}

pub fn config_path(name: impl Into<PathBuf>) -> PathBuf {
    name.into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::IpAddr;
    use std::str::FromStr;

    #[test]
    fn cache_entries_round_trip_through_text_format() {
        let entries = vec![
            CacheEntry {
                addr: IpAddr::from_str("192.0.2.10").expect("ipv4 addr"),
                bits: 24,
                port: 443,
                time: 123,
                host: Some("example.com".to_string()),
            },
            CacheEntry {
                addr: IpAddr::from_str("2001:db8::10").expect("ipv6 addr"),
                bits: 128,
                port: 80,
                time: 456,
                host: None,
            },
        ];

        let dumped = dump_cache_entries(&entries);
        let loaded = load_cache_entries(&dumped);

        assert_eq!(loaded, entries);
    }

    #[test]
    fn load_cache_entries_skips_malformed_lines() {
        let input = "0 192.0.2.1 32 443 100 example.com\n\
                      bad line\n\
                      1 192.0.2.2 32 443 100 -\n\
                      0 not_an_ip 32 443 100 -\n\
                      0 192.0.2.3 xxx 443 100 -\n\
                      0 192.0.2.4 32 xxx 100 -\n\
                      0 192.0.2.5 32 443 xxx -\n\
                      0 192.0.2.6 32 443 200 -\n";

        let entries = load_cache_entries(input);
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].host, Some("example.com".to_string()));
        assert_eq!(entries[1].host, None);
        assert_eq!(entries[1].time, 200);
    }

    #[test]
    fn load_cache_entries_empty_string() {
        assert!(load_cache_entries("").is_empty());
    }

    #[test]
    fn dump_cache_entries_empty_list() {
        assert_eq!(dump_cache_entries(&[]), "");
    }

    // --- New coverage gap tests ---

    #[test]
    fn load_cache_entries_ipv6_address() {
        let input = "0 2001:db8::1 128 443 100 v6.example.com\n";
        let entries = load_cache_entries(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].addr, IpAddr::from_str("2001:db8::1").unwrap());
        assert_eq!(entries[0].bits, 128);
        assert_eq!(entries[0].host, Some("v6.example.com".to_string()));
    }

    #[test]
    fn cache_entry_host_vs_dash_serialization() {
        let with_host = CacheEntry {
            addr: IpAddr::from_str("10.0.0.1").unwrap(),
            bits: 32,
            port: 443,
            time: 100,
            host: Some("test.com".to_string()),
        };
        let without_host =
            CacheEntry { addr: IpAddr::from_str("10.0.0.2").unwrap(), bits: 32, port: 80, time: 200, host: None };

        let dumped = dump_cache_entries(&[with_host, without_host]);
        assert!(dumped.contains("test.com"));
        assert!(dumped.contains(" - \n") || dumped.ends_with(" -\n"));

        let reloaded = load_cache_entries(&dumped);
        assert_eq!(reloaded[0].host, Some("test.com".to_string()));
        assert_eq!(reloaded[1].host, None);
    }
}
