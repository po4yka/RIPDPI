use super::QUIC_FAKE_INITIAL_TARGET_LEN;
use super::build::build_quic_initial_raw;
use super::datagram::decode_quic_initial_datagram;
use super::frames::append_quic_crypto_frame;

/// Re-encrypt a QUIC Initial packet with the TLS ClientHello split across
/// two CRYPTO frames at the given split offset within the ClientHello.
///
/// Returns `None` if the packet is not a valid QUIC Initial or cannot be parsed.
pub fn tamper_quic_initial_split_sni(packet: &[u8], split_offset: usize) -> Option<Vec<u8>> {
    tamper_quic_initial_split_crypto(packet, split_offset)
}

/// Re-encrypt a QUIC Initial packet with the TLS ClientHello split across
/// two CRYPTO frames at the given split offset within the ClientHello.
///
/// Returns `None` if the packet is not a valid QUIC Initial or cannot be parsed.
pub fn tamper_quic_initial_split_crypto(packet: &[u8], split_offset: usize) -> Option<Vec<u8>> {
    let decoded = decode_quic_initial_datagram(packet)?;
    let header = decoded.header;
    let client_hello = decoded.client_hello;
    if split_offset == 0 || split_offset >= client_hello.len() {
        return None;
    }

    let mut plaintext = Vec::new();
    append_quic_crypto_frame(&mut plaintext, 0, &client_hello[..split_offset]);
    append_quic_crypto_frame(&mut plaintext, split_offset as u64, &client_hello[split_offset..]);

    build_quic_initial_raw(
        header.version,
        header.dcid,
        header.scid,
        header.token,
        plaintext,
        QUIC_FAKE_INITIAL_TARGET_LEN,
        0,
    )
}

/// Replace the QUIC version field in a Long Header packet with a different version.
/// This prevents DPI from deriving the correct decryption keys.
///
/// Returns `None` if the packet doesn't have a valid QUIC Long Header.
pub fn tamper_quic_version(packet: &[u8], fake_version: u32) -> Option<Vec<u8>> {
    if packet.len() < 5 || (packet[0] & 0x80) == 0 {
        return None;
    }
    let mut tampered = packet.to_vec();
    tampered[1..5].copy_from_slice(&fake_version.to_be_bytes());
    Some(tampered)
}
