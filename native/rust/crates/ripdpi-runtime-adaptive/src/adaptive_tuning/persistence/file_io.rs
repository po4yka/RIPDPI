use std::fs;
use std::io;
use std::path::Path;

use super::schema::StoredAdaptivePlannerStore;
use crate::adaptive_tuning::key::now_millis;

pub(super) fn read_store(path: &Path) -> Result<StoredAdaptivePlannerStore, io::Error> {
    let payload = fs::read(path)?;
    serde_json::from_slice::<StoredAdaptivePlannerStore>(&payload)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, format!("invalid adaptive tuning store: {err}")))
}

pub(super) fn write_store(path: &Path, store: &StoredAdaptivePlannerStore) -> io::Result<()> {
    let payload = serde_json::to_vec_pretty(store)
        .map_err(|err| io::Error::other(format!("failed to serialize adaptive tuning store: {err}")))?;
    atomic_write(path, &payload)
}

fn atomic_write(path: &Path, payload: &[u8]) -> io::Result<()> {
    let Some(parent) = path.parent() else {
        return fs::write(path, payload);
    };
    if parent.as_os_str().is_empty() {
        return fs::write(path, payload);
    }
    fs::create_dir_all(parent)?;
    let tmp_name = format!(
        ".{}.tmp-{}-{}",
        path.file_name().and_then(|value| value.to_str()).unwrap_or("adaptive-tuning"),
        std::process::id(),
        next_temp_file_nonce()
    );
    let tmp_path = parent.join(tmp_name);
    fs::write(&tmp_path, payload)?;
    if path.exists() {
        let _ = fs::remove_file(path);
    }
    fs::rename(tmp_path, path)
}

fn next_temp_file_nonce() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEMP_FILE_NONCE: AtomicU64 = AtomicU64::new(0);
    let timestamp = now_millis() << 16;
    let sequence = TEMP_FILE_NONCE.fetch_add(1, Ordering::Relaxed) & 0xFFFF;
    timestamp | sequence
}
