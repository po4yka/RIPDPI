use std::time::Duration;

/// Commonly-blocked domains used for warmup probes.
pub(crate) const PROBE_DOMAINS: &[&str] =
    &["youtube.com", "discord.com", "telegram.org", "signal.org", "instagram.com"];

/// Maximum time to wait for a single probe connection + first response.
pub(crate) const PROBE_TIMEOUT: Duration = Duration::from_secs(5);

/// Maximum total wall-clock time for the entire warmup pass.
pub(crate) const WARMUP_DEADLINE: Duration = Duration::from_secs(30);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn probe_domains_are_non_empty() {
        assert!(!PROBE_DOMAINS.is_empty());
        for domain in PROBE_DOMAINS {
            assert!(!domain.is_empty());
            assert!(domain.contains('.'));
        }
    }
}
