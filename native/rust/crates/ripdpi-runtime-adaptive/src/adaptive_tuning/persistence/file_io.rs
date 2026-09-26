use std::fs;
use std::io::{self, Read, Write};
use std::path::Path;

use super::schema::StoredAdaptivePlannerStore;
use crate::adaptive_tuning::key::now_millis;

const MAX_ADAPTIVE_STORE_BYTES: u64 = 8 * 1024 * 1024;

pub(super) fn read_store(path: &Path) -> Result<StoredAdaptivePlannerStore, io::Error> {
    let mut payload = Vec::new();
    fs::File::open(path)?.take(MAX_ADAPTIVE_STORE_BYTES + 1).read_to_end(&mut payload)?;
    if payload.len() as u64 > MAX_ADAPTIVE_STORE_BYTES {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "adaptive tuning store exceeds size limit"));
    }
    serde_json::from_slice::<StoredAdaptivePlannerStore>(&payload)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, format!("invalid adaptive tuning store: {err}")))
}

pub(super) fn write_store(path: &Path, store: &StoredAdaptivePlannerStore) -> io::Result<()> {
    let payload = serde_json::to_vec_pretty(store)
        .map_err(|err| io::Error::other(format!("failed to serialize adaptive tuning store: {err}")))?;
    if payload.len() as u64 > MAX_ADAPTIVE_STORE_BYTES {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "adaptive tuning store exceeds size limit"));
    }
    atomic_write(path, &payload)
}

fn atomic_write(path: &Path, payload: &[u8]) -> io::Result<()> {
    let parent = path.parent().filter(|parent| !parent.as_os_str().is_empty()).unwrap_or(Path::new("."));
    fs::create_dir_all(parent)?;
    let tmp_name = format!(
        ".{}.tmp-{}-{}",
        path.file_name().and_then(|value| value.to_str()).unwrap_or("adaptive-tuning"),
        std::process::id(),
        next_temp_file_nonce()
    );
    let tmp_path = parent.join(tmp_name);
    let result = (|| {
        let mut file = fs::File::create(&tmp_path)?;
        file.write_all(payload)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&tmp_path, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&tmp_path);
    } else {
        let _ = fs::File::open(parent).and_then(|dir| dir.sync_all());
    }
    result
}

fn next_temp_file_nonce() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEMP_FILE_NONCE: AtomicU64 = AtomicU64::new(0);
    let timestamp = now_millis() << 16;
    let sequence = TEMP_FILE_NONCE.fetch_add(1, Ordering::Relaxed) & 0xFFFF;
    timestamp | sequence
}

#[cfg(test)]
mod tests {
    use super::{MAX_ADAPTIVE_STORE_BYTES, atomic_write, read_store};
    use std::fs;

    #[test]
    fn failed_replacement_cleans_temporary_file() {
        let temp = tempfile::tempdir().expect("temp directory");
        let destination = temp.path().join("store.json");
        fs::create_dir(&destination).expect("destination directory");

        assert!(atomic_write(&destination, b"new state").is_err());
        assert!(destination.is_dir());
        assert_eq!(fs::read_dir(temp.path()).expect("list parent").count(), 1);
    }

    #[test]
    fn oversized_store_is_rejected_before_json_parsing() {
        let temp = tempfile::tempdir().expect("temp directory");
        let path = temp.path().join("store.json");
        fs::File::create(&path).expect("create store").set_len(MAX_ADAPTIVE_STORE_BYTES + 1).expect("size store");
        assert_eq!(read_store(&path).expect_err("oversized store must fail").kind(), std::io::ErrorKind::InvalidData);
    }
}
