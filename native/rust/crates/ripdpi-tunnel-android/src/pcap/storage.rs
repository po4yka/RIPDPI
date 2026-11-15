//! Private capture storage. Storage operations never acquire the session registry.

use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::Path;
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, SystemTime};

use super::PcapCaptureMetadata;

pub(super) const GLOBAL_MAX_CAPTURE_BYTES: u64 = 64 * 1024 * 1024;
pub(super) const GLOBAL_MAX_CAPTURE_FILES: u32 = 3;
const CAPTURE_MAX_AGE: Duration = Duration::from_secs(24 * 60 * 60);

static STORAGE: Mutex<()> = Mutex::new(());

pub(super) fn lock_storage() -> MutexGuard<'static, ()> {
    STORAGE.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// List finalized regular captures. A missing directory is an empty store.
pub fn list_captures(dir: &Path) -> io::Result<Vec<PcapCaptureMetadata>> {
    let _guard = lock_storage();
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(error),
    };
    let mut files = Vec::new();
    for entry in entries {
        let entry = entry?;
        let path = entry.path();
        if !is_completed_capture(&path) || !entry.file_type()?.is_file() {
            continue;
        }
        files.push(PcapCaptureMetadata {
            path: path.display().to_string(),
            byte_size: entry.metadata()?.len(),
            packet_count: 0, // The packet reader computes this when the file is opened.
            started_at_ms: 0,
            ended_at_ms: 0,
            drops: read_drops(&path)?,
        });
    }
    Ok(files)
}

pub(crate) fn cleanup_captures(dir: &Path, active_set_ids: &HashSet<u64>) -> io::Result<()> {
    let _guard = lock_storage();
    prune(dir, active_set_ids, GLOBAL_MAX_CAPTURE_FILES, GLOBAL_MAX_CAPTURE_BYTES)
}

pub(super) fn reserve_segment(dir: &Path, set_id: u64, max_file_bytes: u64) -> io::Result<()> {
    let _guard = lock_storage();
    prune(dir, &HashSet::from([set_id]), GLOBAL_MAX_CAPTURE_FILES - 1, GLOBAL_MAX_CAPTURE_BYTES - max_file_bytes)
}

fn prune(dir: &Path, active_set_ids: &HashSet<u64>, max_files: u32, max_bytes: u64) -> io::Result<()> {
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    };
    let now = SystemTime::now();
    let mut retained_count = 0;
    let mut retained_bytes = 0u64;
    let mut inactive = Vec::new();
    for entry in entries {
        let entry = entry?;
        let path = entry.path();
        let active = capture_set_id(&path).is_some_and(|id| active_set_ids.contains(&id));
        let metadata = match entry.metadata() {
            Ok(metadata) if metadata.is_file() => metadata,
            Ok(_) => continue,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error),
        };
        if has_suffix(&path, ".pcap.drops.tmp")
            || (has_suffix(&path, ".pcap.drops") && !path.with_extension("").try_exists()?)
        {
            remove_if_present(&path)?;
        } else if has_suffix(&path, ".pcap.active") {
            if active {
                retained_count += 1;
                retained_bytes = retained_bytes.saturating_add(metadata.len());
            } else {
                remove_if_present(&path)?;
            }
        } else if is_completed_capture(&path) {
            let modified = metadata.modified()?;
            if !active && now.duration_since(modified).is_ok_and(|age| age >= CAPTURE_MAX_AGE) {
                remove_capture(&path)?;
                continue;
            }
            retained_count += 1;
            retained_bytes = retained_bytes.saturating_add(metadata.len());
            if !active {
                inactive.push((modified, path, metadata.len()));
            }
        }
    }

    inactive.sort_unstable();
    for (_, path, bytes) in inactive {
        if retained_count <= max_files && retained_bytes <= max_bytes {
            break;
        }
        remove_capture(&path)?;
        retained_count -= 1;
        retained_bytes = retained_bytes.saturating_sub(bytes);
    }

    if retained_count > max_files || retained_bytes > max_bytes {
        return Err(io::Error::other("Active capture files occupy the reserved capture budget"));
    }
    Ok(())
}

