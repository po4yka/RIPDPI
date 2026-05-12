mod mapped_file;

use std::collections::HashMap;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use arc_swap::ArcSwap;
use maxminddb::{geoip2, Reader};
use thiserror::Error;

pub use crate::mapped_file::MappedFileError;

use crate::mapped_file::MappedFile;

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
    pub fn load(paths: GeoRuntimePaths) -> Result<GeoRuntimeLoad, GeoRuntimeError> {
        let LoadedDatabases { databases, warnings } = load_databases(&paths)?;
        Ok(GeoRuntimeLoad { runtime: Self { paths, databases: Arc::new(ArcSwap::from_pointee(databases)) }, warnings })
    }

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

    pub fn geosite_category(&self, category: &str) -> Option<Vec<GeositeDomainRule>> {
        self.databases.load().geosite_category(category)
    }

    pub fn country_contains_ip(&self, country_code: &str, ip: IpAddr) -> bool {
        self.databases.load().country_contains_ip(country_code, ip)
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
}

#[derive(Debug)]
struct GeoipDatabase {
    reader: Reader<MappedFile>,
    version: String,
}

impl GeoipDatabase {
    fn country_contains_ip(&self, country_code: &str, ip: IpAddr) -> bool {
        self.country_code(ip).is_some_and(|candidate| candidate.eq_ignore_ascii_case(country_code.trim()))
    }

    fn country_code(&self, ip: IpAddr) -> Option<&str> {
        let lookup = self.reader.lookup(ip).ok()?;
        let country = lookup.decode::<geoip2::Country>().ok()??;
        country.country.iso_code
    }
}

#[derive(Debug)]
struct GeositeDatabase {
    #[allow(dead_code)]
    mapping: MappedFile,
    version: String,
    categories: HashMap<String, Vec<GeositeDomainRule>>,
}

impl GeositeDatabase {
    fn contains(&self, category: &str, domain: &str) -> bool {
        self.category(category).is_some_and(|rules| rules.iter().any(|rule| rule.matches(domain)))
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
        let domain = domain.trim_end_matches('.').to_ascii_lowercase();
        let value = self.value.trim_end_matches('.').to_ascii_lowercase();
        match self.kind {
            GeositeDomainKind::Plain => domain.contains(&value),
            GeositeDomainKind::Regex => domain == value,
            GeositeDomainKind::RootDomain => domain == value || domain.ends_with(&format!(".{value}")),
            GeositeDomainKind::Full => domain == value,
        }
    }
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
    #[error("unsupported protobuf wire type {0}")]
    UnsupportedWireType(u8),
    #[error("protobuf varint is too long")]
    VarintTooLong,
    #[error("invalid utf-8 in protobuf string")]
    InvalidUtf8,
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
    let mapping = MappedFile::open(path).map_err(|source| GeoRuntimeError::Map { path: path.to_path_buf(), source })?;
    let mapped_version = mapped_version(path, mapping.len());
    let reader = Reader::from_source(mapping)
        .map_err(|source| GeoRuntimeError::GeoipParse { path: path.to_path_buf(), source })?;
    let version = format!("{mapped_version}:{}:{}", reader.metadata.database_type, reader.metadata.build_epoch);
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
    let mapping = MappedFile::open(path).map_err(|source| GeoRuntimeError::Map { path: path.to_path_buf(), source })?;
    let categories = parse_geosite_catalog(mapping.as_slice())
        .map_err(|source| GeoRuntimeError::GeositeParse { path: path.to_path_buf(), source })?;
    let version = mapped_version(path, mapping.len());
    Ok(Some(GeositeDatabase { mapping, version, categories }))
}

fn mapped_version(path: &Path, len: usize) -> String {
    format!("{}:{len}", path.file_name().and_then(|name| name.to_str()).unwrap_or("db"))
}

