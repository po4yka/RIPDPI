use std::sync::LazyLock;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlockpageFingerprint {
    pub name: String,
    pub location: FingerprintLocation,
    pub pattern_type: PatternType,
    pub pattern: String,
    pub scope: String,
    pub confidence: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FingerprintLocation {
    Body,
    Header(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PatternType {
    Full,
    Prefix,
    Contains,
}

static BUNDLED_BLOCKPAGE_FINGERPRINTS: LazyLock<Vec<BlockpageFingerprint>> =
    LazyLock::new(|| load_blockpage_fingerprints_from_csv(include_str!("../../blockpage_fingerprints.csv")));

pub fn bundled_blockpage_fingerprints() -> &'static [BlockpageFingerprint] {
    BUNDLED_BLOCKPAGE_FINGERPRINTS.as_slice()
}

pub fn load_blockpage_fingerprints() -> Vec<BlockpageFingerprint> {
    bundled_blockpage_fingerprints().to_vec()
}

pub fn load_blockpage_fingerprints_from_csv(csv: &str) -> Vec<BlockpageFingerprint> {
    csv.lines().skip(1).filter(|line| !line.trim().is_empty()).filter_map(parse_fingerprint_line).collect()
}

pub(crate) fn parse_fingerprint_line(line: &str) -> Option<BlockpageFingerprint> {
    let parts: Vec<&str> = line.splitn(6, ',').collect();
    if parts.len() < 6 {
        return None;
    }
    let name = parts[0].trim();
    let location_field = parts[1].trim();
    let pattern_type_field = parts[2].trim();
    let pattern = parts[3].trim();
    let scope = parts[4].trim();
    let confidence = parts[5].trim();

    if name.is_empty() || pattern.is_empty() || scope.is_empty() || confidence.is_empty() {
        return None;
    }

    let location = if location_field == "body" {
        FingerprintLocation::Body
    } else {
        let header = location_field.strip_prefix("header.")?;
        let header = header.trim();
        if header.is_empty() {
            return None;
        }
        FingerprintLocation::Header(header.to_ascii_lowercase())
    };
    let pattern_type = match pattern_type_field {
        "full" => PatternType::Full,
        "prefix" => PatternType::Prefix,
        "contains" => PatternType::Contains,
        _ => return None,
    };
    Some(BlockpageFingerprint {
        name: name.to_string(),
        location,
        pattern_type,
        pattern: pattern.to_string(),
        scope: scope.to_string(),
        confidence: confidence.to_string(),
    })
}
