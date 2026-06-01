pub fn extract_host_from_url(url: &str) -> Option<String> {
    let without_scheme = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let host = without_scheme.split('/').next()?;
    let host = host.split(':').next()?;
    if host.is_empty() { None } else { Some(host.to_string()) }
}

pub fn extract_path_from_url(url: &str) -> String {
    let without_scheme = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://")).unwrap_or(url);
    match without_scheme.find('/') {
        Some(idx) => without_scheme[idx..].to_string(),
        None => "/".to_string(),
    }
}
