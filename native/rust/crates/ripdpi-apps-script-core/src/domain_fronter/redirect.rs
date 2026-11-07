use url::{Position, Url};

use super::FronterError;
use super::headers;
use super::http;
use super::response::HttpResponse;

/// # Cancel safety:
///
/// NOT cancel-safe: each hop sends a request and consumes a response.
/// Cancellation must discard the sender and its connection driver.
pub(super) async fn follow(
    sender: &mut http::Sender,
    mut response: HttpResponse,
    initial_url: &str,
) -> Result<HttpResponse, FronterError> {
    let mut current = Url::parse(initial_url).map_err(|_| invalid_redirect())?;
    for _ in 0..5 {
        if !matches!(response.status, 301 | 302 | 303 | 307 | 308) {
            break;
        }
        let Some(location) = headers::value(&response.headers, "location") else {
            break;
        };
        current = parse_redirect(&current, location)?;
        let path = &current[Position::BeforePath..Position::AfterQuery];
        let host = &current[Position::BeforeHost..Position::AfterPort];
        response = http::send_get(sender, path, host).await?;
    }
    Ok(response)
}

fn parse_redirect(current: &Url, location: &str) -> Result<Url, FronterError> {
    let mut next = current.join(location).map_err(|_| invalid_redirect())?;
    if !matches!(next.scheme(), "http" | "https")
        || !next.has_host()
        || !next.username().is_empty()
        || next.password().is_some()
    {
        return Err(invalid_redirect());
    }
    next.set_fragment(None);
    Ok(next)
}

fn invalid_redirect() -> FronterError {
    FronterError::BadResponse("invalid redirect URL".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_redirect_references_without_fragments() {
        let base = Url::parse("https://old.example/base/start?old=1").expect("base URL");
        for (reference, expected_host, expected_target) in [
            ("https://host.example?token=x", "host.example", "/?token=x"),
            ("next?token=x", "old.example", "/base/next?token=x"),
            ("../next", "old.example", "/next"),
            ("?token=x", "old.example", "/base/start?token=x"),
            ("//new.example/next", "new.example", "/next"),
            ("https://[2001:db8::1]:8443/a#secret", "[2001:db8::1]:8443", "/a"),
            ("/next?token=x#secret", "old.example", "/next?token=x"),
        ] {
            let next = parse_redirect(&base, reference).expect("supported redirect");
            assert_eq!(&next[Position::BeforeHost..Position::AfterPort], expected_host);
            assert_eq!(&next[Position::BeforePath..Position::AfterQuery], expected_target);
            assert_eq!(next.fragment(), None);
        }
    }

    #[test]
    fn invalid_redirect_errors_do_not_echo_credentials() {
        let base = Url::parse("https://old.example/start").expect("base URL");
        for reference in [
            "file:///tmp/private?token=secret-value",
            "javascript:secret-value",
            "https://new.example:invalid/?token=secret-value",
            "https://[invalid]/?token=secret-value",
            "https://user:secret-value@new.example/path",
        ] {
            let error = parse_redirect(&base, reference).expect_err("invalid redirect");
            assert_eq!(error.to_string(), "bad response: invalid redirect URL");
            assert!(!format!("{error:?}").contains("secret-value"));
        }
    }

    /// # Cancel safety:
    /// cancel-safe: the test owns and drops both transport ends and all futures.
    #[tokio::test(flavor = "current_thread")]
    async fn redirect_chain_keeps_the_new_host_on_one_connection() {
        use hyper_util::rt::TokioIo;
        use std::time::Duration;
        use tokio::io::{AsyncReadExt, AsyncWriteExt, duplex};

        let (client, mut peer) = duplex(4096);
        let (mut sender, connection) =
            hyper::client::conn::http1::handshake(TokioIo::new(client)).await.expect("client handshake");
        let client_job = async move {
            let response = HttpResponse {
                status: 302,
                headers: vec![("location".to_string(), "https://new.example:8443/base/start?token=one".to_string())],
                body: Vec::new(),
            };
            let result = follow(&mut sender, response, "https://old.example/original").await;
            drop(sender);
            result
        };
        let server_job = async move {
            let mut requests = Vec::new();
            for reply in [
                b"HTTP/1.1 302 Found\r\nLocation: /next?token=two#fragment\r\nContent-Length: 0\r\n\r\n".as_slice(),
                b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".as_slice(),
            ] {
                let mut head = Vec::new();
                while !head.ends_with(b"\r\n\r\n") {
                    head.push(peer.read_u8().await.expect("request head"));
                }
                requests.push(String::from_utf8(head).expect("UTF-8 request head"));
                peer.write_all(reply).await.expect("response");
            }
            requests
        };
        let (result, requests, driver) =
            tokio::time::timeout(Duration::from_secs(2), async { tokio::join!(client_job, server_job, connection) })
                .await
                .expect("bounded redirect exchange");
        driver.expect("connection driver");
        let response = result.expect("redirect chain");
        assert_eq!(response.status, 200);
        assert_eq!(response.body, b"ok");
        assert!(requests[0].starts_with("GET /base/start?token=one HTTP/1.1\r\n"));
        assert!(requests[1].starts_with("GET /next?token=two HTTP/1.1\r\n"));
        for request in requests {
            assert!(request.to_ascii_lowercase().contains("\r\nhost: new.example:8443\r\n"));
            assert!(!request.contains("old.example"));
            assert!(!request.contains("#fragment"));
        }
    }
}
