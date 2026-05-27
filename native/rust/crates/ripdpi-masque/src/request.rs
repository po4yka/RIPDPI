use std::io;

use crate::auth::AuthHeader;
use crate::config::MasqueConfig;

pub(crate) fn apply_request_headers(
    mut builder: http::request::Builder,
    _config: &MasqueConfig,
    auth_header: Option<&AuthHeader>,
) -> io::Result<http::request::Builder> {
    if let Some(header) = auth_header {
        builder = builder.header(header.name, header.value.as_str());
    }
    Ok(builder)
}
