use ring::hmac::{self, Key};

pub(crate) const HMAC_LEN: usize = 4;

#[derive(Clone)]
pub(crate) struct ShadowTlsHmac {
    context: hmac::Context,
}

impl ShadowTlsHmac {
    pub(crate) fn new(password: &[u8]) -> Self {
        Self { context: hmac::Context::with_key(&Key::new(hmac::HMAC_SHA1_FOR_LEGACY_USE_ONLY, password)) }
    }

    pub(crate) fn update(&mut self, data: &[u8]) {
        self.context.update(data);
    }

    pub(crate) fn digest(&self) -> [u8; HMAC_LEN] {
        let tag = self.context.clone().sign();
        let mut out = [0u8; HMAC_LEN];
        out.copy_from_slice(&tag.as_ref()[..HMAC_LEN]);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shadowtls_hmac_uses_hmac_sha1_with_4_byte_tag() {
        let mut h = ShadowTlsHmac::new(&[0x0b; 20]);
        h.update(b"Hi There");
        let tag = h.digest();
        assert_eq!(tag, [0xb6, 0x17, 0x31, 0x86], "HMAC-SHA1 truncation must match RFC 2202 Test Case 1");
        assert_eq!(tag.len(), HMAC_LEN);
        assert_eq!(HMAC_LEN, 4, "ShadowTLS v3 specifies a 4-byte truncated tag");
    }

    #[test]
    fn shadowtls_hmac_chained_updates_match_single_update() {
        let mut chained = ShadowTlsHmac::new(b"shared-password");
        chained.update(b"first chunk");
        chained.update(b"-");
        chained.update(b"second chunk");

        let mut single = ShadowTlsHmac::new(b"shared-password");
        single.update(b"first chunk-second chunk");

        assert_eq!(chained.digest(), single.digest());
    }
}
