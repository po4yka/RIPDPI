pub(crate) fn normalize_path(path: &str) -> String {
    let trimmed = path.trim().trim_matches('/');
    if trimmed.is_empty() { "/".to_owned() } else { format!("/{trimmed}") }
}

pub(crate) fn extract_session_id(base_path: &str, request_path: &str) -> Option<String> {
    let normalized_base = normalize_path(base_path);
    if normalized_base == "/" {
        let session_id = request_path.trim_matches('/');
        let segments = session_id.split('/').collect::<Vec<_>>();
        return if segments.len() == 1 && !segments[0].is_empty() { Some(segments[0].to_owned()) } else { None };
    }
    let prefix = format!("{normalized_base}/");
    request_path.strip_prefix(&prefix).filter(|value| !value.is_empty() && !value.contains('/')).map(str::to_owned)
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
}