fn parse_geosite_catalog(input: &[u8]) -> Result<HashMap<String, Vec<GeositeDomainRule>>, GeositeParseError> {
    let mut cursor = ProtoCursor::new(input);
    let mut categories = HashMap::new();
    while !cursor.is_empty() {
        let tag = cursor.varint()?;
        let field = tag >> PROTO_TAG_SHIFT;
        let wire = (tag & PROTO_WIRE_MASK) as u8;
        if field == GEOSITE_CATALOG_ENTRY_FIELD {
            let entry = cursor.length_delimited(wire)?;
            let (category, domains) = parse_geosite_entry(entry)?;
            categories.insert(category.to_ascii_lowercase(), domains);
        } else {
            cursor.skip(wire)?;
        }
    }
    Ok(categories)
}

fn parse_geosite_entry(input: &[u8]) -> Result<(String, Vec<GeositeDomainRule>), GeositeParseError> {
    let mut cursor = ProtoCursor::new(input);
    let mut country_code = String::new();
    let mut domains = Vec::new();
    while !cursor.is_empty() {
        let tag = cursor.varint()?;
        let field = tag >> PROTO_TAG_SHIFT;
        let wire = (tag & PROTO_WIRE_MASK) as u8;
        match field {
            GEOSITE_ENTRY_COUNTRY_CODE_FIELD => {
                country_code = cursor.string(wire)?;
            }
            GEOSITE_ENTRY_DOMAIN_FIELD => {
                domains.push(parse_geosite_domain(cursor.length_delimited(wire)?)?);
            }
            _ => cursor.skip(wire)?,
        }
    }
    Ok((country_code, domains))
}

fn parse_geosite_domain(input: &[u8]) -> Result<GeositeDomainRule, GeositeParseError> {
    let mut cursor = ProtoCursor::new(input);
    let mut kind = GeositeDomainKind::Plain;
    let mut value = String::new();
    while !cursor.is_empty() {
        let tag = cursor.varint()?;
        let field = tag >> PROTO_TAG_SHIFT;
        let wire = (tag & PROTO_WIRE_MASK) as u8;
        match field {
            GEOSITE_DOMAIN_TYPE_FIELD => {
                kind = match cursor.expect_varint(wire)? {
                    1 => GeositeDomainKind::Regex,
                    2 => GeositeDomainKind::RootDomain,
                    3 => GeositeDomainKind::Full,
                    _ => GeositeDomainKind::Plain,
                };
            }
            GEOSITE_DOMAIN_VALUE_FIELD => {
                value = cursor.string(wire)?;
            }
            _ => cursor.skip(wire)?,
        }
    }
    Ok(GeositeDomainRule { kind, value })
}

struct ProtoCursor<'a> {
    input: &'a [u8],
    offset: usize,
}

impl<'a> ProtoCursor<'a> {
    fn new(input: &'a [u8]) -> Self {
        Self { input, offset: 0 }
    }

    fn is_empty(&self) -> bool {
        self.offset >= self.input.len()
    }

    fn varint(&mut self) -> Result<u64, GeositeParseError> {
        let mut value = 0_u64;
        for shift in (0..64).step_by(7) {
            let byte = *self.input.get(self.offset).ok_or(GeositeParseError::UnexpectedEof)?;
            self.offset += 1;
            value |= u64::from(byte & VARINT_VALUE_MASK) << shift;
            if byte & VARINT_CONTINUATION_MASK == 0 {
                return Ok(value);
            }
        }
        Err(GeositeParseError::VarintTooLong)
    }

    fn expect_varint(&mut self, wire: u8) -> Result<u64, GeositeParseError> {
        if wire != WIRE_VARINT {
            return Err(GeositeParseError::UnsupportedWireType(wire));
        }
        self.varint()
    }

    fn string(&mut self, wire: u8) -> Result<String, GeositeParseError> {
        std::str::from_utf8(self.length_delimited(wire)?).map(str::to_owned).map_err(|_| GeositeParseError::InvalidUtf8)
    }

