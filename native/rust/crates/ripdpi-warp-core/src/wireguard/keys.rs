use anyhow::{anyhow, Context};
use base64::{engine::general_purpose::STANDARD, Engine as _};

pub(crate) fn decode_key(value: &str) -> anyhow::Result<[u8; 32]> {
    let bytes = STANDARD.decode(value).context("base64 decode failed")?;
    bytes.try_into().map_err(|_| anyhow!("expected 32-byte key"))
}

pub(crate) fn reserved_bytes_from_client_id(client_id: Option<&str>) -> [u8; 3] {
    let mut reserved = [0u8; 3];
    if let Some(client_id) = client_id {
        if let Ok(decoded) = STANDARD.decode(client_id) {
            for (index, value) in decoded.iter().take(3).enumerate() {
                reserved[index] = *value;
            }
        }
    }
    reserved
}

pub(crate) fn apply_reserved_bytes(packet: &mut [u8], reserved: [u8; 3]) {
    if packet.len() >= 4 {
        packet[1..4].copy_from_slice(&reserved);
    }
}
