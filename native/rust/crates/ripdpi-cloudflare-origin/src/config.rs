use std::io::{self, Cursor, Read};

use crate::path::normalize_path;

const STARTUP_CONFIG_FLAG: &str = "--config-stdin";
const STARTUP_CONFIG_MAGIC: &[u8; 16] = b"RIPDPI-CF-ORIGIN";
const STARTUP_CONFIG_SCHEMA_VERSION: u8 = 1;
const STARTUP_CONFIG_MAX_BYTES: usize = 16 * 1024;

#[derive(Clone)]
pub(crate) struct OriginConfig {
    pub(crate) listen: String,
    pub(crate) path: String,
    pub(crate) uuid: [u8; 16],
    /// Parent `VpnService.protect` UDS path (`RIPDPI_PROTECT_PATH`), or `None`
    /// when unset — outbound connects are then unprotected (desktop / VPN down).
    pub(crate) protect_path: Option<String>,
}

pub(crate) fn parse_config() -> io::Result<OriginConfig> {
    let stdin = io::stdin();
    let mut stdin = stdin.lock();
    parse_config_from_reader(
        pico_args::Arguments::from_env(),
        &mut stdin,
        ripdpi_subprocess_protect::protect_path_from_env(),
    )
}

pub(crate) fn parse_config_from_reader(
    mut args: pico_args::Arguments,
    mut startup_input: impl Read,
    protect_path: Option<String>,
) -> io::Result<OriginConfig> {
    let use_stdin = args.contains(STARTUP_CONFIG_FLAG);
    let remaining = args.finish();
    if !remaining.is_empty() {
        return Err(invalid_input("unexpected Cloudflare origin arguments"));
    }
    if !use_stdin {
        return Err(invalid_input("Cloudflare origin requires --config-stdin"));
    }

    let (listen, path, uuid_raw) = read_startup_config(&mut startup_input)?;
    if listen.trim().is_empty() {
        return Err(invalid_input("Cloudflare origin listener is missing"));
    }
    if uuid_raw.trim().is_empty() {
        return Err(invalid_input("Cloudflare origin UUID is missing"));
    }

    Ok(OriginConfig { listen, path: normalize_path(&path), uuid: parse_uuid(&uuid_raw)?, protect_path })
}

fn read_startup_config(input: &mut impl Read) -> io::Result<(String, String, String)> {
    let mut payload = Vec::new();
    input.take((STARTUP_CONFIG_MAX_BYTES + 1) as u64).read_to_end(&mut payload)?;
    if payload.len() > STARTUP_CONFIG_MAX_BYTES {
        return Err(invalid_input("Cloudflare origin startup config is too large"));
    }

    let mut cursor = Cursor::new(payload.as_slice());
    let mut magic = [0u8; STARTUP_CONFIG_MAGIC.len()];
    read_exact_field(&mut cursor, &mut magic)?;
    if &magic != STARTUP_CONFIG_MAGIC {
        return Err(invalid_input("Cloudflare origin startup config magic is invalid"));
    }
    let mut schema = [0u8; 1];
    read_exact_field(&mut cursor, &mut schema)?;
    if schema[0] != STARTUP_CONFIG_SCHEMA_VERSION {
        return Err(invalid_input("Cloudflare origin startup config schema is unsupported"));
    }

    let listen = read_string_field(&mut cursor, "listener")?;
    let path = read_string_field(&mut cursor, "path")?;
    let uuid = read_string_field(&mut cursor, "UUID")?;
    if cursor.position() != payload.len() as u64 {
        return Err(invalid_input("Cloudflare origin startup config contains trailing data"));
    }
    Ok((listen, path, uuid))
}

fn read_string_field(cursor: &mut Cursor<&[u8]>, field_name: &str) -> io::Result<String> {
    let mut length = [0u8; 2];
    read_exact_field(cursor, &mut length)?;
    let mut bytes = vec![0u8; u16::from_be_bytes(length) as usize];
    read_exact_field(cursor, &mut bytes)?;
    String::from_utf8(bytes).map_err(|_| invalid_input(&format!("Cloudflare origin startup {field_name} is not UTF-8")))
}

