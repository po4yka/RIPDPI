use std::fs;
use std::io::{self, Read, Write};
use std::path::Path;

use super::schema::StoredAdaptivePlannerStore;

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
    let mut file = tempfile::NamedTempFile::new_in(parent)?;
    file.write_all(payload)?;
    match fs::metadata(path) {
        Ok(metadata) => file.as_file().set_permissions(metadata.permissions())?,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(error),
    }
    file.as_file().sync_all()?;
    file.persist(path).map_err(|error| error.error)?;
    // Best-effort directory sync keeps the existing durability contract.
    let _ = fs::File::open(parent).and_then(|dir| dir.sync_all());
    Ok(())
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
    #[cfg(unix)]
    #[test]
    fn replacement_is_private_and_preserves_existing_permissions() {
        use std::os::unix::fs::PermissionsExt;
        let temp = tempfile::tempdir().unwrap();
        let destination = temp.path().join("store.json");
        atomic_write(&destination, b"first").unwrap();
        assert_eq!(fs::metadata(&destination).unwrap().permissions().mode() & 0o777, 0o600);
        fs::set_permissions(&destination, fs::Permissions::from_mode(0o640)).unwrap();
        atomic_write(&destination, b"replacement").unwrap();
        assert_eq!(fs::read(&destination).unwrap(), b"replacement");
        assert_eq!(fs::metadata(&destination).unwrap().permissions().mode() & 0o777, 0o640);
    }
}
