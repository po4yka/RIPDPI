use std::time::Duration;

use crate::cdn_ech::cache::CdnEchUpdater;
use crate::cdn_ech::source::{BundledEchConfigSource, RemoteEchConfigSource};

/// Default TTL for the singleton updater: 24 hours, matching Cloudflare's
/// observed key-rotation cadence.
const PRODUCTION_TTL: Duration = Duration::from_secs(24 * 60 * 60);

static PRODUCTION_UPDATER: std::sync::OnceLock<CdnEchUpdater<RemoteEchConfigSource, BundledEchConfigSource>> =
    std::sync::OnceLock::new();

/// Borrow the process-wide updater, constructing it on first call.
pub fn production_updater() -> &'static CdnEchUpdater<RemoteEchConfigSource, BundledEchConfigSource> {
    PRODUCTION_UPDATER
        .get_or_init(|| CdnEchUpdater::new(RemoteEchConfigSource::new(), BundledEchConfigSource, PRODUCTION_TTL))
}
