#![forbid(unsafe_code)]

//! Strategy-chain config model — the **file-driven** strategy surface.
//!
//! Parses developer- and CLI-authored YAML or TOML strategy files into a
//! [`LoadedStrategyConfig`], resolves `@file` host-list references, and
//! hot-reloads on change ([`StrategyConfigReloader`]). The output is consumed
//! by `ripdpi-strategy-registry` (`StrategyRegistry::from_loaded_config`).
//!
//! [`StepType`] is a **string-backed known/unknown** step kind: every
//! recognized YAML/TOML `type:` spelling — the canonical registry id, the
//! camelCase form, and legacy aliases — resolves to a named variant, and any
//! other string parses to [`StepType::Unknown`] rather than failing serde
//! decoding, so an experimental strategy id fails later at registry
//! resolution instead. Its [`registry_id`](StepType::registry_id) mapping must
//! stay in lock-step with the stable IDs in `ripdpi-strategy-registry`. This
//! crate is distinct from the Android protobuf settings path (Kotlin
//! `StrategyChain*`); see `README.md` and `FEATURE_EXTENSION_GUIDE.md` §1.

use std::fmt;
use std::fs;
use std::path::{Component, Path, PathBuf};
use std::time::SystemTime;

use serde::de::{self, Visitor};
use serde::{Deserialize, Deserializer};
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
    #[error("host list reference {reference} escapes the strategy-config base directory")]
    HostListPathEscape { reference: String },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LoadedStrategyConfig {
    pub version: u32,
    /// Directory the config was loaded from. This is the trust anchor that
    /// confines both `@file` host-list references and `lua` step
    /// `script_paths`: an imported (potentially untrusted) config may only
    /// reach files inside this directory. Always populated by the parse
    /// entry points; `.` for in-memory `parse_*_str` callers that pass it.
    pub base_dir: PathBuf,
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

/// A strategy chain step kind.
///
/// String-backed: a recognized YAML/TOML `type:` value resolves to a named
/// variant; any other string parses to [`StepType::Unknown`] so an
/// experimental strategy id can be carried through and rejected later at
/// registry resolution rather than at serde decoding. Deserialization is
/// hand-written ([`StepType::from_wire`]) so every known spelling — the
/// canonical id, the camelCase form, and legacy aliases — is preserved.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StepType {
    Split,
    Disorder,
    Fake,
    Oob,
    FakeRst,
    SeqOverlap,
    IpFrag,
    MultiDisorder,
    TlsRec,
    TlsRandRec,
    Udplen,
    HttpDomcase,
    HttpHostcase,
    HttpMethodeol,
    HttpUnixeol,
    Wsize,
    Wssize,
    Ipv6Ext,
    SynAck,
    SynAckSplit,
    Lua,
    /// An unrecognized step kind — the raw `type:` string. Parses cleanly so
    /// it surfaces as an `unknown strategy type` error at registry resolution.
    Unknown(String),
}

