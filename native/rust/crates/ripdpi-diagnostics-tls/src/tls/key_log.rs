use std::fs::OpenOptions;
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use rustls::KeyLog;

pub type TlsKeyLogCallback = Arc<dyn KeyLog>;

pub fn tls_key_log_callback_for_path(path: impl Into<PathBuf>) -> TlsKeyLogCallback {
    Arc::new(TlsKeyLogFile::new(path))
}

#[derive(Debug)]
pub struct TlsKeyLogFile {
    path: PathBuf,
    lock: Mutex<()>,
}

impl TlsKeyLogFile {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into(), lock: Mutex::new(()) }
    }
}

impl KeyLog for TlsKeyLogFile {
    fn log(&self, label: &str, client_random: &[u8], secret: &[u8]) {
        let Ok(_guard) = self.lock.lock() else {
            tracing::warn!("TLS keylog mutex poisoned");
            return;
        };
        if let Some(parent) = self.path.parent() {
            if let Err(err) = std::fs::create_dir_all(parent) {
                tracing::warn!(error = %err, "failed to create TLS keylog parent directory");
                return;
            }
        }
        match OpenOptions::new().create(true).append(true).open(&self.path) {
            Ok(mut file) => {
                if let Err(err) = write_keylog_line(&mut file, label, client_random, secret) {
                    tracing::warn!(error = %err, "failed to write TLS keylog line");
                }
            }
            Err(err) => {
                tracing::warn!(error = %err, "failed to open TLS keylog file");
            }
        }
    }

    fn will_log(&self, _label: &str) -> bool {
        true
    }
}

fn write_keylog_line(output: &mut dyn Write, label: &str, client_random: &[u8], secret: &[u8]) -> std::io::Result<()> {
    write!(output, "{label} ")?;
    write_hex(output, client_random)?;
    write!(output, " ")?;
    write_hex(output, secret)?;
    writeln!(output)?;
    Ok(())
}

fn write_hex(output: &mut dyn Write, bytes: &[u8]) -> std::io::Result<()> {
    for byte in bytes {
        write!(output, "{byte:02x}")?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn tls_key_log_file_writes_sslkeylogfile_line() {
        let path = temp_keylog_path();
        let key_log = TlsKeyLogFile::new(path.clone());

        key_log.log("CLIENT_RANDOM", &[0xab, 0xcd], &[0x01, 0x23]);

        let content = std::fs::read_to_string(path).expect("keylog file");
        assert_eq!(content, "CLIENT_RANDOM abcd 0123\n");
    }

    #[test]
    fn tls_key_log_callback_for_path_enables_logging() {
        let key_log = tls_key_log_callback_for_path(temp_keylog_path());

        assert!(key_log.will_log("CLIENT_HANDSHAKE_TRAFFIC_SECRET"));
    }

    fn temp_keylog_path() -> PathBuf {
        let nanos = SystemTime::now().duration_since(UNIX_EPOCH).expect("system time").as_nanos();
        std::env::temp_dir().join(format!("ripdpi-keylog-{nanos}.keys"))
    }
}
