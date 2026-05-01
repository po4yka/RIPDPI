use crate::block_detection::classify_http_response_block;
use crate::{ClassifiedFailure, FailureClass};

pub fn classify_http_blockpage(response: &[u8]) -> Option<ClassifiedFailure> {
    classify_http_response_block(response).filter(|failure| failure.class == FailureClass::HttpBlockpage)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_http_blockpages_from_status_and_body() {
        let response = b"HTTP/1.1 403 Forbidden\r\nServer: test\r\n\r\nthis website has been blocked";
        let classified = classify_http_blockpage(response).expect("blockpage");
        assert_eq!(classified.class, FailureClass::HttpBlockpage);
    }

    #[test]
    fn http_blockpage_detects_status_451() {
        let response = b"HTTP/1.1 451 Unavailable For Legal Reasons\r\nServer: test\r\n\r\nblocked";
        assert!(classify_http_blockpage(response).is_some());
    }

    #[test]
    fn http_blockpage_detects_redirect_302() {
        let response = b"HTTP/1.1 302 Found\r\nLocation: http://block.isp.net\r\n\r\n";
        assert!(classify_http_blockpage(response).is_some());
    }

    #[test]
    fn http_blockpage_detects_body_keywords_on_200() {
        let response = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nThis page is subject to censorship.";
        let f = classify_http_blockpage(response).expect("blockpage from keyword");
        assert_eq!(f.class, FailureClass::HttpBlockpage);
    }

    #[test]
    fn http_blockpage_returns_none_for_clean_200() {
        let response = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nHello, world!";
        assert!(classify_http_blockpage(response).is_none());
    }

    #[test]
    fn http_blockpage_returns_none_for_missing_headers_end() {
        let response = b"HTTP/1.1 403 Forbidden\r\nServer: test";
        assert!(classify_http_blockpage(response).is_none());
    }

    #[test]
    fn http_blockpage_returns_none_for_non_http() {
        let response = b"\x00\x01\x02random binary data";
        assert!(classify_http_blockpage(response).is_none());
    }

    #[test]
    fn http_blockpage_detects_all_keywords() {
        let keywords = ["blocked", "access denied", "forbidden", "restriction", "censorship"];
        for keyword in keywords {
            let response = format!("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nThis page: {keyword}.");
            let f = classify_http_blockpage(response.as_bytes())
                .unwrap_or_else(|| panic!("keyword '{keyword}' should trigger blockpage detection"));
            assert_eq!(f.class, FailureClass::HttpBlockpage);
        }
    }

    #[test]
    fn http_blockpage_keyword_matching_is_case_insensitive() {
        let response = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nACCESS DENIED by firewall";
        assert!(classify_http_blockpage(response).is_some());
    }

    #[test]
    fn http_blockpage_returns_none_for_http2_response() {
        let response = b"HTTP/2 403 Forbidden\r\nServer: test\r\n\r\nblocked";
        assert!(classify_http_blockpage(response).is_none());
    }

    #[test]
    fn http_blockpage_returns_none_for_malformed_status_code() {
        let response = b"HTTP/1.1 abc Not A Number\r\nServer: test\r\n\r\nblocked";
        assert!(classify_http_blockpage(response).is_none());
    }
}
