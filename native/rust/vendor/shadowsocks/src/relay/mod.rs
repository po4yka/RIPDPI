//! Relay server in local and server side implementations.

pub use self::socks5::Address;

pub mod socks5;
pub mod tcprelay;
pub mod udprelay;

/// AEAD 2022 maximum padding length
#[cfg(feature = "aead-cipher-2022")]
const AEAD2022_MAX_PADDING_SIZE: usize = 900;

/// Get a properly AEAD 2022 padding size according to payload's length.
/// SIP022 requires a request header to carry a payload or non-zero padding, so an empty payload never gets 0.
#[cfg(feature = "aead-cipher-2022")]
fn get_aead_2022_padding_size(payload: &[u8]) -> usize {
    use std::cell::RefCell;

    use rand::{RngExt, rngs::SmallRng};

    thread_local! {
        static PADDING_RNG: RefCell<SmallRng> = RefCell::new(rand::make_rng());
    }

    if payload.is_empty() {
        PADDING_RNG.with(|rng| rng.borrow_mut().random_range::<usize, _>(1..=AEAD2022_MAX_PADDING_SIZE))
    } else {
        0
    }
}

#[cfg(all(test, feature = "aead-cipher-2022"))]
mod padding_tests {
    use super::{AEAD2022_MAX_PADDING_SIZE, get_aead_2022_padding_size};

    #[test]
    fn empty_payload_always_gets_nonzero_padding() {
        for _ in 0..20_000 {
            let padding = get_aead_2022_padding_size(&[]);
            assert!((1..=AEAD2022_MAX_PADDING_SIZE).contains(&padding));
        }
    }

    #[test]
    fn non_empty_payload_gets_no_padding() {
        assert_eq!(get_aead_2022_padding_size(b"x"), 0);
    }
}