fn remove_capture(path: &Path) -> io::Result<()> {
    remove_if_present(path)?;
    remove_if_present(&path.with_extension("pcap.drops"))
}

fn remove_if_present(path: &Path) -> io::Result<()> {
    match fs::remove_file(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        result => result,
    }
}

fn has_suffix(path: &Path, suffix: &str) -> bool {
    path.file_name().and_then(|name| name.to_str()).is_some_and(|name| name.ends_with(suffix))
}

fn is_completed_capture(path: &Path) -> bool {
    path.extension().and_then(|extension| extension.to_str()) == Some("pcap")
}

fn capture_set_id(path: &Path) -> Option<u64> {
    path.file_name()
        .and_then(|name| name.to_str())
        .and_then(|name| name.split_once('-').map(|(set_id, _)| set_id))
        .and_then(|set_id| u64::from_str_radix(set_id, 16).ok())
}

pub(super) fn persist_drops(capture: &Path, drops: u64) -> io::Result<()> {
    let _guard = lock_storage();
    let path = capture.with_extension("pcap.drops");
    let temporary = capture.with_extension("pcap.drops.tmp");
    let mut file = File::create_new(&temporary)?;
    let result = file.write_all(&drops.to_le_bytes()).and_then(|()| file.sync_all());
    drop(file);
    let result = result.and_then(|()| fs::rename(&temporary, path));
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result
}

fn read_drops(capture: &Path) -> io::Result<u64> {
    let mut bytes = [0; 8];
    match File::open(capture.with_extension("pcap.drops")) {
        Ok(mut file) => {
            file.read_exact(&mut bytes)?;
            Ok(u64::from_le_bytes(bytes))
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(0),
        Err(error) => Err(error),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn write_file(dir: &Path, name: &str, modified: SystemTime, len: u64) -> PathBuf {
        let path = dir.join(name);
        let file = File::create(&path).unwrap();
        file.set_len(len).unwrap();
        file.set_modified(modified).unwrap();
        path
    }

    #[test]
    fn reserves_space_before_a_new_segment_opens() {
        let dir = tempfile::tempdir().unwrap();
        let dir = dir.path();
        let now = SystemTime::now();
        let active = write_file(dir, "0000000000000010-1-00.pcap", now, 28 * 1024 * 1024);
        let inactive = write_file(dir, "0000000000000011-1-00.pcap", now, 24 * 1024 * 1024);
        let recent = write_file(dir, "0000000000000012-1-00.pcap", now + Duration::from_secs(1), 12 * 1024 * 1024);

        reserve_segment(dir, 0x10, 16 * 1024 * 1024).unwrap();

        assert!(active.exists());
        assert!(!inactive.exists());
        assert!(recent.exists());
    }

    #[test]
    fn prunes_expired_and_orphan_files_without_touching_a_live_set() {
        let dir = tempfile::tempdir().unwrap();
        let dir = dir.path();
        let expired = SystemTime::UNIX_EPOCH;
        let active = write_file(dir, "0000000000000010-1-00.pcap", expired, 48);
        let marker = write_file(dir, "0000000000000010-2-01.pcap.active", expired, 24);
        let old = write_file(dir, "0000000000000011-1-00.pcap", expired, 24);
        persist_drops(&old, 2).unwrap();
        let old_marker = write_file(dir, "0000000000000011-2-01.pcap.active", expired, 24);
        let orphan = write_file(dir, "0000000000000012-1-00.pcap.drops", expired, 8);

        cleanup_captures(dir, &HashSet::from([0x10])).unwrap();

        assert!(active.exists());
        assert!(marker.exists());
        assert!(!old.exists());
        assert!(!old.with_extension("pcap.drops").exists());
        assert!(!old_marker.exists());
        assert!(!orphan.exists());
    }
}
