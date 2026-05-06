use std::collections::HashMap;
use std::io;

use crate::path::normalize_path;

#[derive(Clone)]
pub(crate) struct OriginConfig {
    pub(crate) listen: String,
    pub(crate) path: String,
    pub(crate) uuid: [u8; 16],
}

pub(crate) fn parse_config() -> io::Result<OriginConfig> {
    let args = parse_args();
    let listen = args.get("listen").cloned().unwrap_or_else(|| "127.0.0.1:43128".to_string());
    let path = normalize_path(args.get("path").map(String::as_str).unwrap_or("/"));
    let uuid_raw = args
        .get("uuid")
        .map(String::as_str)
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing --uuid"))?;
    Ok(OriginConfig { listen, path, uuid: parse_uuid(uuid_raw)? })
}

fn parse_args() -> HashMap<String, String> {
    let mut parsed = HashMap::new();
    let mut args = std::env::args().skip(1);
    while let Some(flag) = args.next() {
        if !flag.starts_with("--") {
            continue;
        }
        let value = args.next().unwrap_or_default();
        parsed.insert(flag.trim_start_matches("--").to_owned(), value);
    }
    parsed
}

fn parse_uuid(raw: &str) -> io::Result<[u8; 16]> {
    let normalized: String = raw.chars().filter(|character| *character != '-').collect();
    let bytes = hex::decode(&normalized)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid UUID {raw}: {error}")))?;
    if bytes.len() != 16 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("invalid UUID {raw}: expected 16 bytes")));
    }
    let mut uuid = [0u8; 16];
    uuid.copy_from_slice(&bytes);
    Ok(uuid)
}

#[cfg(test)]
mod tests {
    use super::parse_uuid;

    #[test]
    fn parse_uuid_accepts_dashed_and_compact_forms() {
        assert_eq!(
            parse_uuid("550e8400-e29b-41d4-a716-446655440000").expect("dashed UUID"),
            parse_uuid("550e8400e29b41d4a716446655440000").expect("compact UUID"),
        );
    }
}
