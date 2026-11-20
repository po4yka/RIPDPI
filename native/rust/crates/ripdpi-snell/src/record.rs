// SPDX-License-Identifier: GPL-3.0-or-later
// Derived from sagernet/sing-snell, Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>.

//! Snell v4 record codec, ported from sing-snell `snellv4/record.go`.

use std::io;

use crate::crypto::{DirectionCipher, SALT_LEN, TAG_LEN};

const HEADER_VERSION: u8 = 0x04;
const HEADER_PLAIN_LEN: usize = 7;
const HEADER_CIPHER_LEN: usize = HEADER_PLAIN_LEN + TAG_LEN;
const MAX_PAYLOAD: usize = 0x3fff;
// Surge frame sizing constants that sing-snell reproduces.
const FRAME_SIZE: usize = 1460;
const FIRST_RECORD_OVERHEAD: usize = 55;
const RESET_RECORD_OVERHEAD: usize = 39;
const INITIAL_PADDING_MIN: usize = 0x100;
const INITIAL_PADDING_SPAN: usize = 0x100;
const PAYLOAD_RESET_INTERVAL_SECS: i64 = 31;

/// Encodes the client-to-server direction.
pub(crate) struct RecordWriter {
    cipher: DirectionCipher,
    salt: [u8; SALT_LEN],
    salt_sent: bool,
    initial_padding_len: usize,
    payload_limit: usize,
    last_write_unix: i64,
}

impl RecordWriter {
    pub(crate) fn new(psk: &[u8]) -> io::Result<Self> {
        let mut salt = [0u8; SALT_LEN];
        rand::fill(&mut salt);
        Ok(Self {
            cipher: DirectionCipher::new(psk, &salt)?,
            salt,
            salt_sent: false,
            initial_padding_len: INITIAL_PADDING_MIN + rand::random_range(0..INITIAL_PADDING_SPAN),
            payload_limit: 0,
            last_write_unix: 0,
        })
    }

    /// Appends the records for one application write to `out` (sing-snell `writeBytesLocked`): the
    /// payload limit advances once per write and every chunk of that write uses it.
    pub(crate) fn encode_write(&mut self, data: &[u8], now_unix: i64, out: &mut Vec<u8>) -> io::Result<()> {
        let limit = self.payload_limit_for(now_unix);
        self.advance_payload_limit(limit, now_unix);
        for chunk in data.chunks(limit) {
            let padding_len = if self.salt_sent { 0 } else { self.initial_padding_len };
            self.encode_record(chunk, padding_len, out)?;
        }
        Ok(())
    }

    /// Appends the zero-length record that ends this direction.
    pub(crate) fn encode_end(&mut self, out: &mut Vec<u8>) -> io::Result<()> {
        self.encode_record(&[], 0, out)
    }

    fn payload_limit_for(&self, now_unix: i64) -> usize {
        if !self.salt_sent {
            return FRAME_SIZE - FIRST_RECORD_OVERHEAD - self.initial_padding_len;
        }
        if self.last_write_unix != 0
            && now_unix - self.last_write_unix < PAYLOAD_RESET_INTERVAL_SECS
            && self.payload_limit > 0
        {
            return self.payload_limit;
        }
        FRAME_SIZE - RESET_RECORD_OVERHEAD
    }

    fn advance_payload_limit(&mut self, limit: usize, now_unix: i64) {
        self.last_write_unix = now_unix;
        self.payload_limit = if limit < MAX_PAYLOAD {
            (limit + FRAME_SIZE - RESET_RECORD_OVERHEAD).min(MAX_PAYLOAD)
        } else {
            MAX_PAYLOAD
        };
    }

    fn encode_record(&mut self, payload: &[u8], padding_len: usize, out: &mut Vec<u8>) -> io::Result<()> {
        debug_assert!(payload.len() <= MAX_PAYLOAD && (padding_len == 0 || !payload.is_empty()));
        if !self.salt_sent {
            out.extend_from_slice(&self.salt);
            self.salt_sent = true;
        }
        let mut header = [0u8; HEADER_PLAIN_LEN];
        header[0] = HEADER_VERSION;
        header[3..5].copy_from_slice(&(padding_len as u16).to_be_bytes());
        header[5..7].copy_from_slice(&(payload.len() as u16).to_be_bytes());
        let tag = self.cipher.seal(&mut header)?;
        out.extend_from_slice(&header);
        out.extend_from_slice(&tag);
        if payload.is_empty() {
            return Ok(());
        }
        let padding_start = out.len();
        out.resize(padding_start + padding_len, 0);
        let cipher_start = out.len();
        out.extend_from_slice(payload);
        let tag = self.cipher.seal(&mut out[cipher_start..])?;
        out.extend_from_slice(&tag);
        if padding_len > 0 {
            let (padding, payload_cipher) = out[padding_start..].split_at_mut(padding_len);
            fill_padding(padding, payload_cipher);
            interleave(padding, payload_cipher);
        }
        Ok(())
    }
}

/// Swaps every even index below the shorter length; the operation is its own inverse.
fn interleave(padding: &mut [u8], payload_cipher: &mut [u8]) {
    for index in (0..padding.len().min(payload_cipher.len())).step_by(2) {
        std::mem::swap(&mut padding[index], &mut payload_cipher[index]);
    }
}

fn random_int31() -> u64 {
    u64::from(rand::random::<u32>() & 0x7fff_ffff)
}

