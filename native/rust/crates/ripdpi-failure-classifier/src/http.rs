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
    fn http_blockpage_detects_explicit_block_message_on_200() {
        let response = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nThis site has been blocked by your ISP.";
        let f = classify_http_blockpage(response).expect("blockpage from explicit message");
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
    fn generic_words_on_ordinary_responses_are_not_blockpages() {
        for body in ["blocked", "access denied", "forbidden", "Restrictions apply", "censorship"] {
            let response = format!("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nThis page: {body}.");
            assert!(classify_http_blockpage(response.as_bytes()).is_none(), "'{body}' alone is not evidence");
        }
    }

    #[test]
    fn redirects_to_site_paths_that_mention_block_words_are_not_blockpages() {
        for location in ["https://example.com/search?filter=x", "https://example.com/blockchain", "/warning"] {
            let response = format!("HTTP/1.1 302 Found\r\nLocation: {location}\r\n\r\n");
            assert!(classify_http_response_block(response.as_bytes()).is_none(), "{location} is a site URL");
        }
    }

    #[test]
    fn redirects_to_isp_stub_urls_are_blockpages() {
        for (location, provider) in [
            ("http://domru.ru/block?url=example.com", "dom_ru_block"),
            ("http://lawfilter.ertelecom.ru/", "rostelecom_filter"),
            ("http://lawfilter.isp.example/", "redirect_block_url"),
            ("http://blocklist.isp.example/?u=x", "redirect_block_url"),
            ("http://netfilter.isp.example:8080/", "redirect_block_url"),
            ("http://10.10.0.1/blocked.html", "redirect_block_url"),
            ("http://stub.isp.example/block-page/", "redirect_block_url"),
        ] {
            let response = format!("HTTP/1.1 302 Found\r\nLocation: {location}\r\nContent-Length: 0\r\n\r\n");
            let failure = classify_http_blockpage(response.as_bytes())
                .unwrap_or_else(|| panic!("{location} is an ISP block stub"));
            assert!(failure.evidence.tags.contains(&format!("provider={provider}")), "{location}: {failure:?}");
        }
    }

    #[test]
    fn short_pages_that_say_the_site_is_blocked_are_blockpages() {
        for body in [
            "<html><title>Доступ к ресурсу ограничен</title></html>",
            "Ресурс заблокирован по решению суда",
            "The requested resource is blocked.",
            "Access to the resource is blocked",
            "This URL has been blocked by the network administrator.",
        ] {
            let response = format!("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n{body}");
            assert!(classify_http_blockpage(response.as_bytes()).is_some(), "'{body}' says the site is blocked");
        }
    }

    #[test]
    fn ordinary_pages_that_mention_blocked_are_not_blockpages() {
        for body in [
            "<html><h1>Sign in</h1><p>Your account has been blocked after 5 attempts. Contact support.</p></html>",
            "<p>Pop-ups blocked on this page. Allow pop-ups to continue.</p>",
            "<p>Ограничения сайта: только для зарегистрированных пользователей.</p>",
        ] {
            let response = format!("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n{body}");
            assert!(classify_http_blockpage(response.as_bytes()).is_none(), "'{body}' is an ordinary page");
        }
    }

    #[test]
    fn http_blockpage_keyword_matching_is_case_insensitive() {
        let response = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nTHIS SITE HAS BEEN BLOCKED";
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