impl StepType {
    /// Every known step kind, in registry order. Excludes [`StepType::Unknown`].
    pub const ALL: &'static [StepType] = &[
        StepType::Split,
        StepType::Disorder,
        StepType::Fake,
        StepType::Oob,
        StepType::FakeRst,
        StepType::SeqOverlap,
        StepType::IpFrag,
        StepType::MultiDisorder,
        StepType::TlsRec,
        StepType::TlsRandRec,
        StepType::Udplen,
        StepType::HttpDomcase,
        StepType::HttpHostcase,
        StepType::HttpMethodeol,
        StepType::HttpUnixeol,
        StepType::Wsize,
        StepType::Wssize,
        StepType::Ipv6Ext,
        StepType::SynAck,
        StepType::SynAckSplit,
        StepType::Lua,
    ];

    /// The stable registry id this step resolves to.
    ///
    /// For a known variant this is the canonical id; for [`StepType::Unknown`]
    /// it is the raw `type:` string, which the registry then rejects.
    pub fn registry_id(&self) -> &str {
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
            Self::Unknown(id) => id.as_str(),
        }
    }

    /// Resolves a YAML/TOML `type:` string to a step kind.
    ///
    /// Recognizes the canonical registry id, the camelCase spelling, and the
    /// legacy `snake_case` aliases that the former `#[serde(alias = …)]` /
    /// `#[serde(rename = …)]` attributes accepted. Any other string becomes
    /// [`StepType::Unknown`].
    pub fn from_wire(value: &str) -> Self {
        match value {
            "split" => Self::Split,
            "disorder" => Self::Disorder,
            "fake" => Self::Fake,
            "oob" => Self::Oob,
            "fakeRst" | "fake_rst" => Self::FakeRst,
            "seqOverlap" | "seq_overlap" => Self::SeqOverlap,
            "ipFrag" | "ip_frag" => Self::IpFrag,
            "multiDisorder" | "multi_disorder" => Self::MultiDisorder,
            "tlsRec" | "tls_rec" => Self::TlsRec,
            "tlsRandRec" | "tls_rand_rec" => Self::TlsRandRec,
            "udplen" => Self::Udplen,
            "httpDomcase" | "http_domcase" => Self::HttpDomcase,
            "httpHostcase" | "http_hostcase" => Self::HttpHostcase,
            "httpMethodeol" | "http_methodeol" => Self::HttpMethodeol,
            "httpUnixeol" | "http_unixeol" => Self::HttpUnixeol,
            "wsize" => Self::Wsize,
            "wssize" => Self::Wssize,
            "ipv6Ext" | "ipv6_ext" => Self::Ipv6Ext,
            "synack" => Self::SynAck,
            "synack_split" => Self::SynAckSplit,
            "lua" => Self::Lua,
            other => Self::Unknown(other.to_owned()),
        }
    }
}

impl<'de> Deserialize<'de> for StepType {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct StepTypeVisitor;

        impl Visitor<'_> for StepTypeVisitor {
            type Value = StepType;

            fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                formatter.write_str("a strategy step `type` string")
            }

            fn visit_str<E: de::Error>(self, value: &str) -> Result<StepType, E> {
                Ok(StepType::from_wire(value))
            }
        }

        deserializer.deserialize_str(StepTypeVisitor)
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
    Ok(LoadedStrategyConfig { version: raw.version, base_dir: base_dir.to_path_buf(), strategies })
}

fn resolve_hosts(hosts: HostSpec, base_dir: &Path) -> Result<Vec<String>, StrategyConfigError> {
    match hosts {
        HostSpec::Inline(hosts) => Ok(hosts),
        HostSpec::Reference(reference) if reference.starts_with('@') => {
            let raw = &reference[1..];
            let path = jail_host_list_path(raw, base_dir, &reference)?;
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

/// Confines an `@file` host-list reference to the strategy-config base dir.
///
/// A strategy config may be imported from an untrusted source, so an `@file`
/// reference must not be allowed to read arbitrary files. Absolute paths and any
/// `..` traversal are rejected outright; otherwise the reference is resolved
/// relative to `base_dir`. When the target file exists it is canonicalized and
/// asserted to stay within the canonicalized base dir (defends against symlink
/// escapes); when it does not yet exist the lexical join is returned so the
/// caller surfaces the original [`HostListRead`](StrategyConfigError::HostListRead)
/// not-found error.
fn jail_host_list_path(raw: &str, base_dir: &Path, reference: &str) -> Result<PathBuf, StrategyConfigError> {
    let raw_path = Path::new(raw);
    if raw_path.is_absolute() || raw_path.components().any(|component| component == Component::ParentDir) {
        return Err(StrategyConfigError::HostListPathEscape { reference: reference.to_owned() });
    }
    let joined = base_dir.join(raw_path);
    match (fs::canonicalize(&joined), fs::canonicalize(base_dir)) {
        (Ok(canonical_target), Ok(canonical_base)) => {
            if canonical_target.starts_with(&canonical_base) {
                Ok(canonical_target)
            } else {
                Err(StrategyConfigError::HostListPathEscape { reference: reference.to_owned() })
            }
        }
        // Target (or base) is not yet on disk: fall through with the lexical
        // join, which is already free of `..`/absolute escapes checked above.
        _ => Ok(joined),
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
