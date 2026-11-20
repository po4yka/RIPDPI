// SPDX-License-Identifier: GPL-3.0-or-later
// Derived from sagernet/sing-snell, Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>.

use std::io;

use aes_gcm::aead::consts::U12;
use aes_gcm::{AeadInOut, Aes128Gcm, KeyInit, Nonce, Tag};
use argon2::{Algorithm, Argon2, Params, Version};

pub(crate) const SALT_LEN: usize = 16;
pub(crate) const TAG_LEN: usize = 16;

/// One direction's AES-128-GCM state: a key derived from the PSK and that direction's salt, and a
/// little-endian counter nonce advanced once per sealed or opened part.
pub(crate) struct DirectionCipher {
    cipher: Aes128Gcm,
    nonce: [u8; 12],
}

impl DirectionCipher {
    pub(crate) fn new(psk: &[u8], salt: &[u8; SALT_LEN]) -> io::Result<Self> {
        // sing-snell crypto.go: argon2.IDKey(psk, salt, 3, 8, 1, 32)[:16].
        let params = Params::new(8, 3, 1, Some(32)).map_err(io::Error::other)?;
        let mut derived = [0u8; 32];
        Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
            .hash_password_into(psk, salt, &mut derived)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?;
        let cipher = Aes128Gcm::new_from_slice(&derived[..16]).map_err(io::Error::other)?;
        Ok(Self { cipher, nonce: [0; 12] })
    }

    /// Encrypts `buffer` in place and returns its tag.
    pub(crate) fn seal(&mut self, buffer: &mut [u8]) -> io::Result<[u8; TAG_LEN]> {
        let tag = self
            .cipher
            .encrypt_inout_detached(&Nonce::<U12>::from(self.nonce), &[], buffer.into())
            .map_err(|_| io::Error::other("Snell record encryption failed"))?;
        self.advance();
        Ok(tag.into())
    }

    /// Decrypts `buffer` in place against `tag`.
    pub(crate) fn open(&mut self, buffer: &mut [u8], tag: &[u8]) -> io::Result<()> {
        let tag = Tag::try_from(tag).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad Snell tag"))?;
        self.cipher
            .decrypt_inout_detached(&Nonce::<U12>::from(self.nonce), &[], buffer.into(), &tag)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "Snell record authentication failed"))?;
        self.advance();
        Ok(())
    }

    /// sing-snell `IncreaseNonce`: little-endian increment over all 12 bytes.
    fn advance(&mut self) {
        for byte in &mut self.nonce {
            *byte = byte.wrapping_add(1);
            if *byte != 0 {
                return;
            }
        }
    }
}
