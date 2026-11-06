#![forbid(unsafe_code)]

mod mapped_file;

// prost-build emits enum name helpers with a borrowed receiver.
#[allow(clippy::trivially_copy_pass_by_ref)]
mod proto {
    include!(concat!(env!("OUT_DIR"), "/geosite.rs"));
}

use std::collections::HashMap;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use arc_swap::ArcSwap;
use maxminddb::{Reader, geoip2};
use memmap2::Mmap;
use prost::Message;
use regex::{Regex, RegexBuilder};
use thiserror::Error;

pub use crate::mapped_file::MappedFileError;

use crate::mapped_file::read_snapshot;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GeoRuntimePaths {
    pub geoip_db: PathBuf,
    pub geosite_db: PathBuf,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GeoRuntimeVersions {
    pub geoip: Option<String>,
    pub geosite: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GeoIpMetadata {
    pub country_code: Option<String>,
    pub asn: Option<u32>,
    pub organization: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GeoRuntimeWarning {
    MissingGeoip(PathBuf),
    MissingGeosite(PathBuf),
}

#[derive(Debug)]
pub struct GeoRuntimeLoad {
    pub runtime: GeoRuntime,
    pub warnings: Vec<GeoRuntimeWarning>,
}

#[derive(Debug)]
pub struct GeoRuntime {
    paths: GeoRuntimePaths,
    databases: Arc<ArcSwap<GeoDatabases>>,
}

impl GeoRuntime {
    /// Reads each database into private memory so later source writes cannot invalidate lookups.
    /// Producers should publish complete files by rename to prevent inconsistent input during the read.
    pub fn load(paths: GeoRuntimePaths) -> Result<GeoRuntimeLoad, GeoRuntimeError> {
        let LoadedDatabases { databases, warnings } = load_databases(&paths)?;
        Ok(GeoRuntimeLoad { runtime: Self { paths, databases: Arc::new(ArcSwap::from_pointee(databases)) }, warnings })
    }

    /// Replaces the snapshots only after both databases load and parse successfully.
    pub fn reload(&self) -> Result<Vec<GeoRuntimeWarning>, GeoRuntimeError> {
        let LoadedDatabases { databases, warnings } = load_databases(&self.paths)?;
        self.databases.store(Arc::new(databases));
        Ok(warnings)
    }

    pub fn versions(&self) -> GeoRuntimeVersions {
        self.databases.load().versions()
    }

    pub fn geosite_contains(&self, category: &str, domain: &str) -> bool {
        self.databases.load().geosite_contains(category, domain)
    }

    pub fn geosite_match(&self, category: &str, domain: &str) -> Option<bool> {
        let databases = self.databases.load();
        let database = databases.geosite.as_ref()?;
        database.category(category)?;
        Some(database.contains(category, domain))
    }

    pub fn geosite_category(&self, category: &str) -> Option<Vec<GeositeDomainRule>> {
        self.databases.load().geosite_category(category)
    }

    pub fn country_contains_ip(&self, country_code: &str, ip: IpAddr) -> bool {
        self.databases.load().country_contains_ip(country_code, ip)
    }

    pub fn ip_metadata(&self, ip: IpAddr) -> Option<GeoIpMetadata> {
        self.databases.load().ip_metadata(ip)
    }
}

#[derive(Debug)]
struct LoadedDatabases {
    databases: GeoDatabases,
    warnings: Vec<GeoRuntimeWarning>,
}

#[derive(Debug, Default)]
struct GeoDatabases {
    geoip: Option<GeoipDatabase>,
    geosite: Option<GeositeDatabase>,
}

impl GeoDatabases {
    fn versions(&self) -> GeoRuntimeVersions {
        GeoRuntimeVersions {
            geoip: self.geoip.as_ref().map(|database| database.version.clone()),
            geosite: self.geosite.as_ref().map(|database| database.version.clone()),
        }
    }

    fn geosite_contains(&self, category: &str, domain: &str) -> bool {
        self.geosite.as_ref().is_some_and(|database| database.contains(category, domain))
    }

    fn geosite_category(&self, category: &str) -> Option<Vec<GeositeDomainRule>> {
        self.geosite.as_ref().and_then(|database| database.category(category)).map(<[GeositeDomainRule]>::to_vec)
    }

    fn country_contains_ip(&self, country_code: &str, ip: IpAddr) -> bool {
        self.geoip.as_ref().is_some_and(|database| database.country_contains_ip(country_code, ip))
    }

    fn ip_metadata(&self, ip: IpAddr) -> Option<GeoIpMetadata> {
        self.geoip.as_ref().and_then(|database| database.ip_metadata(ip))
    }
}

#[derive(Debug)]
struct GeoipDatabase {
    reader: Reader<Mmap>,
    version: String,
}

impl GeoipDatabase {
    fn country_contains_ip(&self, country_code: &str, ip: IpAddr) -> bool {
        self.country_code(ip).is_some_and(|candidate| candidate.eq_ignore_ascii_case(country_code.trim()))
    }

    fn country_code(&self, ip: IpAddr) -> Option<&str> {
        let lookup = self.reader.lookup(ip).ok()?;
        match lookup.decode::<geoip2::Country>() {
            Ok(country) => country?.country.iso_code,
            // sing-geoip (the sing-box geoip.db) stores a bare lowercase code string per network.
            Err(_) => lookup.decode::<&str>().ok()?,
        }
    }

    fn ip_metadata(&self, ip: IpAddr) -> Option<GeoIpMetadata> {
        let lookup = self.reader.lookup(ip).ok()?;
        let country_code = match lookup.decode::<geoip2::Country>() {
            Ok(country) => country.and_then(|country| country.country.iso_code.map(ToOwned::to_owned)),
            // sing-geoip leaves also carry non-country tags such as "private" or "telegram".
            Err(_) => lookup
                .decode::<&str>()
                .ok()
                .flatten()
                .filter(|code| code.len() == 2 && code.bytes().all(|byte| byte.is_ascii_alphabetic()))
                .map(str::to_ascii_uppercase),
        };
        let asn = lookup.decode::<geoip2::Asn>().ok().flatten();
        let asn_number = asn.as_ref().and_then(|record| record.autonomous_system_number);
        let organization = asn.and_then(|record| record.autonomous_system_organization.map(ToOwned::to_owned));
        (country_code.is_some() || asn_number.is_some() || organization.is_some()).then_some(GeoIpMetadata {
            country_code,
            asn: asn_number,
            organization,
        })
    }
}

#[derive(Debug)]
struct GeositeDatabase {
    version: String,
    categories: HashMap<String, Vec<GeositeDomainRule>>,
    regexes: HashMap<String, Vec<Regex>>,
}

impl GeositeDatabase {
    fn contains(&self, category: &str, domain: &str) -> bool {
        let key = category.to_ascii_lowercase();
        let Some(rules) = self.categories.get(&key) else { return false };
        let domain = domain.trim_end_matches('.');
        rules.iter().any(|rule| rule.kind != GeositeDomainKind::Regex && rule.matches(domain))
            || self.regexes.get(&key).is_some_and(|regexes| regexes.iter().any(|regex| regex.is_match(domain)))
    }

    fn category(&self, category: &str) -> Option<&[GeositeDomainRule]> {
        self.categories.get(&category.to_ascii_lowercase()).map(Vec::as_slice)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GeositeDomainRule {
    pub kind: GeositeDomainKind,
    pub value: String,
}

impl GeositeDomainRule {
    fn matches(&self, domain: &str) -> bool {
        // Avoid allocating lowercase Strings on this per-flow hot path: compare
        // case-insensitively over borrowed ASCII slices (rust-performance skill —
        // remove per-lookup heap allocation).
        let domain = domain.trim_end_matches('.');
        let value = self.value.trim_end_matches('.');
        match self.kind {
            GeositeDomainKind::Plain => ascii_contains_ignore_case(domain, value),
            GeositeDomainKind::Regex => false,
            GeositeDomainKind::Full => domain.eq_ignore_ascii_case(value),
            GeositeDomainKind::RootDomain => {
                domain.eq_ignore_ascii_case(value)
                    || (domain.len() > value.len()
                        && domain.as_bytes()[domain.len() - value.len() - 1] == b'.'
                        && domain[domain.len() - value.len()..].eq_ignore_ascii_case(value))
            }
        }
    }
}

/// Case-insensitive (ASCII) substring search without allocating a lowercased copy
/// of either operand. Equivalent to `haystack.to_ascii_lowercase().contains(&needle.to_ascii_lowercase())`.
fn ascii_contains_ignore_case(haystack: &str, needle: &str) -> bool {
    let (haystack, needle) = (haystack.as_bytes(), needle.as_bytes());
    if needle.is_empty() {
        return true;
    }
    if needle.len() > haystack.len() {
        return false;
    }
    haystack.windows(needle.len()).any(|window| window.eq_ignore_ascii_case(needle))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GeositeDomainKind {
    Plain,
    Regex,
    RootDomain,
    Full,
}

#[derive(Debug, Error)]
pub enum GeoRuntimeError {
    #[error("failed to map {path}: {source}")]
    Map { path: PathBuf, source: MappedFileError },
    #[error("failed to parse geoip database {path}: {source}")]
    GeoipParse { path: PathBuf, source: maxminddb::MaxMindDbError },
    #[error("failed to parse geosite database {path}: {source}")]
    GeositeParse { path: PathBuf, source: GeositeParseError },
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum GeositeParseError {
    #[error("unexpected end of protobuf input")]
    UnexpectedEof,
    #[error("invalid Protobuf message: {0}")]
    Protobuf(#[from] prost::DecodeError),
    #[error("unsupported GeoSite domain type {0}")]
    UnsupportedDomainType(i32),
    #[error("protobuf varint is too long")]
    VarintTooLong,
    #[error("invalid utf-8 in protobuf string")]
    InvalidUtf8,
    #[error("unsupported sing-box geosite version {0}")]
    UnsupportedSingGeositeVersion(u8),
    #[error("unsupported sing-box geosite rule type {0}")]
    UnsupportedSingGeositeRuleType(u8),
    #[error("sing-box geosite entry {0} does not start where the previous entry ends")]
    NonContiguousSingGeositeEntry(String),
}

fn load_databases(paths: &GeoRuntimePaths) -> Result<LoadedDatabases, GeoRuntimeError> {
    let mut warnings = Vec::new();
    let geoip = load_geoip(&paths.geoip_db, &mut warnings)?;
    let geosite = load_geosite(&paths.geosite_db, &mut warnings)?;
    Ok(LoadedDatabases { databases: GeoDatabases { geoip, geosite }, warnings })
}

fn load_geoip(path: &Path, warnings: &mut Vec<GeoRuntimeWarning>) -> Result<Option<GeoipDatabase>, GeoRuntimeError> {
    if !path.is_file() {
        warnings.push(GeoRuntimeWarning::MissingGeoip(path.to_path_buf()));
        return Ok(None);
    }
    let mapping = read_snapshot(path).map_err(|source| GeoRuntimeError::Map { path: path.to_path_buf(), source })?;
    let mapped_version = mapped_version(path, mapping.len());
    let reader = Reader::from_source(mapping)
        .map_err(|source| GeoRuntimeError::GeoipParse { path: path.to_path_buf(), source })?;
    let metadata = reader.metadata();
    let version = format!("{mapped_version}:{}:{}", metadata.database_type, metadata.build_epoch);
    Ok(Some(GeoipDatabase { reader, version }))
}

fn load_geosite(
    path: &Path,
    warnings: &mut Vec<GeoRuntimeWarning>,
) -> Result<Option<GeositeDatabase>, GeoRuntimeError> {
    if !path.is_file() {
        warnings.push(GeoRuntimeWarning::MissingGeosite(path.to_path_buf()));
        return Ok(None);
    }
    let mapping = read_snapshot(path).map_err(|source| GeoRuntimeError::Map { path: path.to_path_buf(), source })?;
    let categories = parse_geosite_catalog(&mapping)
        .map_err(|source| GeoRuntimeError::GeositeParse { path: path.to_path_buf(), source })?;
    let regexes = categories
        .iter()
        .filter_map(|(category, rules)| {
            let compiled = rules
                .iter()
                .filter(|rule| rule.kind == GeositeDomainKind::Regex)
                .filter_map(|rule| RegexBuilder::new(&rule.value).case_insensitive(true).build().ok())
                .collect::<Vec<_>>();
            (!compiled.is_empty()).then(|| (category.clone(), compiled))
        })
        .collect();
    let version = mapped_version(path, mapping.len());
    Ok(Some(GeositeDatabase { version, categories, regexes }))
}

fn mapped_version(path: &Path, len: usize) -> String {
    format!("{}:{len}", path.file_name().and_then(|name| name.to_str()).unwrap_or("db"))
}

fn parse_geosite_catalog(input: &[u8]) -> Result<HashMap<String, Vec<GeositeDomainRule>>, GeositeParseError> {
    // Zero is not a valid Protobuf tag; it identifies the sing-box version.
    if input.first() == Some(&SING_GEOSITE_VERSION) {
        return parse_sing_geosite_catalog(input);
    }
    let catalog = proto::GeositeCatalog::decode(input)?;
    let mut categories = HashMap::new();
    for entry in catalog.entry {
        let mut domains = Vec::with_capacity(entry.domain.len());
        for domain in entry.domain {
            let kind = match proto::geosite_domain::Type::try_from(domain.r#type) {
                Ok(proto::geosite_domain::Type::Plain) => GeositeDomainKind::Plain,
                Ok(proto::geosite_domain::Type::Regex) => GeositeDomainKind::Regex,
                Ok(proto::geosite_domain::Type::RootDomain) => GeositeDomainKind::RootDomain,
                Ok(proto::geosite_domain::Type::Full) => GeositeDomainKind::Full,
                Err(_) => return Err(GeositeParseError::UnsupportedDomainType(domain.r#type)),
            };
            domains.push(GeositeDomainRule { kind, value: domain.value });
        }
        categories.insert(entry.country_code.to_ascii_lowercase(), domains);
    }
    Ok(categories)
}

/// Parses the sing-box `geosite.db` layout: a version byte, a uvarint entry count, one index row per
/// entry (length-prefixed code, uvarint item offset, uvarint item count), then the items, each a
/// type byte and a length-prefixed value.
fn parse_sing_geosite_catalog(input: &[u8]) -> Result<HashMap<String, Vec<GeositeDomainRule>>, GeositeParseError> {
    let mut cursor = SingCursor::new(input);
    let version = cursor.byte()?;
    if version != SING_GEOSITE_VERSION {
        return Err(GeositeParseError::UnsupportedSingGeositeVersion(version));
    }
    let entry_count = cursor.varint()?;
    let mut index = Vec::new();
    for _ in 0..entry_count {
        let code = cursor.string()?;
        let offset = usize::try_from(cursor.varint()?).map_err(|_| GeositeParseError::VarintTooLong)?;
        let item_count = cursor.varint()?;
        index.push((code, offset, item_count));
    }
    // sing-geosite writes the items in index order with no gaps. Requiring that layout means each
    // item is read once, so aliased offsets cannot expand a small file into billions of rules.
    let mut items = SingCursor::new(cursor.remaining());
    let mut categories = HashMap::new();
    for (code, offset, item_count) in index {
        if offset != items.offset {
            return Err(GeositeParseError::NonContiguousSingGeositeEntry(code));
        }
        let mut rules = Vec::new();
        for _ in 0..item_count {
            let kind = match items.byte()? {
                SING_GEOSITE_DOMAIN => GeositeDomainKind::Full,
                SING_GEOSITE_DOMAIN_SUFFIX => GeositeDomainKind::RootDomain,
                SING_GEOSITE_DOMAIN_KEYWORD => GeositeDomainKind::Plain,
                SING_GEOSITE_DOMAIN_REGEX => GeositeDomainKind::Regex,
                other => return Err(GeositeParseError::UnsupportedSingGeositeRuleType(other)),
            };
            let mut value = items.string()?;
            // sing-geosite writes a v2ray root domain as suffix ".value" (plus a full rule when the
            // value has a dot); the root-domain rule matches the value and its subdomains.
            if kind == GeositeDomainKind::RootDomain && value.starts_with('.') {
                value.remove(0);
            }
            rules.push(GeositeDomainRule { kind, value });
        }
        categories.insert(code.to_ascii_lowercase(), rules);
    }
    Ok(categories)
}

struct SingCursor<'a> {
    input: &'a [u8],
    offset: usize,
}

impl<'a> SingCursor<'a> {
    fn new(input: &'a [u8]) -> Self {
        Self { input, offset: 0 }
    }

    fn remaining(&self) -> &'a [u8] {
        self.input.get(self.offset..).unwrap_or_default()
    }

    fn byte(&mut self) -> Result<u8, GeositeParseError> {
        let byte = *self.input.get(self.offset).ok_or(GeositeParseError::UnexpectedEof)?;
        self.offset += 1;
        Ok(byte)
    }

    fn varint(&mut self) -> Result<u64, GeositeParseError> {
        let mut remaining = self.remaining();
        let value = prost::encoding::decode_varint(&mut remaining)?;
        self.offset = self.input.len() - remaining.len();
        Ok(value)
    }

    fn string(&mut self) -> Result<String, GeositeParseError> {
        let len = usize::try_from(self.varint()?).map_err(|_| GeositeParseError::VarintTooLong)?;
        let end = self.offset.checked_add(len).ok_or(GeositeParseError::UnexpectedEof)?;
        let slice = self.input.get(self.offset..end).ok_or(GeositeParseError::UnexpectedEof)?;
        self.offset = end;
        std::str::from_utf8(slice).map(str::to_owned).map_err(|_| GeositeParseError::InvalidUtf8)
    }
}

const SING_GEOSITE_VERSION: u8 = 0;
const SING_GEOSITE_DOMAIN: u8 = 0;
const SING_GEOSITE_DOMAIN_SUFFIX: u8 = 1;
const SING_GEOSITE_DOMAIN_KEYWORD: u8 = 2;
const SING_GEOSITE_DOMAIN_REGEX: u8 = 3;

#[cfg(test)]
mod protobuf_tests;

#[cfg(test)]
mod tests {
    use std::fs;
    use std::net::IpAddr;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};

    use super::*;

    const GEOIP_COUNTRY_FIXTURE: &[u8] = include_bytes!("../tests/fixtures/GeoIP2-Country-Test.mmdb");
    const GEOIP_ASN_FIXTURE: &[u8] = include_bytes!("../tests/fixtures/GeoLite2-ASN-Test.mmdb");
    static TEMP_DIR_COUNTER: AtomicU64 = AtomicU64::new(0);

    #[test]
    fn missing_databases_return_typed_warnings_without_panic() {
        let dir = temp_dir();
        let result = GeoRuntime::load(paths(&dir)).expect("runtime loads without database files");

        assert_eq!(
            vec![
                GeoRuntimeWarning::MissingGeoip(dir.join("geoip.db")),
                GeoRuntimeWarning::MissingGeosite(dir.join("geosite.db")),
            ],
            result.warnings
        );
        assert_eq!(GeoRuntimeVersions { geoip: None, geosite: None }, result.runtime.versions());
        assert!(!result.runtime.geosite_contains("ru", "example.ru"));
        assert!(!result.runtime.country_contains_ip("se", ip("89.160.20.112")));
    }

    #[test]
    fn geosite_lookup_matches_root_and_full_domains_from_mapped_database() {
        let dir = temp_dir();
        fs::write(dir.join("geosite.db"), geosite_catalog("ru", &[domain(2, "example.ru"), domain(3, "exact.ru")]))
            .expect("write geosite fixture");

        let result = GeoRuntime::load(paths(&dir)).expect("runtime loads geosite database");

        assert_eq!(vec![GeoRuntimeWarning::MissingGeoip(dir.join("geoip.db"))], result.warnings);
        assert!(result.runtime.geosite_contains("RU", "www.example.ru"));
        assert!(result.runtime.geosite_contains("ru", "exact.ru"));
        assert!(!result.runtime.geosite_contains("ru", "www.exact.ru"));
        assert_eq!(Some(true), result.runtime.geosite_match("ru", "www.example.ru"));
        assert_eq!(Some(false), result.runtime.geosite_match("ru", "foreign.example"));
        assert_eq!(None, result.runtime.geosite_match("missing", "www.example.ru"));
        assert_eq!(
            Some(vec![GeositeDomainRule { kind: GeositeDomainKind::RootDomain, value: "example.ru".to_owned() }]),
            result.runtime.geosite_category("ru").map(|rules| vec![rules[0].clone()])
        );
    }

    #[test]
    fn geosite_lookup_matches_regex_domains_from_mapped_database() {
        let dir = temp_dir();
        fs::write(
            dir.join("geosite.db"),
            geosite_catalog(
                "test",
                &[domain(1, r"^([a-z0-9-]+\.)?example\.com$"), domain(1, "("), domain(3, "exact.test")],
            ),
        )
        .expect("write geosite fixture");

        let runtime = GeoRuntime::load(paths(&dir)).expect("runtime loads geosite database").runtime;
        assert!(runtime.geosite_contains("test", "shop.example.com"));
        assert_eq!(Some(true), runtime.geosite_match("test", "EXAMPLE.COM."));
        assert!(!runtime.geosite_contains("test", "notexample.com"));
        assert!(!runtime.geosite_contains("test", "example.com.evil"));
        assert!(runtime.geosite_contains("test", "exact.test"));
    }

    #[test]
    fn geoip_lookup_matches_country_from_mapped_database() {
        let dir = temp_dir();
        write_geoip_fixture(&dir);

        let result = GeoRuntime::load(paths(&dir)).expect("runtime loads geoip database");

        assert_eq!(vec![GeoRuntimeWarning::MissingGeosite(dir.join("geosite.db"))], result.warnings);
        assert!(result.runtime.country_contains_ip("se", ip("89.160.20.112")));
        assert!(result.runtime.country_contains_ip("SE", ip("89.160.20.112")));
        assert!(!result.runtime.country_contains_ip("ru", ip("89.160.20.112")));
        assert!(!result.runtime.country_contains_ip("se", ip("10.0.0.1")));
    }

    #[test]
    fn geoip_metadata_reads_asn_and_organization_from_mapped_database() {
        let dir = temp_dir();
        write_geoip_asn_fixture(&dir);

        let result = GeoRuntime::load(paths(&dir)).expect("runtime loads geoip asn database");
        let metadata = result.runtime.ip_metadata(ip("1.128.0.123")).expect("metadata");

        assert_eq!(None, metadata.country_code);
        assert_eq!(Some(1221), metadata.asn);
        assert_eq!(Some("Telstra Pty Ltd".to_string()), metadata.organization);
        assert_eq!(None, result.runtime.ip_metadata(ip("10.0.0.1")));
    }

    #[test]
    fn geoip_metadata_reads_country_from_country_database_without_asn() {
        let dir = temp_dir();
        write_geoip_fixture(&dir);

        let result = GeoRuntime::load(paths(&dir)).expect("runtime loads geoip country database");
        let metadata = result.runtime.ip_metadata(ip("89.160.20.112")).expect("metadata");

        assert_eq!(Some("SE".to_string()), metadata.country_code);
        assert_eq!(None, metadata.asn);
        assert_eq!(None, metadata.organization);
    }

    #[test]
    fn versions_report_loaded_mapped_files() {
        let dir = temp_dir();
        write_geoip_fixture(&dir);
        fs::write(dir.join("geosite.db"), geosite_catalog("ru", &[domain(2, "example.ru")]))
            .expect("write geosite fixture");

        let result = GeoRuntime::load(paths(&dir)).expect("runtime loads database files");
        let versions = result.runtime.versions();

        let geoip_version = versions.geoip.expect("geoip version");
        assert!(geoip_version.starts_with("geoip.db:"));
        assert!(geoip_version.contains(":GeoIP2-Country:"));
        assert!(versions.geosite.is_some_and(|version| version.starts_with("geosite.db:")));
    }

    #[test]
    fn reload_atomically_swaps_geosite_database() {
        let dir = temp_dir();
        fs::write(dir.join("geosite.db"), geosite_catalog("ru", &[domain(2, "example.ru")]))
            .expect("write initial geosite fixture");
        let result = GeoRuntime::load(paths(&dir)).expect("runtime loads initial database");
        assert!(result.runtime.geosite_contains("ru", "example.ru"));

        write_geoip_fixture(&dir);
        fs::write(dir.join("geosite.db"), geosite_catalog("cn", &[domain(2, "example.cn")]))
            .expect("write replacement geosite fixture");
        let warnings = result.runtime.reload().expect("reload succeeds");

        assert!(warnings.is_empty());
        assert!(!result.runtime.geosite_contains("ru", "example.ru"));
        assert!(result.runtime.geosite_contains("cn", "www.example.cn"));
        assert!(result.runtime.country_contains_ip("se", ip("89.160.20.112")));
    }

    #[test]
    fn geoip_snapshot_survives_source_overwrite_and_truncation() {
        let dir = temp_dir();
        write_geoip_fixture(&dir);
        let runtime = GeoRuntime::load(paths(&dir)).expect("load country database").runtime;

        write_geoip_asn_fixture(&dir);
        assert!(runtime.country_contains_ip("se", ip("89.160.20.112")));
        runtime.reload().expect("load replacement ASN database");
        let expected =
            GeoIpMetadata { country_code: None, asn: Some(1221), organization: Some("Telstra Pty Ltd".to_owned()) };
        assert_eq!(Some(expected.clone()), runtime.ip_metadata(ip("1.128.0.123")));
        let versions = runtime.versions();

        fs::write(dir.join("geoip.db"), []).expect("truncate source");
        assert_eq!(Some(expected.clone()), runtime.ip_metadata(ip("1.128.0.123")));
        assert!(matches!(runtime.reload(), Err(GeoRuntimeError::Map { source: MappedFileError::EmptyFile, .. })));
        assert_eq!(versions, runtime.versions());
        assert_eq!(Some(expected), runtime.ip_metadata(ip("1.128.0.123")));
        fs::remove_dir_all(dir).expect("remove fixtures");
    }

    #[test]
    fn failed_reload_preserves_both_snapshots_until_complete_replacement() {
        let dir = temp_dir();
        write_geoip_fixture(&dir);
        fs::write(dir.join("geosite.db"), geosite_catalog("ru", &[domain(2, "example.ru")]))
            .expect("write geosite fixture");
        let runtime = GeoRuntime::load(paths(&dir)).expect("load initial databases").runtime;
        let versions = runtime.versions();

        let pending = dir.join("geoip.pending");
        fs::write(&pending, GEOIP_ASN_FIXTURE).expect("write replacement");
        fs::rename(pending, dir.join("geoip.db")).expect("publish replacement");
        fs::write(dir.join("geosite.db"), b"\x0a").expect("write malformed geosite");
        assert!(matches!(runtime.reload(), Err(GeoRuntimeError::GeositeParse { .. })));
        assert_eq!(versions, runtime.versions());
        assert!(runtime.country_contains_ip("se", ip("89.160.20.112")));
        assert!(runtime.geosite_contains("ru", "example.ru"));

        fs::write(dir.join("geosite.db"), geosite_catalog("cn", &[domain(2, "example.cn")]))
            .expect("write valid geosite");
        assert!(runtime.reload().expect("reload both databases").is_empty());
        assert_eq!(Some(1221), runtime.ip_metadata(ip("1.128.0.123")).and_then(|record| record.asn));
        assert!(!runtime.geosite_contains("ru", "example.ru"));
        assert!(runtime.geosite_contains("cn", "example.cn"));
        fs::remove_dir_all(dir).expect("remove fixtures");
    }

    #[test]
    fn empty_database_files_return_typed_errors() {
        for name in ["geoip.db", "geosite.db"] {
            let dir = temp_dir();
            let path = dir.join(name);
            fs::write(&path, []).expect("write empty fixture");
            assert!(matches!(
                GeoRuntime::load(paths(&dir)),
                Err(GeoRuntimeError::Map { path: error_path, source: MappedFileError::EmptyFile })
                    if error_path == path
            ));
            fs::remove_dir_all(dir).expect("remove fixtures");
        }
    }

    #[test]
    fn geosite_lookup_reads_sing_box_geosite_database() {
        let dir = temp_dir();
        let catalog = sing_geosite_catalog(&[
            ("ru", &[(0, "example.ru"), (1, ".example.ru"), (1, ".su"), (0, "exact.ru")]),
            ("Test", &[(2, "keyword"), (3, r"^shop\.example\.com$")]),
        ]);
        fs::write(dir.join("geosite.db"), catalog).expect("write geosite fixture");

        let runtime = GeoRuntime::load(paths(&dir)).expect("runtime loads sing-box geosite database").runtime;

        assert!(runtime.geosite_contains("ru", "example.ru"));
        assert!(runtime.geosite_contains("RU", "www.example.ru"));
        assert!(runtime.geosite_contains("ru", "su"));
        assert!(runtime.geosite_contains("ru", "mail.su"));
        assert!(runtime.geosite_contains("ru", "exact.ru"));
        assert!(!runtime.geosite_contains("ru", "notexample.ru"));
        assert!(runtime.geosite_contains("test", "a.keyword.example"));
        assert!(runtime.geosite_contains("test", "shop.example.com"));
        assert!(!runtime.geosite_contains("test", "example.com"));
        assert_eq!(None, runtime.geosite_match("missing", "example.ru"));
    }

    #[test]
    fn sing_box_geosite_rejects_truncated_input_and_unknown_rule_types() {
        let mut truncated = sing_geosite_catalog(&[("ru", &[(0, "example.ru")])]);
        truncated.pop();
        assert_eq!(Err(GeositeParseError::UnexpectedEof), parse_geosite_catalog(&truncated).map(|_| ()));
        let mut aliased = vec![0, 2];
        for code in ["a", "b"] {
            // Both entries claim the same items at offset 0.
            aliased.extend_from_slice(&[1, code.as_bytes()[0], 0, 1]);
        }
        aliased.extend_from_slice(&[0, 1, b'x']);
        assert_eq!(
            Err(GeositeParseError::NonContiguousSingGeositeEntry("b".to_owned())),
            parse_geosite_catalog(&aliased).map(|_| ())
        );
        let unknown = sing_geosite_catalog(&[("ru", &[(9, "example.ru")])]);
        assert_eq!(
            Err(GeositeParseError::UnsupportedSingGeositeRuleType(9)),
            parse_geosite_catalog(&unknown).map(|_| ())
        );
    }

    #[test]
    fn geoip_lookup_reads_sing_geoip_string_records() {
        let dir = temp_dir();
        // 0.0.0.0/1 is "se", 128.0.0.0/2 is the non-country tag "telegram", 192.0.0.0/2 has no record.
        fs::write(dir.join("geoip.db"), sing_geoip_database(&["se", "telegram"])).expect("write geoip fixture");

        let runtime = GeoRuntime::load(paths(&dir)).expect("runtime loads sing-geoip database").runtime;

        assert!(runtime.country_contains_ip("se", ip("89.160.20.112")));
        assert!(runtime.country_contains_ip("SE", ip("89.160.20.112")));
        assert!(!runtime.country_contains_ip("ru", ip("89.160.20.112")));
        assert!(runtime.country_contains_ip("telegram", ip("149.154.167.50")));
        assert!(!runtime.country_contains_ip("se", ip("203.0.113.1")));
        assert_eq!(
            Some(GeoIpMetadata { country_code: Some("SE".to_owned()), asn: None, organization: None }),
            runtime.ip_metadata(ip("89.160.20.112"))
        );
        assert_eq!(None, runtime.ip_metadata(ip("149.154.167.50")));
        assert!(runtime.versions().geoip.is_some_and(|version| version.contains(":sing-geoip:")));
    }

    fn paths(dir: &Path) -> GeoRuntimePaths {
        GeoRuntimePaths { geoip_db: dir.join("geoip.db"), geosite_db: dir.join("geosite.db") }
    }

    fn temp_dir() -> PathBuf {
        let base = std::env::temp_dir();
        for _ in 0..1024 {
            let nanos = SystemTime::now().duration_since(UNIX_EPOCH).expect("clock").as_nanos();
            let sequence = TEMP_DIR_COUNTER.fetch_add(1, Ordering::Relaxed);
            let dir = base.join(format!("ripdpi-geo-{}-{nanos}-{sequence}", std::process::id()));
            match fs::create_dir(&dir) {
                Ok(()) => return dir,
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(error) => panic!("create temp dir {dir:?}: {error}"),
            }
        }
        panic!("failed to allocate unique ripdpi-geo temp dir");
    }

    fn write_geoip_fixture(dir: &Path) {
        fs::write(dir.join("geoip.db"), GEOIP_COUNTRY_FIXTURE).expect("write geoip fixture");
    }

    fn write_geoip_asn_fixture(dir: &Path) {
        fs::write(dir.join("geoip.db"), GEOIP_ASN_FIXTURE).expect("write geoip asn fixture");
    }

    fn ip(input: &str) -> IpAddr {
        input.parse().expect("valid IP")
    }

    fn geosite_catalog(country_code: &str, domains: &[Vec<u8>]) -> Vec<u8> {
        let mut entry = Vec::new();
        field_string(&mut entry, 1, country_code);
        for domain in domains {
            field_bytes(&mut entry, 2, domain);
        }
        let mut catalog = Vec::new();
        field_bytes(&mut catalog, 1, &entry);
        catalog
    }

    fn domain(kind: u64, value: &str) -> Vec<u8> {
        let mut out = Vec::new();
        field_varint(&mut out, 1, kind);
        field_string(&mut out, 2, value);
        out
    }

    fn field_string(out: &mut Vec<u8>, field: u64, value: &str) {
        field_bytes(out, field, value.as_bytes());
    }

    fn field_bytes(out: &mut Vec<u8>, field: u64, value: &[u8]) {
        varint(out, (field << 3) | 2);
        varint(out, value.len() as u64);
        out.extend_from_slice(value);
    }

    fn field_varint(out: &mut Vec<u8>, field: u64, value: u64) {
        varint(out, field << 3);
        varint(out, value);
    }

    fn sing_geosite_catalog(entries: &[(&str, &[(u8, &str)])]) -> Vec<u8> {
        let mut header = vec![0];
        let mut content = Vec::new();
        varint(&mut header, entries.len() as u64);
        for (code, items) in entries {
            varint(&mut header, code.len() as u64);
            header.extend_from_slice(code.as_bytes());
            varint(&mut header, content.len() as u64);
            varint(&mut header, items.len() as u64);
            for (kind, value) in *items {
                content.push(*kind);
                varint(&mut content, value.len() as u64);
                content.extend_from_slice(value.as_bytes());
            }
        }
        header.extend_from_slice(&content);
        header
    }

    /// A minimal IPv4 MaxMind DB as sing-geoip writes it: string leaves, 24-bit records, two nodes.
    /// Node 0 splits on the first address bit: left is `codes[0]`, right is node 1. Node 1 splits on
    /// the second bit: left is `codes[1]`, right is "no record".
    fn sing_geoip_database(codes: &[&str; 2]) -> Vec<u8> {
        const NODE_COUNT: u32 = 2;
        let mut data = Vec::new();
        let mut pointers = Vec::new();
        for code in codes {
            pointers.push(NODE_COUNT + 16 + data.len() as u32);
            mmdb_string(&mut data, code);
        }
        let mut out = Vec::new();
        for record in [pointers[0], 1, pointers[1], NODE_COUNT] {
            out.extend_from_slice(&record.to_be_bytes()[1..]);
        }
        out.extend_from_slice(&[0; 16]);
        out.extend_from_slice(&data);
        out.extend_from_slice(b"\xab\xcd\xefMaxMind.com");
        out.push(0xe0 | 9);
        for (key, value) in [
            ("binary_format_major_version", &[0xa1, 2][..]),
            ("binary_format_minor_version", &[0xa0][..]),
            ("build_epoch", &[0x00, 0x02][..]),
            ("description", &[0xe0][..]),
            ("ip_version", &[0xa1, 4][..]),
            ("languages", &[0x00, 0x04][..]),
            ("node_count", &[0xc1, NODE_COUNT as u8][..]),
            ("record_size", &[0xa1, 24][..]),
        ] {
            mmdb_string(&mut out, key);
            out.extend_from_slice(value);
        }
        mmdb_string(&mut out, "database_type");
        mmdb_string(&mut out, "sing-geoip");
        out
    }

    fn mmdb_string(out: &mut Vec<u8>, value: &str) {
        assert!(value.len() < 29, "short strings only");
        out.push(0x40 | value.len() as u8);
        out.extend_from_slice(value.as_bytes());
    }

    fn varint(out: &mut Vec<u8>, mut value: u64) {
        while value >= 0x80 {
            out.push(((value as u8) & 0x7f) | 0x80);
            value >>= 7;
        }
        out.push(value as u8);
    }
}
