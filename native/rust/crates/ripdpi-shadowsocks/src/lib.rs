//! Shadowsocks outbound client — AEAD-2022 and legacy AEAD cipher framing.
//!
//! # Supported ciphers
//!
//! AEAD-2022 (SIP022):
//! - `2022-blake3-aes-128-gcm`
//! - `2022-blake3-aes-256-gcm`
//! - `2022-blake3-chacha20-poly1305`
//!
//! Legacy AEAD (SIP004):
//! - `aes-128-gcm`
//! - `aes-256-gcm`
//! - `chacha20-ietf-poly1305`
//!
//! Stream ciphers (`rc4`, `aes-cfb`, `chacha20`, `salsa20`, etc.) are rejected
//! with [`CipherError::Unsupported`] and are never silently downgraded.
//!
//! # Password security
//!
//! Passwords are wrapped in [`SecretString`] which implements neither `Debug`
//! nor `Display`, preventing accidental exposure in logs or formatted output.

#![forbid(unsafe_code)]

pub mod cipher;
pub mod tcp;
pub mod udp;
pub mod uri;

pub use cipher::{Cipher, CipherError, CipherKey, PresharedKey, SecretString};
pub use tcp::TcpStream;
pub use udp::UdpPacket;
pub use uri::{ShadowsocksUri, UriError};
