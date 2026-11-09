use boring::ssl::SslConnectorBuilder;

use crate::Error;
use crate::profile::ProfileConfig;

pub fn apply_profile(builder: &mut SslConnectorBuilder, config: &ProfileConfig) -> Result<(), Error> {
    builder.set_cipher_list(config.cipher_list_tls12)?;
    // The boring 5.3.0 safe API has no TLS 1.3 cipher-order setter. The pinned
    // BoringSSL C API exposes SSL_CTX_set1_tls13_ciphers, but it is not called
    // here. The backend uses its AES-hardware-dependent default order;
    // config.ciphersuites_tls13 remains reference metadata.
    builder.set_curves_list(config.curves)?;
    builder.set_sigalgs_list(config.sigalgs)?;
    builder.set_min_proto_version(Some(config.min_version))?;
    builder.set_max_proto_version(Some(config.max_version))?;

    // Build ALPN wire format: each protocol is prefixed with its length byte.
    let mut alpn_wire = Vec::new();
    for proto in config.alpn {
        alpn_wire.push(proto.len() as u8);
        alpn_wire.extend_from_slice(proto);
    }
    builder.set_alpn_protos(&alpn_wire)?;

    builder.set_grease_enabled(config.grease_enabled);
    builder.set_permute_extensions(config.permute_extensions);

    Ok(())
}
