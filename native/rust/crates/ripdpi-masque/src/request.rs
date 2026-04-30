use std::io;

use crate::auth::AuthHeader;
use crate::config::MasqueConfig;

pub(crate) fn apply_request_headers(
    mut builder: http::request::Builder,
    config: &MasqueConfig,
    auth_header: Option<&AuthHeader>,
) -> io::Result<http::request::Builder> {
    if let Some(header) = auth_header {
        builder = builder.header(header.name, header.value.as_str());
    }
    if let Some(geohash) = config.cloudflare_geohash_header.as_deref().filter(|value| !value.trim().is_empty()) {
        builder = builder.header("sec-ch-geohash", geohash);
    }
    Ok(builder)
}