/// sing-snell `fillPadding`: shapes the padding so the record's one-bit ratio lands near a random
/// target instead of the ~1.0 ratio of ciphertext; outside the shaping window it is random.
fn fill_padding(padding: &mut [u8], payload_cipher: &[u8]) {
    let counted = payload_cipher.len() & !3;
    let ones: usize = payload_cipher[..counted].iter().map(|byte| byte.count_ones() as usize).sum();
    let zeros = 8 * payload_cipher.len() - ones;
    let ratio = ones as f64 / zeros as f64;
    if zeros == 0 || ratio <= 0.5 || ratio >= 1.6 {
        rand::fill(padding);
        return;
    }
    let base = if zeros < ones { 0.4 } else { 1.6 };
    let target = base + random_int31() as f64 / 2_147_483_647.0 / 10.0;
    let total_bits = 8 * (padding.len() + payload_cipher.len());
    // Go `int(float64)` truncates toward zero, as `as i64` does.
    let target_ones = (total_bits as f64 * (target / (target + 1.0)) - ones as f64) as i64;
    match usize::try_from(target_ones) {
        Ok(target_ones) if target_ones <= 8 * padding.len() => fill_padding_with_bit_count(padding, target_ones),
        _ => rand::fill(padding),
    }
}

/// sing-snell `fillPaddingWithBitCount`: a front-to-back shuffle of `one_bits` set bits, keeping
/// Surge's `rand() / (RAND_MAX / remaining + 1)` index mapping.
pub(crate) fn fill_padding_with_bit_count(padding: &mut [u8], one_bits: usize) {
    let total_bits = 8 * padding.len();
    let mut bits = vec![false; total_bits];
    bits[..one_bits].fill(true);
    let mut remaining = total_bits as u64;
    let mut position = 0usize;
    while remaining > 1 {
        let swap_index = position + (random_int31() / (0x7fff_ffff / remaining + 1)) as usize;
        bits.swap(position, swap_index);
        position += 1;
        remaining -= 1;
    }
    padding.fill(0);
    for (index, _) in bits.iter().enumerate().filter(|(_, bit)| **bit) {
        padding[index / 8] |= 1 << (index % 8);
    }
}

/// One decoded record.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Record {
    Data(Vec<u8>),
    End,
}

enum ReadState {
    Salt,
    Header,
    Body { padding_len: usize, payload_len: usize },
}

/// Decodes one direction from bytes buffered by the caller.
pub(crate) struct RecordReader {
    psk: Vec<u8>,
    cipher: Option<DirectionCipher>,
    state: ReadState,
}

impl RecordReader {
    pub(crate) fn new(psk: &[u8]) -> Self {
        Self { psk: psk.to_vec(), cipher: None, state: ReadState::Salt }
    }

    /// True when no partial record is pending, so an upstream EOF here is not a truncation.
    pub(crate) fn at_record_boundary(&self) -> bool {
        matches!(self.state, ReadState::Salt | ReadState::Header)
    }

    /// Consumes complete parts from the front of `input`. Returns `None` when more bytes are needed.
    pub(crate) fn decode(&mut self, input: &mut Vec<u8>) -> io::Result<Option<Record>> {
        loop {
            match self.state {
                ReadState::Salt => {
                    let Some(salt) = input.get(..SALT_LEN) else { return Ok(None) };
                    let salt: [u8; SALT_LEN] = salt.try_into().map_err(io::Error::other)?;
                    self.cipher = Some(DirectionCipher::new(&self.psk, &salt)?);
                    input.drain(..SALT_LEN);
                    self.state = ReadState::Header;
                }
                ReadState::Header => {
                    if input.len() < HEADER_CIPHER_LEN {
                        return Ok(None);
                    }
                    let (header, tag) = input[..HEADER_CIPHER_LEN].split_at_mut(HEADER_PLAIN_LEN);
                    self.cipher_mut()?.open(header, tag)?;
                    if header[0] != HEADER_VERSION {
                        return Err(io::Error::new(io::ErrorKind::InvalidData, "bad Snell record version"));
                    }
                    let padding_len = usize::from(u16::from_be_bytes([header[3], header[4]]));
                    let payload_len = usize::from(u16::from_be_bytes([header[5], header[6]]));
                    input.drain(..HEADER_CIPHER_LEN);
                    if payload_len == 0 {
                        return Ok(Some(Record::End));
                    }
                    self.state = ReadState::Body { padding_len, payload_len };
                }
                ReadState::Body { padding_len, payload_len } => {
                    let cipher_len = payload_len + TAG_LEN;
                    if input.len() < padding_len + cipher_len {
                        return Ok(None);
                    }
                    let (padding, rest) = input[..padding_len + cipher_len].split_at_mut(padding_len);
                    interleave(padding, rest);
                    let (payload, tag) = rest.split_at_mut(payload_len);
                    self.cipher_mut()?.open(payload, tag)?;
                    let payload = payload.to_vec();
                    input.drain(..padding_len + cipher_len);
                    self.state = ReadState::Header;
                    return Ok(Some(Record::Data(payload)));
                }
            }
        }
    }

    fn cipher_mut(&mut self) -> io::Result<&mut DirectionCipher> {
        self.cipher.as_mut().ok_or_else(|| io::Error::other("Snell cipher not initialized"))
    }
}

#[cfg(test)]
pub(crate) mod test_support {
    pub(crate) use super::fill_padding_with_bit_count;
}