fn read_exact_field(cursor: &mut Cursor<&[u8]>, target: &mut [u8]) -> io::Result<()> {
    cursor.read_exact(target).map_err(|_| invalid_input("Cloudflare origin startup config is truncated"))
}

fn invalid_input(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

fn parse_uuid(raw: &str) -> io::Result<[u8; 16]> {
    let normalized: String = raw.chars().filter(|character| *character != '-').collect();
    let bytes = hex::decode(&normalized).map_err(|_| invalid_input("Cloudflare origin UUID is invalid"))?;
    if bytes.len() != 16 {
        return Err(invalid_input("Cloudflare origin UUID is invalid"));
    }
    let mut uuid = [0u8; 16];
    uuid.copy_from_slice(&bytes);
    Ok(uuid)
}

#[cfg(test)]
mod tests {
    use std::ffi::OsString;

    use super::{parse_config_from_reader, parse_uuid};

    const STARTUP_MAGIC: &[u8; 16] = b"RIPDPI-CF-ORIGIN";

    fn startup_frame(listen: &str, path: &str, uuid: &str) -> Vec<u8> {
        let mut frame = Vec::new();
        frame.extend_from_slice(STARTUP_MAGIC);
        frame.push(1);
        for field in [listen, path, uuid] {
            let bytes = field.as_bytes();
            frame.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
            frame.extend_from_slice(bytes);
        }
        frame
    }

    fn parse_frame(frame: &[u8]) -> std::io::Result<super::OriginConfig> {
        parse_config_from_reader(pico_args::Arguments::from_vec(vec![OsString::from("--config-stdin")]), frame, None)
    }

    #[test]
    fn parse_uuid_accepts_dashed_and_compact_forms() {
        assert_eq!(
            parse_uuid("550e8400-e29b-41d4-a716-446655440000").expect("dashed UUID"),
            parse_uuid("550e8400e29b41d4a716446655440000").expect("compact UUID"),
        );
    }

    #[test]
    fn stdin_startup_frame_preserves_listener_path_and_uuid() {
        let config = parse_frame(&startup_frame(
            "127.0.0.1:43128",
            "/private-xhttp-path",
            "550e8400-e29b-41d4-a716-446655440000",
        ))
        .expect("framed startup config");

        assert_eq!(config.listen, "127.0.0.1:43128");
        assert_eq!(config.path, "/private-xhttp-path");
        assert_eq!(config.uuid, parse_uuid("550e8400-e29b-41d4-a716-446655440000").unwrap());
    }

    #[test]
    fn stdin_startup_frame_rejects_trailing_data_without_echoing_identity() {
        let identity = "550e8400-e29b-41d4-a716-446655440000";
        let mut frame = startup_frame("127.0.0.1:43128", "/private-xhttp-path", identity);
        frame.push(0x41);

        let error = parse_frame(&frame).err().expect("trailing startup data must fail");

        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
        assert!(!error.to_string().contains(identity));
        assert!(!error.to_string().contains("private-xhttp-path"));
    }

    #[test]
    fn stdin_startup_frame_rejects_oversized_input() {
        let oversized = vec![0u8; 16 * 1024 + 1];

        let error = parse_frame(&oversized).err().expect("oversized startup data must fail");

        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
        assert!(error.to_string().contains("too large"));
    }

    #[test]
    fn invalid_uuid_error_never_echoes_peer_controlled_value() {
        let invalid_identity = "credential-shaped-invalid-uuid";

        let error = parse_frame(&startup_frame("127.0.0.1:43128", "/xhttp", invalid_identity))
            .err()
            .expect("invalid UUID must fail");

        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
        assert!(!error.to_string().contains(invalid_identity));
    }

    #[test]
    fn cli_rejects_legacy_identity_arguments() {
        let args = pico_args::Arguments::from_vec(
            ["--listen", "127.0.0.1:43128", "--path", "/xhttp", "--uuid", "secret"]
                .into_iter()
                .map(OsString::from)
                .collect(),
        );

        let error = parse_config_from_reader(args, &[][..], None).err().expect("legacy argv config must fail");

        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
        assert!(!error.to_string().contains("secret"));
    }
}
