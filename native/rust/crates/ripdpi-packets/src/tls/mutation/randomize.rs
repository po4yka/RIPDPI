use crate::types::{OracleRng, PacketMutation};
use crate::util::{fill_random_tls_host_like_c, read_u16};

use super::super::detect::{is_tls_client_hello, tls_marker_info};

/// Randomize TLS Random, Session ID, and Key Share fields in place.
pub fn randomize_tls_seeded_inplace(buf: &mut [u8], seed: u32) -> isize {
    if buf.len() < 44 {
        return 0;
    }
    let sid_len = buf[43] as usize;
    if buf.len() < 44 + sid_len + 2 {
        return 0;
    }
    let mut rng = OracleRng::seeded(seed);
    for byte in &mut buf[11..43] {
        *byte = rng.next_u8();
    }
    for byte in &mut buf[44..44 + sid_len] {
        *byte = rng.next_u8();
    }
    let Some(parsed) = crate::tls_nom::parse_client_hello_record(buf) else {
        return 0;
    };
    let Some(ks_offs) = crate::tls_nom::find_extension_offset(&parsed, 0x0033) else {
        return 0;
    };
    if ks_offs + 6 >= buf.len() {
        return 0;
    }
    let Some(ks_size) = read_u16(buf, ks_offs + 2) else {
        return 0;
    };
    if ks_offs + 4 + ks_size > buf.len() {
        return 0;
    }
    let ks_end = ks_offs + 4 + ks_size;
    let mut group_offs = ks_offs + 6;
    while group_offs + 4 < ks_end {
        let Some(group_size) = read_u16(buf, group_offs + 2) else {
            return 0;
        };
        let group_end = group_offs + 4 + group_size;
        if group_end > ks_end || group_end > buf.len() {
            return 0;
        }
        for byte in &mut buf[group_offs + 4..group_end] {
            *byte = rng.next_u8();
        }
        group_offs += 4 + group_size;
    }
    0
}

pub fn randomize_tls_seeded_like_c(input: &[u8], seed: u32) -> PacketMutation {
    let mut output = input.to_vec();
    let rc = randomize_tls_seeded_inplace(&mut output, seed);
    PacketMutation { rc, bytes: output }
}

/// Randomize the SNI hostname bytes in place.
pub fn randomize_tls_sni_seeded_inplace(buf: &mut [u8], seed: u32) -> isize {
    let Some(markers) = tls_marker_info(buf) else {
        return -1;
    };
    let mut rng = OracleRng::seeded(seed);
    fill_random_tls_host_like_c(&mut buf[markers.host_start..markers.host_end], &mut rng);
    0
}

pub fn randomize_tls_sni_seeded_like_c(input: &[u8], seed: u32) -> PacketMutation {
    let mut output = input.to_vec();
    let rc = randomize_tls_sni_seeded_inplace(&mut output, seed);
    PacketMutation { rc, bytes: output }
}

/// Copy the Session ID from `original` into `fake` in place.
pub fn duplicate_tls_session_id_inplace(fake: &mut [u8], original: &[u8]) -> isize {
    if !is_tls_client_hello(fake) || !is_tls_client_hello(original) || fake.len() < 44 || original.len() < 44 {
        return -1;
    }
    let sid_len = fake[43] as usize;
    if fake.len() < 44 + sid_len || original[43] as usize != sid_len || original.len() < 44 + sid_len {
        return -1;
    }
    fake[44..44 + sid_len].copy_from_slice(&original[44..44 + sid_len]);
    0
}

pub fn duplicate_tls_session_id_like_c(fake_input: &[u8], original_input: &[u8]) -> PacketMutation {
    let mut output = fake_input.to_vec();
    let rc = duplicate_tls_session_id_inplace(&mut output, original_input);
    PacketMutation { rc, bytes: output }
}
