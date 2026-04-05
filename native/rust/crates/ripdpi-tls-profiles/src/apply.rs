use boring::ssl::SslConnectorBuilder;

use crate::profile::ProfileConfig;
use crate::Error;

pub fn apply_profile(builder: &mut SslConnectorBuilder, config: &ProfileConfig) -> Result<(), Error> {
    builder.set_cipher_list(config.cipher_list_tls12)?;
    // BoringSSL does not expose SSL_CTX_set_ciphersuites -- TLS 1.3 cipher suites
    // are fixed (AES-128-GCM, AES-256-GCM, ChaCha20-Poly1305). This matches both
    // Chrome and Firefox defaults, so no configuration is needed.
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
