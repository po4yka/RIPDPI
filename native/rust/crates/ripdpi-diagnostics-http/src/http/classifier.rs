use crate::blockpage_fingerprints::{BlockpageFingerprint, match_blockpage};

use super::types::{HttpObservation, HttpResponse};

pub fn classify_http_response(response: &HttpResponse) -> String {
    let blockpage_body = body_has_explicit_blockpage_message(&response.body);
    if response.status_code == 200 && !blockpage_body {
        "http_ok".to_string()
    } else if response.status_code == 451 || blockpage_body {
        "http_blockpage".to_string()
    } else {
        format!("http_status_{}", response.status_code)
    }
}

pub fn classify_http_response_with_fingerprints(
    response: &HttpResponse,
    fingerprints: &[BlockpageFingerprint],
) -> (String, Option<String>) {
    if let Some(fp_name) = match_blockpage(response, fingerprints) {
        return ("http_blockpage".to_string(), Some(fp_name));
    }
    (classify_http_response(response), None)
}

pub fn describe_http_observation(observation: &HttpObservation) -> String {
    match (&observation.response, &observation.error) {
        (Some(response), _) => format!(
            "{} {} {}",
            response.status_code,
            response.reason,
            response.headers.get("server").cloned().unwrap_or_else(|| "server=unknown".to_string())
        ),
        (None, Some(error)) => error.clone(),
        (None, None) => "none".to_string(),
    }
}

pub fn is_blockpage(observation: &HttpObservation) -> bool {
    observation.status == "http_blockpage"
}

fn body_has_explicit_blockpage_message(body: &[u8]) -> bool {
    if body.len() > 8192 {
        return false;
    }
    let text = String::from_utf8_lossy(body).to_ascii_lowercase();
    [
        "this site has been blocked",
        "this website has been blocked",
        "this page has been blocked",
        "blocked by the government",
        "blocked by your internet service provider",
        "blocked by your isp",
        "blocked by your provider",
        "blocked by your network",
    ]
    .iter()
    .any(|needle| text.contains(needle))
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener};

    use crate::http::try_http_request;
    use crate::transport::{TargetAddress, direct_transport};

    #[test]
    fn live_http_does_not_report_a_court_article_as_a_blockpage() {
        let article = "<html><h1>Новости</h1><p>Компания выиграла спор решением суда.</p></html>";
        for size in [article.len(), 12011] {
            let mut body = article.as_bytes().to_vec();
            body.resize(size, b' ');
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
            let address = listener.local_addr().unwrap();
            let server = std::thread::spawn(move || {
                let (mut stream, _) = listener.accept().unwrap();
                let _ = stream.read(&mut [0; 1024]).unwrap();
                let head = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                stream.write_all(head.as_bytes()).unwrap();
                stream.write_all(&body).unwrap();
            });

            let observation = try_http_request(
                &TargetAddress::Ip(address.ip()),
                address.port(),
                &direct_transport(),
                "example.org",
                "/",
                false,
            );
            server.join().unwrap();
            assert_eq!(observation.status, "http_ok", "size={size}");
            assert!(observation.error.is_none());
        }
    }
}
