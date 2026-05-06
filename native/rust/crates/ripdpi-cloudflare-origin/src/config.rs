use std::io;

use crate::path::normalize_path;

#[derive(Clone)]
pub(crate) struct OriginConfig {
    pub(crate) listen: String,
    pub(crate) path: String,
    pub(crate) uuid: [u8; 16],
}

pub(crate) fn parse_config() -> io::Result<OriginConfig> {
    parse_config_from(pico_args::Arguments::from_env())
}

fn parse_config_from(mut args: pico_args::Arguments) -> io::Result<OriginConfig> {
    let listen = args
        .opt_value_from_str::<_, String>("--listen")
        .map_err(invalid_args)?
        .unwrap_or_else(|| "127.0.0.1:43128".to_string());
    let path =
        normalize_path(args.opt_value_from_str::<_, String>("--path").map_err(invalid_args)?.as_deref().unwrap_or("/"));
    let uuid_raw: String = args
        .opt_value_from_str::<_, String>("--uuid")
        .map_err(invalid_args)?
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing --uuid"))?;
    let remaining = args.finish();
    if !remaining.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("unexpected arguments: {remaining:?}")));
    }
    Ok(OriginConfig { listen, path, uuid: parse_uuid(&uuid_raw)? })
}

fn invalid_args(error: pico_args::Error) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, error)
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
