#![forbid(unsafe_code)]

//! Strategy-chain config model — the **file-driven** strategy surface.
//!
//! Parses developer- and CLI-authored YAML or TOML strategy files into a
//! [`LoadedStrategyConfig`], resolves `@file` host-list references, and
//! hot-reloads on change ([`StrategyConfigReloader`]). The output is consumed
//! by `ripdpi-strategy-registry` (`StrategyRegistry::from_loaded_config`).
//!
//! [`StepType`]'s serde representation **is** the YAML/TOML schema; its
//! [`registry_id`](StepType::registry_id) mapping must stay in lock-step with
//! the stable IDs in `ripdpi-strategy-registry`. This crate is distinct from
//! the Android protobuf settings path (Kotlin `StrategyChain*`); see
//! `README.md` and `FEATURE_EXTENSION_GUIDE.md` §1.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use serde::Deserialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum StrategyConfigError {
    #[error("failed to parse strategy YAML: {0}")]
    Parse(#[from] serde_yaml_ng::Error),
    #[error("failed to parse strategy TOML: {0}")]
    TomlParse(#[from] toml::de::Error),
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
    #[serde(default)]
    pub ext_type: Option<String>,
    #[serde(default)]
    pub function: Option<String>,
    #[serde(default)]
    pub script_paths: Vec<String>,
    #[serde(default)]
    pub forward_original: Option<bool>,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum StepType {
    Split,
    Disorder,
    Fake,
    Oob,
    #[serde(alias = "fake_rst")]
    FakeRst,
    #[serde(alias = "seq_overlap")]
    SeqOverlap,
    #[serde(alias = "ip_frag")]
    IpFrag,
    #[serde(alias = "multi_disorder")]
    MultiDisorder,
    #[serde(rename = "tls_rec", alias = "tlsRec")]
    TlsRec,
    #[serde(rename = "tls_rand_rec", alias = "tlsRandRec")]
    TlsRandRec,
    Udplen,
    #[serde(alias = "http_domcase")]
    HttpDomcase,
    #[serde(alias = "http_hostcase")]
    HttpHostcase,
    #[serde(alias = "http_methodeol")]
    HttpMethodeol,
    #[serde(alias = "http_unixeol")]
    HttpUnixeol,
    Wsize,
    Wssize,
    #[serde(alias = "ipv6_ext")]
    Ipv6Ext,
    #[serde(rename = "synack")]
    SynAck,
    #[serde(rename = "synack_split")]
    SynAckSplit,
    Lua,
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
            Self::TlsRec => "tls_rec",
            Self::TlsRandRec => "tls_rand_rec",
            Self::Udplen => "udplen",
            Self::HttpDomcase => "http_domcase",
            Self::HttpHostcase => "http_hostcase",
            Self::HttpMethodeol => "http_methodeol",
            Self::HttpUnixeol => "http_unixeol",
            Self::Wsize => "wsize",
            Self::Wssize => "wssize",
            Self::Ipv6Ext => "ipv6_ext",
            Self::SynAck => "synack",
            Self::SynAckSplit => "synack_split",
            Self::Lua => "lua",
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

pub fn parse_toml_str(input: &str, base_dir: impl AsRef<Path>) -> Result<LoadedStrategyConfig, StrategyConfigError> {
    let raw: RawConfig = toml::from_str(input)?;
    load_raw(raw, base_dir.as_ref())
}

pub fn load_toml_file(path: impl AsRef<Path>) -> Result<LoadedStrategyConfig, StrategyConfigError> {
    let path = path.as_ref();
    let input = fs::read_to_string(path)
        .map_err(|source| StrategyConfigError::ConfigRead { path: path.to_path_buf(), source })?;
    let base_dir = path.parent().unwrap_or_else(|| Path::new("."));
    parse_toml_str(&input, base_dir)
}

pub fn load_config_file(path: impl AsRef<Path>) -> Result<LoadedStrategyConfig, StrategyConfigError> {
    let path = path.as_ref();
    match path.extension().and_then(|extension| extension.to_str()).map(str::to_ascii_lowercase).as_deref() {
        Some("toml") => load_toml_file(path),
        _ => load_yaml_file(path),
    }
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
        let config = load_config_file(&path)?;
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
        self.config = load_config_file(&self.path)?;
        self.modified = modified;
        Ok(true)
    }
}

fn file_modified(path: &Path) -> Result<Option<SystemTime>, StrategyConfigError> {
    fs::metadata(path)
        .map(|metadata| metadata.modified().ok())
        .map_err(|source| StrategyConfigError::Metadata { path: path.to_path_buf(), source })
}