    fn length_delimited(&mut self, wire: u8) -> Result<&'a [u8], GeositeParseError> {
        if wire != WIRE_LENGTH_DELIMITED {
            return Err(GeositeParseError::UnsupportedWireType(wire));
        }
        let len = usize::try_from(self.varint()?).map_err(|_| GeositeParseError::VarintTooLong)?;
        let end = self.offset.checked_add(len).ok_or(GeositeParseError::UnexpectedEof)?;
        let slice = self.input.get(self.offset..end).ok_or(GeositeParseError::UnexpectedEof)?;
        self.offset = end;
        Ok(slice)
    }

    fn skip(&mut self, wire: u8) -> Result<(), GeositeParseError> {
        match wire {
            WIRE_VARINT => {
                self.varint()?;
                Ok(())
            }
            WIRE_LENGTH_DELIMITED => {
                self.length_delimited(wire)?;
                Ok(())
            }
            _ => Err(GeositeParseError::UnsupportedWireType(wire)),
        }
    }
}

const GEOSITE_CATALOG_ENTRY_FIELD: u64 = 1;
const GEOSITE_ENTRY_COUNTRY_CODE_FIELD: u64 = 1;
const GEOSITE_ENTRY_DOMAIN_FIELD: u64 = 2;
const GEOSITE_DOMAIN_TYPE_FIELD: u64 = 1;
const GEOSITE_DOMAIN_VALUE_FIELD: u64 = 2;
const PROTO_TAG_SHIFT: u64 = 3;
const PROTO_WIRE_MASK: u64 = 0x7;
const WIRE_VARINT: u8 = 0;
const WIRE_LENGTH_DELIMITED: u8 = 2;
const VARINT_VALUE_MASK: u8 = 0x7f;
const VARINT_CONTINUATION_MASK: u8 = 0x80;

#[cfg(test)]
mod tests {
    use std::fs;
    use std::net::IpAddr;
    use std::time::{SystemTime, UNIX_EPOCH};

    use super::*;

    const GEOIP_COUNTRY_FIXTURE: &[u8] = include_bytes!("../tests/fixtures/GeoIP2-Country-Test.mmdb");

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
        assert_eq!(
            Some(vec![GeositeDomainRule { kind: GeositeDomainKind::RootDomain, value: "example.ru".to_owned() }]),
            result.runtime.geosite_category("ru").map(|rules| vec![rules[0].clone()])
        );
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

    fn paths(dir: &Path) -> GeoRuntimePaths {
        GeoRuntimePaths { geoip_db: dir.join("geoip.db"), geosite_db: dir.join("geosite.db") }
    }

    fn temp_dir() -> PathBuf {
        let nanos = SystemTime::now().duration_since(UNIX_EPOCH).expect("clock").as_nanos();
        let dir = std::env::temp_dir().join(format!("ripdpi-geo-{nanos}"));
        fs::create_dir_all(&dir).expect("create temp dir");
        dir
    }

    fn write_geoip_fixture(dir: &Path) {
        fs::write(dir.join("geoip.db"), GEOIP_COUNTRY_FIXTURE).expect("write geoip fixture");
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
        varint(out, (field << PROTO_TAG_SHIFT) | u64::from(WIRE_LENGTH_DELIMITED));
        varint(out, value.len() as u64);
        out.extend_from_slice(value);
    }

    fn field_varint(out: &mut Vec<u8>, field: u64, value: u64) {
        varint(out, (field << PROTO_TAG_SHIFT) | u64::from(WIRE_VARINT));
        varint(out, value);
    }

    fn varint(out: &mut Vec<u8>, mut value: u64) {
        while value >= u64::from(VARINT_CONTINUATION_MASK) {
            out.push(((value as u8) & VARINT_VALUE_MASK) | VARINT_CONTINUATION_MASK);
            value >>= 7;
        }
        out.push(value as u8);
    }
}
