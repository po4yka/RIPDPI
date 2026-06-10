pub mod client;

use thiserror::Error;

#[rustfmt::skip]
pub mod consts {
    pub const SOCKS4_VERSION:                          u8 = 0x04;

    pub const SOCKS4_CMD_CONNECT:                      u8 = 0x01;
    pub const SOCKS4_CMD_BIND:                         u8 = 0x02;

    pub const SOCKS4_REPLY_SUCCEEDED:                  u8 = 0x5a;
    pub const SOCKS4_REPLY_FAILED:                     u8 = 0x5b;
    pub const SOCKS4_REPLY_HOST_UNREACHABLE:           u8 = 0x5c;
    pub const SOCKS4_REPLY_INVALID_USER:               u8 = 0x5d;
}

/// SOCKS4 reply code
#[derive(Error, Debug, Copy, Clone)]
pub enum ReplyError {
    #[error("Succeeded")]
    Succeeded,
    #[error("General failure")]
    GeneralFailure,
    #[error("Host unreachable")]
    HostUnreachable,
    #[error("Address type not supported")]
    AddressTypeNotSupported,
    #[error("Invalid user")]
    InvalidUser,

    #[error("Unknown response")]
    UnknownResponse(u8),
}

#[derive(Debug, PartialEq)]
pub enum Socks4Command {
    Connect,
    Bind,
}

#[allow(dead_code)]
impl Socks4Command {
    #[inline]
    #[rustfmt::skip]
    pub fn as_u8(&self) -> u8 {
        match self {
            Socks4Command::Connect   => consts::SOCKS4_CMD_CONNECT,
            Socks4Command::Bind      => consts::SOCKS4_CMD_BIND,
        }
    }

    #[inline]
    #[rustfmt::skip]
    pub fn from_u8(code: u8) -> Option<Socks4Command> {
        match code {
            consts::SOCKS4_CMD_CONNECT      => Some(Socks4Command::Connect),
            consts::SOCKS4_CMD_BIND         => Some(Socks4Command::Bind),
            _ => None,
        }
    }
}

impl ReplyError {
    /// Serialize a [`ReplyError`] back to its SOCKS4 wire reply code.
    ///
    /// Returns `Some(code)` for variants that map to a defined SOCKS4 reply
    /// byte. Variants that have no canonical SOCKS4 wire encoding return
    /// `None`:
    ///
    /// - [`ReplyError::AddressTypeNotSupported`] is a SOCKS5-only concept
    ///   surfaced internally (e.g. for IPv6 targets) and has no SOCKS4 byte.
    /// - [`ReplyError::UnknownResponse`] wraps a byte that the protocol does
    ///   not define; it is not re-serialized.
    ///
    /// This method is total over all variants and never panics, so a SOCKS4
    /// server returning an unusual reply code can be round-tripped safely.
    #[inline]
    #[rustfmt::skip]
    pub fn as_u8(self) -> Option<u8> {
        match self {
            ReplyError::Succeeded                 => Some(consts::SOCKS4_REPLY_SUCCEEDED),
            ReplyError::GeneralFailure            => Some(consts::SOCKS4_REPLY_FAILED),
            ReplyError::HostUnreachable           => Some(consts::SOCKS4_REPLY_HOST_UNREACHABLE),
            ReplyError::InvalidUser               => Some(consts::SOCKS4_REPLY_INVALID_USER),
            ReplyError::AddressTypeNotSupported   => None,
            ReplyError::UnknownResponse(_)        => None,
        }
    }

    #[inline]
    #[rustfmt::skip]
    pub fn from_u8(code: u8) -> ReplyError {
        match code {
            consts::SOCKS4_REPLY_SUCCEEDED         => ReplyError::Succeeded,
            consts::SOCKS4_REPLY_FAILED            => ReplyError::GeneralFailure,
            consts::SOCKS4_REPLY_HOST_UNREACHABLE  => ReplyError::HostUnreachable,
            consts::SOCKS4_REPLY_INVALID_USER      => ReplyError::InvalidUser,
            _                                      => ReplyError::UnknownResponse(code),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every `ReplyError` variant must round-trip through `as_u8` without
    /// panicking. The mapped variants return `Some`; the two non-SOCKS4
    /// variants return `None`. This guards against the historical `panic!`
    /// fall-through that crashed the calling thread on an unusual reply.
    #[test]
    fn as_u8_is_total_over_all_variants() {
        // Mapped variants serialize to their defined wire byte.
        assert_eq!(ReplyError::Succeeded.as_u8(), Some(consts::SOCKS4_REPLY_SUCCEEDED));
        assert_eq!(ReplyError::GeneralFailure.as_u8(), Some(consts::SOCKS4_REPLY_FAILED));
        assert_eq!(ReplyError::HostUnreachable.as_u8(), Some(consts::SOCKS4_REPLY_HOST_UNREACHABLE));
        assert_eq!(ReplyError::InvalidUser.as_u8(), Some(consts::SOCKS4_REPLY_INVALID_USER));

        // Non-SOCKS4 variants have no canonical wire byte; they must not panic.
        assert_eq!(ReplyError::AddressTypeNotSupported.as_u8(), None);
        assert_eq!(ReplyError::UnknownResponse(0x42).as_u8(), None);
    }

    /// `from_u8` accepts any byte (defined codes map to variants, everything
    /// else becomes `UnknownResponse`) — no input panics.
    #[test]
    fn from_u8_accepts_every_byte_without_panic() {
        for code in 0u8..=255 {
            let reply = ReplyError::from_u8(code);
            match code {
                consts::SOCKS4_REPLY_SUCCEEDED => assert!(matches!(reply, ReplyError::Succeeded)),
                consts::SOCKS4_REPLY_FAILED => assert!(matches!(reply, ReplyError::GeneralFailure)),
                consts::SOCKS4_REPLY_HOST_UNREACHABLE => assert!(matches!(reply, ReplyError::HostUnreachable)),
                consts::SOCKS4_REPLY_INVALID_USER => assert!(matches!(reply, ReplyError::InvalidUser)),
                other => assert!(matches!(reply, ReplyError::UnknownResponse(b) if b == other)),
            }
        }
    }
}
