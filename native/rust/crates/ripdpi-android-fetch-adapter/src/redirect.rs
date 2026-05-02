use std::io;

use http::header::LOCATION;
use http::StatusCode;
use url::Url;

use crate::owned_tls_http::dto::RawHttpResponse;

pub(crate) fn redirect_target(current_url: &Url, response: &RawHttpResponse) -> io::Result<Option<Url>> {
    match response.status_code {
        StatusCode::MOVED_PERMANENTLY
        | StatusCode::FOUND
        | StatusCode::SEE_OTHER
        | StatusCode::TEMPORARY_REDIRECT
        | StatusCode::PERMANENT_REDIRECT => {
            let Some(location) = response.headers.get(LOCATION) else {
                return Ok(None);
            };
            let location = location.to_str().map_err(|error| {
                io::Error::new(io::ErrorKind::InvalidData, format!("invalid redirect location: {error}"))
            })?;
            let location = current_url.join(location).map_err(|error| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("invalid redirect target from {current_url}: {error}"),
                )
            })?;
            if current_url.scheme() == "https" && location.scheme() == "http" {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("refusing HTTPS to HTTP redirect downgrade from {current_url} to {location}"),
                ));
            }
            Ok(Some(location))
        }
        _ => Ok(None),
    }
}
