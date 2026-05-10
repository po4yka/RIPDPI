#![forbid(unsafe_code)]

use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use serde::Deserialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum StrategyConfigError {
    #[error("failed to parse strategy YAML: {0}")]
    Parse(#[from] serde_yaml_ng::Error),
    #[error("failed to read host list {path}: {source}")]
    HostListRead { path: PathBuf, source: std::io::Error },
    #[error("strategy config file {path} could not be read: {source}")]
    ConfigRead { path: PathBuf, source: std::io::Error },
    #[error("strategy config metadata for {path} could not be read: {source}")]
    Metadata { path: PathBuf, source: std::io::Error },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LoadedStrategyConfig {
    pub version: u32,
    pub strategies: Vec<LoadedStrategy>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LoadedStrategy {
    pub id: String,
    pub matcher: StrategyMatch,
    pub steps: Vec<StrategyStep>,
    pub on_fail: OnFail,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct StrategyMatch {
    pub proto: Vec<ProtocolName>,
    pub port: Vec<u16>,
    pub hosts: Vec<String>,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum OnFail {
    #[default]
    NextStrategy,
    FallbackPlain,
    Drop,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ProtocolName {
    Tls,
    Http,
    Quic,
    Wireguard,
    Dtls,
    Dht,
    Mtproto,
    Stun,
    Any,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct StrategyStep {
    #[serde(rename = "type")]
    pub kind: StepType,
    #[serde(default)]
    pub pos: Option<String>,
    #[serde(default)]
    pub disorder: bool,
    #[serde(default)]
    pub ttl: Option<u8>,
    #[serde(default)]
    pub sni_mode: Option<String>,
    #[serde(default)]
    pub delta: Option<i32>,
    #[serde(default)]
    pub value: Option<u32>,
    #[serde(default)]
    pub size: Option<u32>,
    #[serde(default)]
    pub scale: Option<u8>,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum StepType {
    Split,
    Disorder,
    Fake,
    Oob,
    FakeRst,
    SeqOverlap,
    IpFrag,
    MultiDisorder,
    Udplen,
    HttpDomcase,
    HttpHostcase,
    HttpMethodeol,
    HttpUnixeol,
    Wsize,
    Wssize,
}

impl StepType {
    pub const fn registry_id(self) -> &'static str {
        match self {
            Self::Split => "split",
            Self::Disorder => "disorder",
            Self::Fake => "fake",
            Self::Oob => "oob",
            Self::FakeRst => "fake_rst",
            Self::SeqOverlap => "seq_overlap",
            Self::IpFrag => "ip_frag",
            Self::MultiDisorder => "multi_disorder",
            Self::Udplen => "udplen",
            Self::HttpDomcase => "http_domcase",
            Self::HttpHostcase => "http_hostcase",
            Self::HttpMethodeol => "http_methodeol",
            Self::HttpUnixeol => "http_unixeol",
            Self::Wsize => "wsize",
            Self::Wssize => "wssize",
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct RawConfig {
    version: u32,
    strategies: Vec<RawStrategy>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct RawStrategy {
    id: String,
    #[serde(rename = "match", default)]
    matcher: RawMatch,
    steps: Vec<StrategyStep>,
    #[serde(default)]
    on_fail: OnFail,
}

#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct RawMatch {
    #[serde(default)]
    proto: Vec<ProtocolName>,
    #[serde(default)]
    port: Vec<u16>,
    #[serde(default)]
    hosts: HostSpec,
}

#[derive(Debug, Default, Deserialize)]
#[serde(untagged)]
enum HostSpec {
    Inline(Vec<String>),
    Reference(String),
    #[default]
    Empty,
}

pub fn parse_yaml_str(input: &str, base_dir: impl AsRef<Path>) -> Result<LoadedStrategyConfig, StrategyConfigError> {
    let raw: RawConfig = serde_yaml_ng::from_str(input)?;
    load_raw(raw, base_dir.as_ref())
}

pub fn load_yaml_file(path: impl AsRef<Path>) -> Result<LoadedStrategyConfig, StrategyConfigError> {
    let path = path.as_ref();
    let input = fs::read_to_string(path)
        .map_err(|source| StrategyConfigError::ConfigRead { path: path.to_path_buf(), source })?;
    let base_dir = path.parent().unwrap_or_else(|| Path::new("."));
    parse_yaml_str(&input, base_dir)
}

fn load_raw(raw: RawConfig, base_dir: &Path) -> Result<LoadedStrategyConfig, StrategyConfigError> {
    let strategies = raw
        .strategies
        .into_iter()
        .map(|strategy| {
            let hosts = resolve_hosts(strategy.matcher.hosts, base_dir)?;
            Ok(LoadedStrategy {
                id: strategy.id,
                matcher: StrategyMatch { proto: strategy.matcher.proto, port: strategy.matcher.port, hosts },
                steps: strategy.steps,
                on_fail: strategy.on_fail,
            })
        })
        .collect::<Result<Vec<_>, StrategyConfigError>>()?;
    Ok(LoadedStrategyConfig { version: raw.version, strategies })
}

fn resolve_hosts(hosts: HostSpec, base_dir: &Path) -> Result<Vec<String>, StrategyConfigError> {
    match hosts {
        HostSpec::Inline(hosts) => Ok(hosts),
        HostSpec::Reference(reference) if reference.starts_with('@') => {
            let raw_path = Path::new(&reference[1..]);
            let path = if raw_path.is_absolute() { raw_path.to_path_buf() } else { base_dir.join(raw_path) };
            let contents = fs::read_to_string(&path)
                .map_err(|source| StrategyConfigError::HostListRead { path: path.clone(), source })?;
            Ok(contents
                .lines()
                .map(str::trim)
                .filter(|line| !line.is_empty() && !line.starts_with('#'))
                .map(str::to_owned)
                .collect())
        }
        HostSpec::Reference(host) => Ok(vec![host]),
        HostSpec::Empty => Ok(Vec::new()),
    }
}

#[derive(Debug)]
pub struct StrategyConfigReloader {
    path: PathBuf,
    modified: Option<SystemTime>,
    config: LoadedStrategyConfig,
}

impl StrategyConfigReloader {
    pub fn load(path: impl AsRef<Path>) -> Result<Self, StrategyConfigError> {
        let path = path.as_ref().to_path_buf();
        let config = load_yaml_file(&path)?;
        let modified = file_modified(&path)?;
        Ok(Self { path, modified, config })
    }

    pub fn current(&self) -> &LoadedStrategyConfig {
        &self.config
    }

    pub fn reload_if_changed(&mut self) -> Result<bool, StrategyConfigError> {
        let modified = file_modified(&self.path)?;
        if modified == self.modified {
            return Ok(false);
        }
        self.config = load_yaml_file(&self.path)?;
        self.modified = modified;
        Ok(true)
    }
}

fn file_modified(path: &Path) -> Result<Option<SystemTime>, StrategyConfigError> {
    fs::metadata(path)
        .map(|metadata| metadata.modified().ok())
        .map_err(|source| StrategyConfigError::Metadata { path: path.to_path_buf(), source })
}
