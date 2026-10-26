use std::collections::BTreeMap;
use std::io;

use http::header::LOCATION;
use http::{Method, StatusCode};
use url::Url;

use crate::dto::RawHttpResponse;

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

/// Upper bound on followed redirects, whatever the caller asks for.
pub(crate) const MAX_REDIRECTS: usize = 10;

/// Request headers that carry credentials or pin the origin. They are not
/// sent to a different origin than the one the caller addressed.
const ORIGIN_BOUND_HEADERS: &[&str] = &["authorization", "cookie", "proxy-authorization", "host"];

/// Prepare the next hop of a redirect from `from` to `to` with `status`:
/// rewrite the method as RFC 9110 section 15.4 says (303 to GET unless HEAD;
/// POST to GET for 301 and 302) and drop origin-bound headers when the origin
/// changes (WHATWG Fetch, HTTP-redirect fetch).
pub(crate) fn prepare_redirect_hop(
    status: StatusCode,
    method: &Method,
    from: &Url,
    to: &Url,
    headers: &mut BTreeMap<String, String>,
) -> Method {
    if from.origin() != to.origin() {
        headers.retain(|name, _| !ORIGIN_BOUND_HEADERS.iter().any(|bound| name.eq_ignore_ascii_case(bound)));
    }
    match status {
        StatusCode::SEE_OTHER if *method != Method::HEAD => Method::GET,
        StatusCode::MOVED_PERMANENTLY | StatusCode::FOUND if *method == Method::POST => Method::GET,
        _ => method.clone(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn headers() -> BTreeMap<String, String> {
        [("Authorization", "Bearer secret"), ("Cookie", "sid=1"), ("Host", "a.example"), ("User-Agent", "RIPDPI")]
            .into_iter()
            .map(|(name, value)| (name.to_string(), value.to_string()))
            .collect()
    }

    fn url(value: &str) -> Url {
        Url::parse(value).expect("url")
    }

    #[test]
    fn cross_origin_redirect_drops_credentials_and_host_override() {
        let mut headers = headers();
        let method = prepare_redirect_hop(
            StatusCode::FOUND,
            &Method::GET,
            &url("https://a.example/x"),
            &url("https://b.example/y"),
            &mut headers,
        );

        assert_eq!(method, Method::GET);
        assert_eq!(headers.keys().map(String::as_str).collect::<Vec<_>>(), vec!["User-Agent"]);
    }

    #[test]
    fn same_origin_redirect_keeps_headers() {
        let mut headers = headers();
        prepare_redirect_hop(
            StatusCode::FOUND,
            &Method::GET,
            &url("https://a.example/x"),
            &url("https://a.example/y"),
            &mut headers,
        );

        assert_eq!(headers, self::headers());
    }

    #[test]
    fn redirect_method_follows_rfc_9110() {
        let (from, to) = (url("https://a.example/x"), url("https://a.example/y"));
        let mut headers = BTreeMap::new();
        let mut hop = |status, method: Method| prepare_redirect_hop(status, &method, &from, &to, &mut headers);

        assert_eq!(hop(StatusCode::SEE_OTHER, Method::POST), Method::GET);
        assert_eq!(hop(StatusCode::SEE_OTHER, Method::HEAD), Method::HEAD);
        assert_eq!(hop(StatusCode::FOUND, Method::POST), Method::GET);
        assert_eq!(hop(StatusCode::MOVED_PERMANENTLY, Method::POST), Method::GET);
        assert_eq!(hop(StatusCode::TEMPORARY_REDIRECT, Method::POST), Method::POST);
        assert_eq!(hop(StatusCode::PERMANENT_REDIRECT, Method::PUT), Method::PUT);
    }
}
