use std::net::SocketAddr;

use ring::digest;

#[derive(Clone, Debug, Eq, Hash, PartialEq)]
pub(super) struct TupleKey {
    pub(super) authority: String,
    pub(super) ip_set_digest: String,
}

pub(super) fn tuple_key_for_targets(host: Option<&str>, targets: &[SocketAddr]) -> Option<TupleKey> {
    let first = *targets.first()?;
    Some(TupleKey { authority: normalize_authority(host, first), ip_set_digest: direct_path_ip_set_digest(targets) })
}

fn normalize_authority(host: Option<&str>, target: SocketAddr) -> String {
    host.map(str::trim).filter(|value| !value.is_empty()).map_or_else(
        || target.to_string().to_ascii_lowercase(),
        |value| format!("{}:{}", value.trim_end_matches('.').to_ascii_lowercase(), target.port()),
    )
}

pub fn direct_path_ip_set_digest(targets: &[SocketAddr]) -> String {
    if targets.is_empty() {
        return String::new();
    }
    let mut members = targets.iter().map(|target| target.ip().to_string()).collect::<Vec<_>>();
    members.sort();
    members.dedup();
    let joined = members.join(",");
    let digest = digest::digest(&digest::SHA256, joined.as_bytes());
    digest.as_ref()[..8].iter().map(|byte| format!("{byte:02x}")).collect()
}
