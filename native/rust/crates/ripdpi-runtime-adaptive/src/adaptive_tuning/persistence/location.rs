use std::path::{Path, PathBuf};

use ring::digest;

const ADAPTIVE_TUNING_STORE_FILE_NAME: &str = "adaptive-tuning-v1.json";

pub(in crate::adaptive_tuning) fn adaptive_store_path(config: &ripdpi_config::RuntimeConfig) -> Option<PathBuf> {
    let store_path = config.host_autolearn.store_path.as_deref().map(str::trim).filter(|value| !value.is_empty())?;
    Path::new(store_path).parent().map(|parent| parent.join(ADAPTIVE_TUNING_STORE_FILE_NAME))
}

pub(super) fn adaptive_store_fingerprint(config: &ripdpi_config::RuntimeConfig) -> String {
    let mut input = format!("adaptive-tuning-v1|{}", config.groups.len());
    input.push('|');
    input.push_str(&format!("{:?}", config.groups));
    let digest = digest::digest(&digest::SHA256, input.as_bytes());
    digest.as_ref().iter().fold(String::new(), |mut out, byte| {
        use std::fmt::Write;
        let _ = write!(out, "{byte:02x}");
        out
    })
}
