use std::fmt;
use std::fs::OpenOptions;
use std::io;
use std::process::Command;
use std::sync::Mutex;
use std::time::SystemTime;

use tracing::Level;
use tracing_subscriber::fmt::format::Writer;
use tracing_subscriber::fmt::time::FormatTime;
use tracing_subscriber::fmt::writer::MakeWriterExt;
use tracing_subscriber::EnvFilter;

/// Log output destination.
#[derive(Debug, Clone)]
pub enum LogOutput {
    Stdout,
    Stderr,
    /// Append-or-create file at the given path.
    File(String),
}

/// Timestamp formatter matching the C format: `[YYYY-MM-DD HH:MM:SS]`.
struct HevTimer;

impl FormatTime for HevTimer {
    fn format_time(&self, w: &mut Writer<'_>) -> fmt::Result {
        let secs = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let (y, mo, d, h, mi, s) = epoch_to_datetime(secs);
        write!(w, "[{y:04}-{mo:02}-{d:02} {h:02}:{mi:02}:{s:02}]")
    }
}

/// Convert Unix epoch seconds to `(year, month, day, hour, minute, second)`.
///
/// Uses Howard Hinnant's civil-from-days algorithm (public domain).
fn epoch_to_datetime(secs: u64) -> (i32, u32, u32, u32, u32, u32) {
    let z = (secs / 86400) as i64 + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let mo = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if mo <= 2 { y + 1 } else { y };

    let tod = secs % 86_400;
    let h = tod / 3_600;
    let mi = (tod % 3_600) / 60;
    let s = tod % 60;

    (
        year as i32,
        mo as u32,
        d as u32,
        h as u32,
        mi as u32,
        s as u32,
    )
}

/// Initialise the global tracing subscriber.
///
/// Safe to call multiple times; subsequent calls are no-ops.
/// Returns `Err` only when `output` is `File` and the file cannot be opened.
pub fn init(level: Level, output: LogOutput) -> io::Result<()> {
    let filter = EnvFilter::new(level.to_string());

    let result = match output {
        LogOutput::Stdout => tracing_subscriber::fmt()
            .with_env_filter(filter)
            .with_timer(HevTimer)
            .with_ansi(false)
            .with_writer(io::stdout)
            .try_init(),

        LogOutput::Stderr => tracing_subscriber::fmt()
            .with_env_filter(filter)
            .with_timer(HevTimer)
            .with_ansi(false)
            .with_writer(io::stderr)
            .try_init(),

        LogOutput::File(path) => {
            let file = OpenOptions::new().append(true).create(true).open(&path)?;
            let writer = Mutex::new(file);
            tracing_subscriber::fmt()
                .with_env_filter(filter)
                .with_timer(HevTimer)
                .with_ansi(false)
                .with_writer(writer.with_max_level(level))
                .try_init()
        }
    };

    // Ignore AlreadyInitialized — idempotent is fine.
    let _ = result;
    Ok(())
}

/// Run `script_path` as a child process with arguments `[tun_name, tun_index]`.
///
/// When `wait` is `true` the function blocks until the child exits.
/// When `wait` is `false` the child runs in the background.
///
/// Spawn failures are logged and otherwise swallowed, matching C behaviour.
pub fn exec_run(script_path: &str, tun_name: &str, tun_index: &str, wait: bool) {
    let mut child = match Command::new(script_path)
        .arg(tun_name)
        .arg(tun_index)
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            tracing::error!("exec {} {} {}: {}", script_path, tun_name, tun_index, e);
            return;
        }
    };

    if wait {
        if let Err(e) = child.wait() {
            tracing::error!("exec wait {}: {}", script_path, e);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Write;

    // -----------------------------------------------------------------------
    // epoch_to_datetime
    // -----------------------------------------------------------------------

    #[test]
    fn epoch_zero_is_unix_epoch() {
        let (y, mo, d, h, mi, s) = epoch_to_datetime(0);
        assert_eq!((y, mo, d, h, mi, s), (1970, 1, 1, 0, 0, 0));
    }

    #[test]
    fn known_timestamp() {
        // 2024-03-10 14:30:45 UTC  =  1710081045
        // Verify: 1710081045 % 86400 = 52245 s = 14h 30m 45s
        let (y, mo, d, h, mi, s) = epoch_to_datetime(1_710_081_045);
        assert_eq!((y, mo, d, h, mi, s), (2024, 3, 10, 14, 30, 45));
    }

    #[test]
    fn leap_day_2000() {
        // 2000-02-29 00:00:00 UTC  =  951782400
        let (y, mo, d, h, mi, s) = epoch_to_datetime(951_782_400);
        assert_eq!((y, mo, d, h, mi, s), (2000, 2, 29, 0, 0, 0));
    }

    #[test]
    fn end_of_day() {
        // 1970-01-01 23:59:59 UTC  =  86399
        let (y, mo, d, h, mi, s) = epoch_to_datetime(86_399);
        assert_eq!((y, mo, d, h, mi, s), (1970, 1, 1, 23, 59, 59));
    }

    // -----------------------------------------------------------------------
    // init — smoke tests
    // -----------------------------------------------------------------------

    #[test]
    fn init_stdout_does_not_panic() {
        init(Level::WARN, LogOutput::Stdout).unwrap();
    }

    #[test]
    fn init_stderr_does_not_panic() {
        init(Level::ERROR, LogOutput::Stderr).unwrap();
    }

    #[test]
    fn init_file_creates_file() {
        let path = std::env::temp_dir().join("hs5t_logger_test_init.log");
        let _ = fs::remove_file(&path);
        init(
            Level::DEBUG,
            LogOutput::File(path.to_str().unwrap().to_owned()),
        )
        .unwrap();
        assert!(path.exists(), "log file must be created");
        let _ = fs::remove_file(&path);
    }

    // -----------------------------------------------------------------------
    // exec_run
    // -----------------------------------------------------------------------

    #[test]
    fn exec_run_passes_args_to_script() {
        let dir = std::env::temp_dir();
        let script = dir.join("hs5t_exec_test.sh");
        let out_file = dir.join("hs5t_exec_args.txt");

        {
            let mut f = fs::File::create(&script).unwrap();
            writeln!(f, "#!/bin/sh").unwrap();
            writeln!(f, "echo \"$1 $2\" > {}", out_file.display()).unwrap();
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&script, fs::Permissions::from_mode(0o755)).unwrap();
        }

        exec_run(script.to_str().unwrap(), "tun0", "5", true);

        let content = fs::read_to_string(&out_file).unwrap_or_default();
        assert_eq!(content.trim(), "tun0 5");

        let _ = fs::remove_file(&script);
        let _ = fs::remove_file(&out_file);
    }

    #[test]
    fn exec_run_nonexistent_script_does_not_panic() {
        exec_run("/nonexistent/path/script.sh", "tun0", "0", false);
    }

    #[test]
    fn exec_run_no_wait_returns_immediately() {
        let dir = std::env::temp_dir();
        let script = dir.join("hs5t_exec_nowait.sh");
        {
            let mut f = fs::File::create(&script).unwrap();
            writeln!(f, "#!/bin/sh\nsleep 5").unwrap();
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&script, fs::Permissions::from_mode(0o755)).unwrap();
        }

        let start = std::time::Instant::now();
        exec_run(script.to_str().unwrap(), "tun0", "0", false);
        assert!(start.elapsed().as_millis() < 1_000);
        // Give the child a moment to open the script before we remove it.
        std::thread::sleep(std::time::Duration::from_millis(50));
        let _ = fs::remove_file(&script);
    }
}
