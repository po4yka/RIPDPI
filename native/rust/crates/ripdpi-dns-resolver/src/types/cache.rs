#[derive(Debug, Clone)]
pub(crate) struct DnsCryptCachedCertificate {
    pub(crate) resolver_public_key: [u8; 32],
    pub(crate) client_magic: [u8; 8],
    pub(crate) valid_from: u32,
    pub(crate) valid_until: u32,
}
