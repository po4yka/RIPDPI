use std::net::SocketAddr;

pub(crate) fn direct_path_authority_candidates(host: Option<&str>, target: SocketAddr) -> Vec<String> {
    let mut candidates = Vec::new();
    if let Some(host) = normalize_authority(host) {
        candidates.push(host.clone());
        candidates.push(format!("{host}:{}", target.port()));
    }
    let target_authority = target.to_string();
    if let Some(target_authority) = normalize_authority(Some(target_authority.as_str())) {
        candidates.push(target_authority);
    }
    let target_ip = target.ip().to_string();
    if let Some(target_ip) = normalize_authority(Some(target_ip.as_str())) {
        candidates.push(target_ip);
    }
    candidates.sort();
    candidates.dedup();
    candidates
}

pub(crate) fn direct_path_authority_candidates_for_targets(host: Option<&str>, targets: &[SocketAddr]) -> Vec<String> {
    let mut candidates = Vec::new();
    if let Some(host) = normalize_authority(host) {
        candidates.push(host.clone());
        for target in targets {
            candidates.push(format!("{host}:{}", target.port()));
        }
    }
    for target in targets {
        let target_authority = target.to_string();
        if let Some(normalized) = normalize_authority(Some(target_authority.as_str())) {
            candidates.push(normalized);
        }
        let target_ip = target.ip().to_string();
        if let Some(normalized) = normalize_authority(Some(target_ip.as_str())) {
            candidates.push(normalized);
        }
    }
    candidates.sort();
    candidates.dedup();
    candidates
}

pub(crate) fn normalize_authority(value: Option<&str>) -> Option<String> {
    value.map(str::trim).map(|entry| entry.trim_end_matches('.').to_ascii_lowercase()).filter(|entry| !entry.is_empty())
}
