use tokio::io::{AsyncRead, AsyncWrite};

use super::FronterError;
use super::headers;
use super::http;
use super::response::{self, HttpResponse};

pub(super) async fn follow<S>(
    stream: &mut S,
    mut response: HttpResponse,
    default_host: &str,
) -> Result<HttpResponse, FronterError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    for _ in 0..5 {
        if !matches!(response.status, 301 | 302 | 303 | 307 | 308) {
            break;
        }
        let Some(location) = headers::value(&response.headers, "location") else {
            break;
        };
        let (redirect_path, redirect_host) = parse_redirect(location);
        http::send_get(stream, &redirect_path, redirect_host.as_deref().unwrap_or(default_host)).await?;
        response = response::read(stream).await?;
    }
    Ok(response)
}

fn parse_redirect(value: &str) -> (String, Option<String>) {
    if let Some(rest) = value.strip_prefix("https://").or_else(|| value.strip_prefix("http://")) {
        let slash = rest.find('/').unwrap_or(rest.len());
        let host = rest[..slash].to_string();
        let path = if slash < rest.len() { rest[slash..].to_string() } else { "/".to_string() };
        return (path, Some(host));
    }
    (value.to_string(), None)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_redirect_urls() {
        let (path, host) = parse_redirect("https://example.com/next");
        assert_eq!(path, "/next");
        assert_eq!(host.as_deref(), Some("example.com"));
    }
}
