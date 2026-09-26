use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};

use serde::{Deserialize, Serialize};

use crate::ApplyError;
use crate::manifest::SharedPriorsManifest;

const MARKER_VERSION: u32 = 1;
const MAX_MARKER_BYTES: u64 = 512;
static TEMP_NONCE: AtomicU64 = AtomicU64::new(0);

#[derive(Serialize, Deserialize)]
struct ReleaseMarker {
    version: u32,
    issued_at_unix: i64,
    priors_sha256: String,
}

pub(super) fn check_and_store(path: &Path, manifest: &SharedPriorsManifest) -> Result<(), ApplyError> {
    if !path.is_absolute() {
        return Err(ApplyError::Watermark(io::Error::new(
            io::ErrorKind::InvalidInput,
            "shared-priors marker path must be absolute",
        )));
    }
    let next = ReleaseMarker {
        version: MARKER_VERSION,
        issued_at_unix: manifest.issued_at_unix,
        priors_sha256: manifest.priors_sha256_hex.to_ascii_lowercase(),
    };
    if let Some(previous) = read(path).map_err(ApplyError::Watermark)? {
        if next.issued_at_unix < previous.issued_at_unix {
            return Err(ApplyError::Rollback { accepted: previous.issued_at_unix, incoming: next.issued_at_unix });
        }
        if next.issued_at_unix == previous.issued_at_unix {
            return if next.priors_sha256 == previous.priors_sha256 {
                Ok(())
            } else {
                Err(ApplyError::ConflictingRelease(next.issued_at_unix))
            };
        }
    }
    write(path, &next).map_err(ApplyError::Watermark)
}

fn read(path: &Path) -> io::Result<Option<ReleaseMarker>> {
    let file = match File::open(path) {
        Ok(file) => file,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(err) => return Err(err),
    };
    let mut bytes = Vec::new();
    file.take(MAX_MARKER_BYTES + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > MAX_MARKER_BYTES {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "shared-priors marker too large"));
    }
    let marker: ReleaseMarker =
        serde_json::from_slice(&bytes).map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
    if marker.version != MARKER_VERSION
        || marker.priors_sha256.len() != 64
        || !marker.priors_sha256.bytes().all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid shared-priors marker"));
    }
    Ok(Some(marker))
}

fn write(path: &Path, marker: &ReleaseMarker) -> io::Result<()> {
    let parent = path.parent().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "marker has no parent"))?;
    fs::create_dir_all(parent)?;
    let nonce = TEMP_NONCE.fetch_add(1, Ordering::Relaxed);
    let temp = parent.join(format!(".shared-priors-marker-{}-{nonce}.tmp", std::process::id()));
    let result = (|| {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temp)?;
        serde_json::to_writer(&mut file, marker).map_err(io::Error::other)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temp, path)?;
        File::open(parent)?.sync_all()
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result
}
