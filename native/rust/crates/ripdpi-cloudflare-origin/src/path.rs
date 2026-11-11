/// Longest accepted session id. Clients use a 36-character UUID; the id is
/// copied into the session table before authentication, so it is bounded.
const MAX_SESSION_ID_LEN: usize = 64;

pub(crate) fn normalize_path(path: &str) -> String {
    let path = path.split_once('?').map_or(path, |(path, _)| path);
    if path.starts_with('/') { path.to_owned() } else { format!("/{path}") }
}

pub(crate) fn extract_session_id(base_path: &str, request_path: &str) -> Option<String> {
    let base = normalize_path(base_path);
    let prefix = if base.ends_with('/') { base } else { format!("{base}/") };
    // URI.path() is escaped; decode once, without treating a decoded '?' as a query.
    let decoded = decode_path(request_path)?;
    decoded
        .strip_prefix(&prefix)
        .filter(|id| !id.is_empty() && id.len() <= MAX_SESSION_ID_LEN && !id.contains('/'))
        .map(str::to_owned)
}

fn decode_path(path: &str) -> Option<String> {
    let mut decoded = Vec::with_capacity(path.len());
    let mut bytes = path.bytes();
    while let Some(byte) = bytes.next() {
        if byte == b'%' {
            let high = char::from(bytes.next()?).to_digit(16)?;
            let low = char::from(bytes.next()?).to_digit(16)?;
            decoded.push(u8::try_from(high * 16 + low).ok()?);
        } else {
            decoded.push(byte);
        }
    }
    String::from_utf8(decoded).ok()
}

#[cfg(test)]
mod tests {
    use super::extract_session_id;

    #[test]
    fn extract_session_id_matches_root_path() {
        assert_eq!(Some("session123".to_string()), extract_session_id("/", "/session123"));
        assert_eq!(None, extract_session_id("/", "/session123/extra"));
    }

    #[test]
    fn extract_session_id_matches_nested_base_path() {
        assert_eq!(Some("session123".to_string()), extract_session_id("/api/v1/stream", "/api/v1/stream/session123"),);
        assert_eq!(None, extract_session_id("/api/v1/stream", "/api/v1/stream"));
    }

    #[test]
    fn configured_query_does_not_change_the_matched_path() {
        for base in ["/xh?ed=2048", "/xh/?ed=2048", "xh?ed=2048"] {
            assert_eq!(Some("session123".to_string()), extract_session_id(base, "/xh/session123"));
            assert_eq!(None, extract_session_id(base, "/other/session123"));
        }
        assert_eq!(Some("session123".to_string()), extract_session_id("?ed=2048", "/session123"));
        assert_eq!(Some("session123".to_string()), extract_session_id("/?ed=2048", "/session123"));
        assert_eq!(
            Some("session123".to_string()),
            extract_session_id("/literal%3Fpath?ed=2048", "/literal%253Fpath/session123")
        );
        assert_eq!(None, extract_session_id("/xh?ed=2048", &format!("/xh/{}", "a".repeat(65))));
    }

    #[test]
    fn extract_session_id_rejects_overlong_ids() {
        let id = "a".repeat(65);
        assert_eq!(None, extract_session_id("/", &format!("/{id}")));
        assert_eq!(None, extract_session_id("/base", &format!("/base/{id}")));
        let uuid = "0d3c6b5e-8f8f-4a2b-9c1d-1f2e3d4c5b6a";
        assert_eq!(Some(uuid.to_string()), extract_session_id("/base", &format!("/base/{uuid}")));
    }
}
