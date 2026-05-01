use ring::hmac::{self, Key};

pub(crate) const HMAC_LEN: usize = 4;

#[derive(Clone, Debug)]
pub(crate) struct ShadowTlsHmac {
    key: Key,
    data: Vec<u8>,
}

impl ShadowTlsHmac {
    pub(crate) fn new(password: &[u8]) -> Self {
        Self { key: Key::new(hmac::HMAC_SHA1_FOR_LEGACY_USE_ONLY, password), data: Vec::new() }
    }

    pub(crate) fn update(&mut self, data: &[u8]) {
        self.data.extend_from_slice(data);
    }

    pub(crate) fn digest(&self) -> [u8; HMAC_LEN] {
        let tag = hmac::sign(&self.key, &self.data);
        let mut out = [0u8; HMAC_LEN];
        out.copy_from_slice(&tag.as_ref()[..HMAC_LEN]);
        out
    }
}
