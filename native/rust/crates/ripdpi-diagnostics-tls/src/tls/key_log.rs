use std::fs::OpenOptions;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;

use rustls::KeyLog;

pub type TlsKeyLogCallback = Arc<dyn KeyLog>;

pub fn tls_key_log_callback_for_path(path: impl Into<PathBuf>) -> TlsKeyLogCallback {
    Arc::new(TlsKeyLogFile::new(path))
}

/// Appends SSLKEYLOGFILE lines to `path`.
///
/// Many instances can write the same file at once (one per probe or candidate worker). Each line
/// is therefore emitted with one `write` on an `O_APPEND` handle, which the kernel appends
/// atomically, instead of a lock that only orders the writers of one instance.
#[derive(Debug)]
pub struct TlsKeyLogFile {
    path: PathBuf,
}

impl TlsKeyLogFile {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }
}

impl KeyLog for TlsKeyLogFile {
    fn log(&self, label: &str, client_random: &[u8], secret: &[u8]) {
        if let Some(parent) = self.path.parent()
            && let Err(err) = std::fs::create_dir_all(parent)
        {
            tracing::warn!(error = %err, "failed to create TLS keylog parent directory");
            return;
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
    output.write_all(keylog_line(label, client_random, secret).as_bytes())
}

fn keylog_line(label: &str, client_random: &[u8], secret: &[u8]) -> String {
    let mut line = String::with_capacity(label.len() + 2 * (client_random.len() + secret.len()) + 3);
    line.push_str(label);
    line.push(' ');
    push_hex(&mut line, client_random);
    line.push(' ');
    push_hex(&mut line, secret);
    line.push('\n');
    line
}

fn push_hex(line: &mut String, bytes: &[u8]) {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    for byte in bytes {
        line.push(char::from(HEX[usize::from(byte >> 4)]));
        line.push(char::from(HEX[usize::from(byte & 0x0f)]));
    }
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

    /// Counts `write` calls, which are the syscalls on a real file.
    struct CountingWriter {
        writes: usize,
        bytes: Vec<u8>,
    }

    impl Write for CountingWriter {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.writes += 1;
            self.bytes.extend_from_slice(buf);
            Ok(buf.len())
        }

        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    #[test]
    fn keylog_line_is_written_with_one_write() {
        let mut output = CountingWriter { writes: 0, bytes: Vec::new() };

        write_keylog_line(&mut output, "CLIENT_RANDOM", &[0xab; 32], &[0x01; 48]).expect("write line");

        assert_eq!(output.writes, 1);
        let expected = format!("CLIENT_RANDOM {} {}\n", "ab".repeat(32), "01".repeat(48));
        assert_eq!(String::from_utf8(output.bytes).unwrap(), expected);
    }

    #[test]
    fn instances_on_one_path_never_interleave_lines() {
        let path = temp_keylog_path();
        std::thread::scope(|scope| {
            for worker in 0u8..4 {
                let path = path.clone();
                scope.spawn(move || {
                    let key_log = TlsKeyLogFile::new(path);
                    for _ in 0..200 {
                        key_log.log("CLIENT_HANDSHAKE_TRAFFIC_SECRET", &[worker; 32], &[worker; 32]);
                    }
                });
            }
        });

        let content = std::fs::read_to_string(&path).expect("keylog file");
        let _ = std::fs::remove_file(&path);
        let lines: Vec<&str> = content.lines().collect();
        assert_eq!(lines.len(), 800);
        for line in lines {
            let fields: Vec<&str> = line.split(' ').collect();
            assert_eq!(fields.len(), 3, "spliced line: {line}");
            assert_eq!(fields[1], fields[2], "spliced line: {line}");
            assert_eq!(fields[1].len(), 64, "spliced line: {line}");
        }
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
