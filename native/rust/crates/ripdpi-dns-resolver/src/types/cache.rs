#[derive(Debug, Clone)]
pub(crate) struct DnsCryptCachedCertificate {
    pub(crate) resolver_public_key: [u8; 32],
    pub(crate) client_magic: [u8; 8],
    pub(crate) valid_from: u32,
    pub(crate) valid_until: u32,
    /// Certificate `es_version` selecting the cipher suite: 1 = XSalsa20Poly1305,
    /// 2 = XChaCha20Poly1305. Validated by the parser to one of these two values.
    pub(crate) es_version: u16,
}
