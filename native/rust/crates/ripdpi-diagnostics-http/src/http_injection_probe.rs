#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InjectionVerdict {
    Clean,
    Injected(String),
    RedirectToPortal,
    ConnectionResetAfterRequest,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FingerprintKind {
    TransparentProxyHeader,
    MiddleboxHtmlMarker,
    CaptiveRedirect,
}

pub struct HttpInjectionFingerprint {
    pub id: &'static str,
    pub kind: FingerprintKind,
    pub marker: &'static str,
}

pub static BUILTIN_FINGERPRINTS: &[HttpInjectionFingerprint] = &[
    HttpInjectionFingerprint {
        id: "transparent-proxy-isp",
        kind: FingerprintKind::TransparentProxyHeader,
        marker: "nginx-cache-isp",
    },
    HttpInjectionFingerprint {
        id: "ru-rkn-middlebox",
        kind: FingerprintKind::MiddleboxHtmlMarker,
        marker: "Доступ к ресурсу ограничен",
    },
    HttpInjectionFingerprint {
        id: "captive-portal-redirect",
        kind: FingerprintKind::CaptiveRedirect,
        marker: "https://block.example/",
    },
];

pub fn classify_http_response(status: u16, headers: &[(String, String)], body: &str) -> InjectionVerdict {
    if status == 0 {
        return InjectionVerdict::ConnectionResetAfterRequest;
    }

    for fp in BUILTIN_FINGERPRINTS {
        match fp.kind {
            FingerprintKind::TransparentProxyHeader => {
                for (name, value) in headers {
                    if name.eq_ignore_ascii_case("via") && value.to_ascii_lowercase().contains(fp.marker) {
                        return InjectionVerdict::Injected(fp.id.to_string());
                    }
                }
            }
            FingerprintKind::MiddleboxHtmlMarker => {
                if body.contains(fp.marker) {
                    return InjectionVerdict::Injected(fp.id.to_string());
                }
            }
            FingerprintKind::CaptiveRedirect => {
                if (300..=399).contains(&status) {
                    for (name, value) in headers {
                        if name.eq_ignore_ascii_case("location") && value.starts_with(fp.marker) {
                            return InjectionVerdict::RedirectToPortal;
                        }
                    }
                }
            }
        }
    }

    InjectionVerdict::Clean
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hdr(name: &str, value: &str) -> (String, String) {
        (name.to_string(), value.to_string())
    }

    #[test]
    fn clean_200_no_markers_returns_clean() {
        let headers = vec![hdr("content-type", "text/html"), hdr("server", "nginx/1.24")];
        let body = "<html><body>Hello world</body></html>";
        assert_eq!(classify_http_response(200, &headers, body), InjectionVerdict::Clean);
    }

    #[test]
    fn via_header_with_nginx_cache_isp_returns_injected() {
        let headers = vec![hdr("content-type", "text/html"), hdr("via", "1.1 nginx-cache-isp (squid/4.14)")];
        let body = "<html><body>some page</body></html>";
        assert_eq!(
            classify_http_response(200, &headers, body),
            InjectionVerdict::Injected("transparent-proxy-isp".to_string())
        );
    }

    #[test]
    fn russian_rkn_body_marker_returns_injected() {
        let headers = vec![hdr("content-type", "text/html; charset=utf-8")];
        let body = "<html><body>Доступ к ресурсу ограничен по решению суда.</body></html>";
        assert_eq!(
            classify_http_response(200, &headers, body),
            InjectionVerdict::Injected("ru-rkn-middlebox".to_string())
        );
    }

    #[test]
    fn redirect_to_captive_portal_returns_redirect_to_portal() {
        let headers = vec![
            hdr("location", "https://block.example/notice?url=http%3A%2F%2Fexample.com"),
            hdr("content-length", "0"),
        ];
        assert_eq!(classify_http_response(302, &headers, ""), InjectionVerdict::RedirectToPortal);
    }

    #[test]
    fn status_zero_returns_connection_reset() {
        assert_eq!(classify_http_response(0, &[], ""), InjectionVerdict::ConnectionResetAfterRequest);
    }
}
