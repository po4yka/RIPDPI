use std::net::SocketAddr;

use ripdpi_desync::AdaptivePlannerHints;

use crate::retry_stealth::hash::{FNV_OFFSET, stable_hash_update};

pub fn adaptive_signature_hash(fake_ttl: Option<u8>, hints: AdaptivePlannerHints) -> u64 {
    let mut hash = FNV_OFFSET;
    if let Some(value) = fake_ttl {
        stable_hash_update(&mut hash, value.to_string().as_bytes());
    }
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, format!("{hints:?}").as_bytes());
    hash
}

pub fn target_key(host: Option<&str>, dest: SocketAddr) -> String {
    host.map(str::trim).filter(|value| !value.is_empty()).map_or_else(|| dest.to_string(), str::to_ascii_lowercase)
}
