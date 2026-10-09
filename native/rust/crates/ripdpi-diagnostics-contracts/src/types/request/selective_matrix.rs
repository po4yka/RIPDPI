use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SelectiveMatrixTarget {
    pub id: String,
    pub label: String,
    pub url: String,
    pub cohort: String,
    pub infrastructure_group: String,
    pub source_url: String,
    pub source_date: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_verified_at: Option<String>,
    #[serde(default)]
    pub connect_ips: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SelectiveMatrixConfig {
    pub version: u32,
    pub catalog_version: String,
    pub targets: Vec<SelectiveMatrixTarget>,
    #[serde(default = "default_repetitions")]
    pub repetitions: usize,
    #[serde(default = "default_timeout")]
    pub timeout_ms: u64,
    #[serde(default = "default_bytes")]
    pub max_response_bytes: usize,
}
fn default_repetitions() -> usize {
    2
}
fn default_timeout() -> u64 {
    5000
}
fn default_bytes() -> usize {
    65536
}

impl SelectiveMatrixConfig {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.version != 1 || self.catalog_version.is_empty() || self.catalog_version.len() > 128 {
            return Err("invalid_matrix_version");
        }
        if self.targets.is_empty()
            || self.targets.len() > 10
            || !(1..=3).contains(&self.repetitions)
            || !(100..=10000).contains(&self.timeout_ms)
            || !(1..=65536).contains(&self.max_response_bytes)
            || self.targets.len() * self.repetitions * (self.max_response_bytes + 16384) > 2 * 1024 * 1024
        {
            return Err("matrix_budget_exceeded");
        }
        let mut ids = std::collections::BTreeSet::new();
        let mut hosts = std::collections::BTreeSet::new();
        for target in &self.targets {
            let Some(host) = matrix_host(&target.url) else {
                return Err("invalid_matrix_target");
            };
            if !hosts.insert(host.to_ascii_lowercase()) {
                return Err("duplicate_matrix_host");
            }
            if target.id.is_empty()
                || target.id.len() > 128
                || !ids.insert(&target.id)
                || target.label.len() > 256
                || target.infrastructure_group.is_empty()
                || target.infrastructure_group.len() > 128
                || !matches!(target.cohort.as_str(), "declared_available" | "domestic" | "global" | "user")
                || target.url.len() > 2048
                || !target.url.starts_with("https://")
                || target.url.contains(['?', '#', '@', '\\'])
                || target.url.bytes().any(|b| b.is_ascii_control() || b.is_ascii_whitespace())
                || target.source_url.len() > 2048
                || target.source_date.len() > 64
                || target.last_verified_at.as_ref().is_some_and(|v| v.len() > 64)
                || target.connect_ips.len() > 16
                || target.connect_ips.iter().any(|ip| ip.parse::<std::net::IpAddr>().is_err())
            {
                return Err("invalid_matrix_target");
            }
        }
        if self.targets.iter().filter(|target| target.cohort == "user").count() > 4 {
            return Err("too_many_user_targets");
        }
        Ok(())
    }
}

fn matrix_host(url: &str) -> Option<&str> {
    let authority = url.strip_prefix("https://")?.split('/').next()?;
    let host = authority.strip_suffix(":443").unwrap_or(authority);
    if host.len() > 253 || !host.contains('.') || host.parse::<std::net::IpAddr>().is_ok() {
        return None;
    }
    if host.split('.').any(|label| {
        label.is_empty()
            || label.len() > 63
            || label.starts_with('-')
            || label.ends_with('-')
            || !label.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
    }) {
        return None;
    }
    Some(host)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn config() -> SelectiveMatrixConfig {
        serde_json::from_value(serde_json::json!({"version":1,"catalogVersion":"test","targets":[{"id":"a","label":"A","url":"https://example.com/","cohort":"global","infrastructureGroup":"a","sourceUrl":"https://example.com/","sourceDate":"2026-10-09"}]})).unwrap()
    }
    #[test]
    fn defaults_and_bounded_budget() {
        let mut value = config();
        assert_eq!(value.repetitions, 2);
        assert!(value.validate().is_ok());
        value.repetitions = usize::MAX;
        assert!(value.validate().is_err());
        value.repetitions = 3;
        value.targets = (0..10)
            .map(|i| {
                let mut t = value.targets[0].clone();
                t.id = i.to_string();
                t
            })
            .collect();
        assert!(value.validate().is_err());
    }
    #[test]
    fn rejects_invalid_and_duplicate_targets() {
        for url in ["http://example.com/", "https://a/?x=1", "https://user@a/", "https://a/\r\nHost: b"] {
            let mut value = config();
            value.targets[0].url = url.into();
            assert!(value.validate().is_err());
        }
        let mut value = config();
        value.targets.push(value.targets[0].clone());
        assert!(value.validate().is_err());
    }
}
