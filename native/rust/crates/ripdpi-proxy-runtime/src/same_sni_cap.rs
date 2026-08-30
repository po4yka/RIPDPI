use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use ripdpi_session_limit::DEFAULT_EXIT_IP_SESSION_CAP;

type SessionKey = (String, String);

#[derive(Debug, Clone, Default)]
pub struct SameSniProfileCaps {
    per_profile: HashMap<String, usize>,
}

impl SameSniProfileCaps {
    #[must_use]
    pub fn with_profile(mut self, profile: impl Into<String>, cap: usize) -> Self {
        self.per_profile.insert(profile.into(), cap.clamp(1, DEFAULT_EXIT_IP_SESSION_CAP));
        self
    }

    #[must_use]
    pub fn cap_for(&self, profile: &str) -> usize {
        self.per_profile.get(profile).copied().unwrap_or(DEFAULT_EXIT_IP_SESSION_CAP)
    }
}

#[derive(Debug, Clone)]
pub struct SameSniProfileLimiter {
    counts: Arc<Mutex<HashMap<SessionKey, usize>>>,
    caps: SameSniProfileCaps,
}

impl SameSniProfileLimiter {
    #[must_use]
    pub fn new(caps: SameSniProfileCaps) -> Self {
        Self { counts: Arc::new(Mutex::new(HashMap::new())), caps }
    }

    #[must_use]
    pub fn try_acquire(&self, sni: &str, profile: &str) -> Option<SameSniProfileGuard> {
        let normalized_sni = normalize_sni(sni)?;
        let normalized_profile = normalize_profile(profile);
        let key = (normalized_sni, normalized_profile.clone());
        let mut counts = self.counts.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let active = counts.entry(key.clone()).or_default();
        if *active >= self.caps.cap_for(&normalized_profile) {
            return None;
        }
        *active += 1;
        Some(SameSniProfileGuard { counts: Arc::clone(&self.counts), key })
    }

    #[must_use]
    pub fn active(&self, sni: &str, profile: &str) -> usize {
        let Some(sni) = normalize_sni(sni) else { return 0 };
        self.counts
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .get(&(sni, normalize_profile(profile)))
            .copied()
            .unwrap_or_default()
    }
}

#[derive(Debug)]
pub struct SameSniProfileGuard {
    counts: Arc<Mutex<HashMap<SessionKey, usize>>>,
    key: SessionKey,
}

impl Drop for SameSniProfileGuard {
    fn drop(&mut self) {
        let mut counts = self.counts.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(active) = counts.get_mut(&self.key) {
            *active = active.saturating_sub(1);
            if *active == 0 {
                counts.remove(&self.key);
            }
        }
    }
}

fn normalize_sni(sni: &str) -> Option<String> {
    let normalized = sni.trim().trim_end_matches('.').to_ascii_lowercase();
    (!normalized.is_empty()).then_some(normalized)
}

fn normalize_profile(profile: &str) -> String {
    let normalized = profile.trim().to_ascii_lowercase();
    if normalized.is_empty() { "unknown".to_string() } else { normalized }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counters_are_independent_by_normalized_sni_and_profile_and_release_with_raii() {
        let limiter = SameSniProfileLimiter::new(SameSniProfileCaps::default().with_profile("firefox_stable", 1));
        let guard = limiter.try_acquire("Example.COM.", "firefox_stable").expect("first slot");
        assert!(limiter.try_acquire("example.com", "firefox_stable").is_none());
        assert!(limiter.try_acquire("other.example", "firefox_stable").is_some());
        assert!(limiter.try_acquire("example.com", "safari_stable").is_some());
        drop(guard);
        assert!(limiter.try_acquire("example.com", "firefox_stable").is_some());
    }

    #[test]
    fn unknown_profiles_retain_existing_default_cap_of_eight() {
        let limiter = SameSniProfileLimiter::new(SameSniProfileCaps::default());
        let _guards: Vec<_> =
            (0..8).map(|_| limiter.try_acquire("example.com", "pass-through").expect("slot")).collect();
        assert!(limiter.try_acquire("example.com", "pass-through").is_none());
    }
}
