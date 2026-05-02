/// Opaque resolver-memory scope owned by the DNS resolver crate.
///
/// This crate does not currently infer Wi-Fi SSID/BSSID, cellular operator,
/// or other platform network identity on its own. Callers may supply an
/// opaque token such as a network fingerprint; otherwise the resolver falls
/// back to the global scope.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ResolverNetworkScope(String);

impl ResolverNetworkScope {
    pub fn new(id: impl Into<String>) -> Self {
        let id = id.into();
        if id.trim().is_empty() {
            return Self::default();
        }
        Self(id)
    }

    pub fn global() -> Self {
        Self::default()
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl Default for ResolverNetworkScope {
    fn default() -> Self {
        Self("global".to_string())
    }
}

impl From<&str> for ResolverNetworkScope {
    fn from(value: &str) -> Self {
        Self::new(value)
    }
}

impl From<String> for ResolverNetworkScope {
    fn from(value: String) -> Self {
        Self::new(value)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResolverOracleObservation {
    Agreement,
    PartialOverlap { shared_answers: usize, resolver_only_answers: usize, oracle_only_answers: usize },
    Disagreement,
    Poisoned,
}
