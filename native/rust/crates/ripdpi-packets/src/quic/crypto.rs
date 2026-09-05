use ring::hkdf::{self, HKDF_SHA256, KeyType, Salt};

use crate::types::{QUIC_V1_VERSION, QUIC_V2_VERSION};

struct HkdfLen(usize);
impl KeyType for HkdfLen {
    fn len(&self) -> usize {
        self.0
    }
}

const QUIC_V1_SALT: [u8; 20] = [
    0x38, 0x76, 0x2c, 0xf7, 0xf5, 0x59, 0x34, 0xb3, 0x4d, 0x17, 0x9a, 0xe6, 0xa4, 0xc8, 0x0c, 0xad, 0xcc, 0xbb, 0x7f,
    0x0a,
];
const QUIC_V2_SALT: [u8; 20] = [
    0x0d, 0xed, 0xe3, 0xde, 0xf7, 0x00, 0xa6, 0xdb, 0x81, 0x93, 0x81, 0xbe, 0x6e, 0x26, 0x9d, 0xcb, 0xf9, 0xbd, 0x2e,
    0xd9,
];

pub(super) fn quic_hkdf_label(label: &str, out_len: usize) -> Option<Vec<u8>> {
    if out_len > u16::MAX as usize || label.len() > u8::MAX as usize {
        return None;
    }
    let mut info = Vec::with_capacity(2 + 1 + label.len() + 1);
    info.extend_from_slice(&(out_len as u16).to_be_bytes());
    info.push(label.len() as u8);
    info.extend_from_slice(label.as_bytes());
    info.push(0);
    Some(info)
}

pub(super) fn quic_expand_label(secret: &[u8], label: &str, out: &mut [u8]) -> Option<()> {
    let info = quic_hkdf_label(label, out.len())?;
    let prk = hkdf::Prk::new_less_safe(HKDF_SHA256, secret);
    let info_refs: &[&[u8]] = &[&info];
    let okm = prk.expand(info_refs, HkdfLen(out.len())).ok()?;
    okm.fill(out).ok()?;
    Some(())
}

pub(super) fn quic_derive_client_initial_secret(dcid: &[u8], version: u32) -> Option<[u8; 32]> {
    quic_derive_initial_secret(dcid, version, "tls13 client in")
}

pub(super) fn quic_derive_initial_secret(dcid: &[u8], version: u32, label: &str) -> Option<[u8; 32]> {
    let salt_bytes = match version {
        QUIC_V1_VERSION => &QUIC_V1_SALT,
        QUIC_V2_VERSION => &QUIC_V2_SALT,
        _ => return None,
    };
    let salt = Salt::new(HKDF_SHA256, salt_bytes);
    let prk = salt.extract(dcid);
    let mut secret = [0u8; 32];
    let info = quic_hkdf_label(label, secret.len())?;
    let info_refs: &[&[u8]] = &[&info];
    let okm = prk.expand(info_refs, HkdfLen(secret.len())).ok()?;
    okm.fill(&mut secret).ok()?;
    Some(secret)
}
