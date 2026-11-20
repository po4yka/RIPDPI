// SPDX-License-Identifier: GPL-3.0-or-later
// Derived from sagernet/sing-snell, Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>.

//! Snell v4 TCP client for the `snell` deploy role (v4 client against a v4/v5 server, no obfs).
//!
//! Snell has no published specification. This crate is a Rust port of the open implementation
//! that the server runs: `github.com/sagernet/sing-snell` at `bc5a12ac736f` (the version pinned by
//! sing-box `v1.14.3`), packages `snell` and `snellv4`, Copyright (C) 2022 by nekohasekai,
//! GPL-3.0-or-later, so this crate is GPL-3.0-or-later too and is excluded from the commercial
//! license (`LICENSING.md`). GPLv3 section 13 permits combining it with the AGPL-3.0 workspace.
//!
//! # Wire format
//! ```text
//! direction stream = salt[16] record*
//! record           = AEAD(header[7]) padding[pad_len] AEAD(payload[len])   (padding and payload
//!                    ciphertext are interleaved: every even index below min(pad_len, len + 16)
//!                    is swapped between them)
//! header           = 0x04 0x00 0x00 pad_len:u16be len:u16be                 (len = 0 ends the stream)
//! key              = Argon2id(psk, salt, t=3, m=8 KiB, p=1, 32 bytes)[..16], AES-128-GCM,
//!                    nonce = u64le(counter) || 0u32, one counter step per sealed part
//! request          = 0x01 0x05 len(user_key) user_key len(host) host port:u16be
//! reply            = 0x00 data... | 0x02 code len(message) message
//! ```
//! The first client record carries 256..=511 bytes of padding shaped to a target bit ratio.

#![forbid(unsafe_code)]

mod crypto;
mod record;
mod request;
mod stream;

pub use stream::{SnellStream, connect};

use std::io;

/// Client credentials for one Snell v4 server.
#[derive(Clone)]
pub struct SnellClientConfig {
    psk: Vec<u8>,
    user_key: Vec<u8>,
}

impl SnellClientConfig {
    /// `user_key` may be empty for a single-user server; it must fit one length byte.
    pub fn new(psk: impl Into<Vec<u8>>, user_key: impl Into<Vec<u8>>) -> io::Result<Self> {
        let psk = psk.into();
        let user_key = user_key.into();
        if psk.is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Snell pre-shared key is required"));
        }
        if user_key.len() > usize::from(u8::MAX) {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Snell user key exceeds 255 bytes"));
        }
        Ok(Self { psk, user_key })
    }
}

impl std::fmt::Debug for SnellClientConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SnellClientConfig").finish_non_exhaustive()
    }
}

#[cfg(test)]
mod tests;
