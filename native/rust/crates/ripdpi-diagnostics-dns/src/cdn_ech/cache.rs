use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::cdn_ech::catalog::CLOUDFLARE_ECH_CONFIG_LIST;
use crate::cdn_ech::source::{EchConfigSource, EchSourceError};
use crate::cdn_ech::validation::validate_ech_config_list_bytes;

struct CachedEch {
    config: Vec<u8>,
    fetched_at: Instant,
    fetched_at_unix_ms: u64,
}

#[derive(Debug, Clone)]
pub struct CachedEchSnapshot {
    pub config: Vec<u8>,
    pub fetched_at_unix_ms: u64,
}

pub(crate) fn now_unix_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or(Duration::ZERO).as_millis() as u64
}

pub(crate) fn synthesize_instant_for_unix_ms(
    fetched_at_unix_ms: u64,
    now_instant: Instant,
    now_unix_ms: u64,
) -> Instant {
    if fetched_at_unix_ms >= now_unix_ms {
        return now_instant;
    }
    let age_ms = now_unix_ms - fetched_at_unix_ms;
    now_instant.checked_sub(Duration::from_millis(age_ms)).unwrap_or(now_instant)
}

pub struct CdnEchUpdater<P, F> {
    primary: P,
    fallback: F,
    cache: Mutex<Option<CachedEch>>,
    ttl: Duration,
}

impl<P: EchConfigSource, F: EchConfigSource> CdnEchUpdater<P, F> {
    pub fn new(primary: P, fallback: F, ttl: Duration) -> Self {
        Self { primary, fallback, cache: Mutex::new(None), ttl }
    }

    pub fn cached_config(&self) -> Option<Vec<u8>> {
        let guard = self.cache.lock().ok()?;
        guard.as_ref().filter(|cached| cached.fetched_at.elapsed() < self.ttl).map(|cached| cached.config.clone())
    }

    pub fn current_config(&self) -> Vec<u8> {
        let mut guard = self.cache.lock().expect("cdn_ech cache mutex poisoned");

        if let Some(ref cached) = *guard
            && cached.fetched_at.elapsed() < self.ttl
        {
            return cached.config.clone();
        }

        let fresh = self.primary.fetch().or_else(|primary_error| {
            tracing::debug!(
                error = %primary_error,
                "ECH primary source failed; trying fallback"
            );
            self.fallback.fetch()
        });

        match fresh {
            Ok(config) => {
                *guard = Some(CachedEch {
                    config: config.clone(),
                    fetched_at: Instant::now(),
                    fetched_at_unix_ms: now_unix_ms(),
                });
                config
            }
            Err(error) => {
                tracing::warn!(error = %error, "ECH fallback source also failed");
                if let Some(ref stale) = *guard { stale.config.clone() } else { CLOUDFLARE_ECH_CONFIG_LIST.to_vec() }
            }
        }
    }

    pub fn refresh(&self) -> Result<(), EchSourceError> {
        let fresh = self.primary.fetch().or_else(|_| self.fallback.fetch())?;
        let mut guard = self.cache.lock().expect("cdn_ech cache mutex poisoned");
        *guard = Some(CachedEch { config: fresh, fetched_at: Instant::now(), fetched_at_unix_ms: now_unix_ms() });
        Ok(())
    }

    pub fn seed_from_persisted(&self, config: Vec<u8>, fetched_at_unix_ms: u64) -> Result<(), EchSourceError> {
        validate_ech_config_list_bytes(&config)?;
        let now_instant = Instant::now();
        let fetched_at = synthesize_instant_for_unix_ms(fetched_at_unix_ms, now_instant, now_unix_ms());
        let mut guard = self.cache.lock().expect("cdn_ech cache mutex poisoned");
        *guard = Some(CachedEch { config, fetched_at, fetched_at_unix_ms });
        Ok(())
    }

    pub fn snapshot_for_persistence(&self) -> Option<CachedEchSnapshot> {
        let guard = self.cache.lock().expect("cdn_ech cache mutex poisoned");
        guard.as_ref().map(|cached| CachedEchSnapshot {
            config: cached.config.clone(),
            fetched_at_unix_ms: cached.fetched_at_unix_ms,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cdn_ech::source::BundledEchConfigSource;

    #[test]
    fn cached_config_treats_a_poisoned_cache_as_unavailable() {
        let updater = CdnEchUpdater::new(BundledEchConfigSource, BundledEchConfigSource, Duration::from_secs(86_400));
        let poisoned = std::panic::catch_unwind(|| {
            let _guard = updater.cache.lock().unwrap();
            panic!("test cache poisoning");
        });
        assert!(poisoned.is_err());
        assert_eq!(updater.cached_config(), None);
    }
}
