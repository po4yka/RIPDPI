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
