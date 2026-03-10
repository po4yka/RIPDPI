use std::fs::{self, File, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::Serialize;
use serde_json::Value;

pub const SAMPLE_INTERVAL: Duration = Duration::from_secs(10);
pub const WARMUP_WINDOW: Duration = Duration::from_secs(120);
const DEFAULT_ARTIFACT_DIR: &str = "target/soak-artifacts";
const SOAK_LOCK_PATH: &str = "/tmp/ripdpi-native-soak.lock";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SoakProfile {
    Smoke,
    Full,
}

impl SoakProfile {
    #[must_use]
    pub fn from_env() -> Self {
        match std::env::var("RIPDPI_SOAK_PROFILE")
            .unwrap_or_else(|_| "smoke".to_string())
            .trim()
            .to_ascii_lowercase()
            .as_str()
        {
            "full" => Self::Full,
            _ => Self::Smoke,
        }
    }

    #[must_use]
    pub fn is_enabled() -> bool {
        std::env::var("RIPDPI_RUN_SOAK").ok().as_deref() == Some("1")
    }

    #[must_use]
    pub fn pick_count(self, smoke: usize, full: usize) -> usize {
        match self {
            Self::Smoke => smoke,
            Self::Full => full,
        }
    }

    #[must_use]
    pub fn pick_duration(self, smoke: Duration, full: Duration) -> Duration {
        match self {
            Self::Smoke => smoke,
            Self::Full => full,
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct GrowthThresholds {
    pub rss_growth_bytes: u64,
    pub fd_growth: usize,
    pub thread_growth: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SoakSample {
    pub scenario: String,
    pub elapsed_ms: u64,
    pub captured_at_ms: u64,
    pub rss_bytes: Option<u64>,
    pub fd_count: Option<usize>,
    pub thread_count: Option<usize>,
    pub extra: Value,
}

pub struct SoakSampler {
    stop: Arc<AtomicBool>,
    join: Option<JoinHandle<io::Result<Vec<SoakSample>>>>,
    output_path: PathBuf,
}

pub struct SoakLock {
    file: File,
}

impl Drop for SoakLock {
    fn drop(&mut self) {
        let fd = std::os::fd::AsRawFd::as_raw_fd(&self.file);
        let _ = unsafe { libc::flock(fd, libc::LOCK_UN) };
    }
}

pub fn acquire_global_lock() -> io::Result<SoakLock> {
    let file = OpenOptions::new().create(true).truncate(false).read(true).write(true).open(SOAK_LOCK_PATH)?;
    let fd = std::os::fd::AsRawFd::as_raw_fd(&file);
    let result = unsafe { libc::flock(fd, libc::LOCK_EX) };
    if result == 0 {
        Ok(SoakLock { file })
    } else {
        Err(io::Error::last_os_error())
    }
}

pub fn artifact_dir() -> io::Result<PathBuf> {
    let dir = std::env::var("RIPDPI_SOAK_ARTIFACT_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(DEFAULT_ARTIFACT_DIR));
    fs::create_dir_all(&dir)?;
    Ok(dir)
}

pub fn write_json_artifact<T>(name: &str, value: &T) -> io::Result<PathBuf>
where
    T: Serialize,
{
    let path = artifact_dir()?.join(name);
    let file = File::create(&path)?;
    serde_json::to_writer_pretty(file, value).map_err(io::Error::other)?;
    Ok(path)
}

impl SoakSampler {
    pub fn start<F>(scenario: &str, extra: F) -> io::Result<Self>
    where
        F: Fn() -> Value + Send + 'static,
    {
        let output_path = artifact_dir()?.join(format!("{scenario}.jsonl"));
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let scenario_name = scenario.to_string();
        let sampler_path = output_path.clone();
        let join = thread::spawn(move || run_sampler(sampler_path, scenario_name, stop_flag, extra));
        Ok(Self { stop, join: Some(join), output_path })
    }

    pub fn finish(mut self) -> io::Result<Vec<SoakSample>> {
        self.stop.store(true, Ordering::Relaxed);
        let join = self.join.take().expect("sampler join handle");
        join.join().map_err(|_| io::Error::other("sampler thread panicked"))?
    }

    #[must_use]
    pub fn output_path(&self) -> &Path {
        &self.output_path
    }
}

pub fn assert_growth(samples: &[SoakSample], warmup: Duration, thresholds: GrowthThresholds) -> Result<(), String> {
    let baseline = samples
        .iter()
        .find(|sample| Duration::from_millis(sample.elapsed_ms) >= warmup)
        .or_else(|| samples.first())
        .ok_or_else(|| "no soak samples recorded".to_string())?;
    let post_warmup =
        samples.iter().filter(|sample| Duration::from_millis(sample.elapsed_ms) >= warmup).collect::<Vec<_>>();
    let check_samples = if post_warmup.is_empty() { samples.iter().collect::<Vec<_>>() } else { post_warmup };

    assert_metric_growth(
        "rss_bytes",
        baseline.rss_bytes,
        check_samples.iter().filter_map(|sample| sample.rss_bytes).max(),
        thresholds.rss_growth_bytes,
    )?;
    assert_metric_growth_usize(
        "fd_count",
        baseline.fd_count,
        check_samples.iter().filter_map(|sample| sample.fd_count).max(),
        thresholds.fd_growth,
    )?;
    assert_metric_growth_usize(
        "thread_count",
        baseline.thread_count,
        check_samples.iter().filter_map(|sample| sample.thread_count).max(),
        thresholds.thread_growth,
    )?;
    Ok(())
}

fn run_sampler<F>(
    output_path: PathBuf,
    scenario: String,
    stop: Arc<AtomicBool>,
    extra: F,
) -> io::Result<Vec<SoakSample>>
where
    F: Fn() -> Value + Send + 'static,
{
    let file = File::create(&output_path)?;
    let mut writer = BufWriter::new(file);
    let started = Instant::now();
    let mut samples = Vec::new();

    loop {
        let sample = SoakSample {
            scenario: scenario.clone(),
            elapsed_ms: started.elapsed().as_millis() as u64,
            captured_at_ms: now_ms(),
            rss_bytes: sample_rss_bytes(),
            fd_count: sample_fd_count(),
            thread_count: sample_thread_count(),
            extra: extra(),
        };
        serde_json::to_writer(&mut writer, &sample).map_err(io::Error::other)?;
        writer.write_all(b"\n")?;
        writer.flush()?;
        samples.push(sample);

        if stop.load(Ordering::Relaxed) {
            break;
        }
        thread::sleep(SAMPLE_INTERVAL);
    }

    Ok(samples)
}

fn assert_metric_growth(label: &str, baseline: Option<u64>, peak: Option<u64>, limit: u64) -> Result<(), String> {
    if let (Some(baseline), Some(peak)) = (baseline, peak) {
        let growth = peak.saturating_sub(baseline);
        if growth > limit {
            return Err(format!("{label} growth exceeded threshold: {growth} > {limit}"));
        }
    }
    Ok(())
}

fn assert_metric_growth_usize(
    label: &str,
    baseline: Option<usize>,
    peak: Option<usize>,
    limit: usize,
) -> Result<(), String> {
    if let (Some(baseline), Some(peak)) = (baseline, peak) {
        let growth = peak.saturating_sub(baseline);
        if growth > limit {
            return Err(format!("{label} growth exceeded threshold: {growth} > {limit}"));
        }
    }
    Ok(())
}

fn sample_rss_bytes() -> Option<u64> {
    sample_rss_linux().or_else(sample_rss_ps)
}

fn sample_rss_linux() -> Option<u64> {
    let contents = fs::read_to_string("/proc/self/status").ok()?;
    let line = contents.lines().find(|line| line.starts_with("VmRSS:"))?;
    let kib = line.split_whitespace().nth(1).and_then(|value| value.parse::<u64>().ok())?;
    Some(kib * 1024)
}

fn sample_rss_ps() -> Option<u64> {
    let pid = std::process::id().to_string();
    let output = Command::new("ps").args(["-o", "rss=", "-p", &pid]).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let kib = String::from_utf8_lossy(&output.stdout).trim().parse::<u64>().ok()?;
    Some(kib * 1024)
}

fn sample_fd_count() -> Option<usize> {
    fs::read_dir("/proc/self/fd").ok().or_else(|| fs::read_dir("/dev/fd").ok()).map(std::iter::Iterator::count)
}

fn sample_thread_count() -> Option<usize> {
    fs::read_dir("/proc/self/task").ok().map(std::iter::Iterator::count).or_else(sample_thread_count_ps)
}

fn sample_thread_count_ps() -> Option<usize> {
    let pid = std::process::id().to_string();
    let output = Command::new("ps").args(["-M", "-p", &pid]).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let lines = String::from_utf8_lossy(&output.stdout).lines().count();
    lines.checked_sub(1)
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_else(|_| Duration::from_secs(0)).as_millis() as u64
}

pub fn extract_last_error(extra: &Value) -> Option<String> {
    extra.get("lastError").and_then(Value::as_str).map(ToOwned::to_owned)
}

pub fn monotonic_u64_samples(samples: &[SoakSample], field: &str) -> bool {
    let mut previous = None;
    for value in samples.iter().filter_map(|sample| sample.extra.get(field)).filter_map(Value::as_u64) {
        if let Some(previous) = previous {
            if value < previous {
                return false;
            }
        }
        previous = Some(value);
    }
    true
}

pub fn latest_extra_field<'a>(samples: &'a [SoakSample], field: &str) -> Option<&'a Value> {
    samples.last()?.extra.get(field)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn profile_env_defaults_to_smoke() {
        std::env::remove_var("RIPDPI_SOAK_PROFILE");
        assert_eq!(SoakProfile::from_env(), SoakProfile::Smoke);
    }

    #[test]
    fn growth_assertions_allow_missing_metrics() {
        let samples = vec![SoakSample {
            scenario: "test".to_string(),
            elapsed_ms: 0,
            captured_at_ms: 0,
            rss_bytes: None,
            fd_count: None,
            thread_count: None,
            extra: json!({}),
        }];
        assert!(assert_growth(
            &samples,
            Duration::from_secs(1),
            GrowthThresholds { rss_growth_bytes: 1, fd_growth: 1, thread_growth: 1 }
        )
        .is_ok());
    }
}
