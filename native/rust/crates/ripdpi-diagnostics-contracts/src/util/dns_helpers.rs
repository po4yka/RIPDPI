use std::collections::BTreeSet;
use std::net::{IpAddr, SocketAddr};

/// Resolver label, HTTPS host, and JSON API path. These are resolver
/// infrastructure endpoints, not domains selected for measurement.
pub const DEFAULT_DOH_JSON_RESOLVERS: &[(&str, &str, &str)] = &[
    ("google", "dns.google", "/resolve"),
    ("cloudflare", "cloudflare-dns.com", "/dns-query"),
    ("adguard", "dns.adguard-dns.com", "/resolve"),
];

/// Encode a scan target as one JSON DoH `name` query value.
pub fn encode_doh_json_query_name(domain: &str) -> Option<String> {
    if domain.is_empty() || domain.len() > 253 {
        return None;
    }
    let mut encoded = String::with_capacity(domain.len());
    for byte in domain.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b'~') {
            encoded.push(char::from(byte));
        } else {
            encoded.push_str(&format!("%{byte:02X}"));
        }
    }
    Some(encoded)
}

/// DNS result from a validated vendor JSON response.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DohJsonResponse {
    /// The requested address record was present.
    Answer(IpAddr),
    /// NOERROR without an address of the requested type.
    NoData,
    /// The queried name does not exist (RCODE 3).
    NxDomain,
    /// The resolver returned another DNS error.
    DnsError {
        /// DNS response code, including its EDNS extension when present.
        rcode: u16,
    },
}

/// Read a vendor DNS JSON answer for the expected A or AAAA question.
/// DNS response codes remain distinct from a malformed response.
pub fn parse_doh_json_response(
    body: &[u8],
    expected_name: &str,
    expected_type: u16,
) -> Result<DohJsonResponse, &'static str> {
    let value: serde_json::Value = serde_json::from_slice(body).map_err(|_| "malformed DoH JSON")?;
    let object = value.as_object().ok_or("DoH response is not an object")?;
    validate_doh_json_question(object, expected_name, expected_type)?;
    let status = object
        .get("Status")
        .or_else(|| object.get("status"))
        .and_then(serde_json::Value::as_u64)
        .and_then(|value| u16::try_from(value).ok())
        .filter(|value| *value <= 4095)
        .ok_or("missing or invalid DoH Status")?;
    let records = match object.get("Answer").or_else(|| object.get("answer")) {
        Some(answers) => answers
            .as_array()
            .ok_or("invalid DoH Answer")?
            .iter()
            .map(doh_json_record)
            .collect::<Result<Vec<_>, _>>()?,
        None => Vec::new(),
    };
    if status == 3 {
        return Ok(DohJsonResponse::NxDomain);
    }
    if status != 0 {
        return Ok(DohJsonResponse::DnsError { rcode: status });
    }
    let mut owner = normalized_dns_name(expected_name);
    let mut visited = BTreeSet::new();
    loop {
        if !visited.insert(owner.clone()) {
            return Err("cyclic DoH CNAME chain");
        }
        let mut next = None;
        for (name, record_type, data, _) in &records {
            if *record_type == 5 && *name == owner {
                let target = normalized_dns_name(data);
                if next.as_ref().is_some_and(|value| *value != target) {
                    return Err("invalid DoH CNAME chain");
                }
                next = Some(target);
            }
        }
        match next {
            Some(target) => owner = target,
            None => break,
        }
    }
    let mut address = None;
    for (name, record_type, _, ip) in records {
        let Some(ip) = ip else { continue };
        if record_type != expected_type || name != owner {
            return Err("unrelated DoH address record");
        }
        address.get_or_insert(ip);
    }
    Ok(address.map_or(DohJsonResponse::NoData, DohJsonResponse::Answer))
}

fn validate_doh_json_question(
    object: &serde_json::Map<String, serde_json::Value>,
    expected_name: &str,
    expected_type: u16,
) -> Result<(), &'static str> {
    validate_dns_json_name(expected_name)?;
    if !matches!(expected_type, 1 | 28) {
        return Err("invalid DoH question");
    }
    if object.get("TC").is_some_and(|value| value.as_bool() != Some(false)) {
        return Err("truncated or invalid DoH response");
    }
    let questions = object
        .get("Question")
        .or_else(|| object.get("question"))
        .and_then(serde_json::Value::as_array)
        .ok_or("missing or invalid DoH Question")?;
    let [question] = questions.as_slice() else { return Err("invalid DoH question count") };
    let name = question.get("name").and_then(serde_json::Value::as_str).ok_or("missing DoH question name")?;
    validate_dns_json_name(name)?;
    if normalized_dns_name(name) != normalized_dns_name(expected_name)
        || question.get("type").and_then(serde_json::Value::as_u64) != Some(u64::from(expected_type))
    {
        return Err("DoH question does not match query");
    }
    Ok(())
}

fn doh_json_record(value: &serde_json::Value) -> Result<(String, u16, &str, Option<IpAddr>), &'static str> {
    let name = value.get("name").and_then(serde_json::Value::as_str).ok_or("missing DoH record name")?;
    validate_dns_json_name(name)?;
    let name = normalized_dns_name(name);
    let record_type = value
        .get("type")
        .and_then(serde_json::Value::as_u64)
        .and_then(|value| u16::try_from(value).ok())
        .filter(|value| *value != 0)
        .ok_or("missing or invalid DoH record type")?;
    let data = value.get("data").and_then(serde_json::Value::as_str).ok_or("missing DoH record data")?;
    if record_type == 5 {
        validate_dns_json_name(data)?;
    }
    let address = if matches!(record_type, 1 | 28) {
        let ip = data.parse::<IpAddr>().map_err(|_| "invalid DoH address")?;
        if (record_type == 1) != ip.is_ipv4() {
            return Err("invalid DoH address family");
        }
        Some(ip)
    } else {
        None
    };
    Ok((name, record_type, data, address))
}

fn validate_dns_json_name(name: &str) -> Result<(), &'static str> {
    if name == "." {
        return Ok(());
    }
    let name = name.strip_suffix('.').unwrap_or(name);
    if name.is_empty()
        || name.len() > 253
        || name.chars().any(char::is_control)
        || name.split('.').any(|label| label.is_empty() || label.len() > 63)
    {
        return Err("invalid DoH DNS name");
    }
    Ok(())
}

fn normalized_dns_name(name: &str) -> String {
    name.strip_suffix('.').unwrap_or(name).to_ascii_lowercase()
}

pub fn ip_set(values: &[String]) -> BTreeSet<String> {
    values.iter().cloned().collect()
}

pub fn ipv4_prefix_24(value: &str) -> Option<[u8; 3]> {
    match value.parse::<IpAddr>().ok()? {
        IpAddr::V4(addr) => {
            let octets = addr.octets();
            Some([octets[0], octets[1], octets[2]])
        }
        IpAddr::V6(_) => None,
    }
}

pub fn ipv6_prefix_48(value: &str) -> Option<[u16; 3]> {
    match value.parse::<IpAddr>().ok()? {
        IpAddr::V4(_) => None,
        IpAddr::V6(addr) => {
            let segments = addr.segments();
            Some([segments[0], segments[1], segments[2]])
        }
    }
}

/// True when an IP belongs to a bogon / sinkhole / private space that cannot
/// legitimately answer for a public domain.
pub fn looks_like_sinkhole(value: &str) -> bool {
    match value.parse::<IpAddr>() {
        Ok(IpAddr::V4(addr)) => {
            addr.is_unspecified()
                || addr.is_loopback()
                || addr.is_link_local()
                || addr.is_broadcast()
                || addr.is_private()
                || addr.is_documentation()
                || addr.is_multicast()
        }
        Ok(IpAddr::V6(addr)) => {
            let first_segment = addr.segments()[0];
            let is_unique_local = (first_segment & 0xfe00) == 0xfc00;
            let is_link_local = (first_segment & 0xffc0) == 0xfe80;
            addr.is_unspecified() || addr.is_loopback() || addr.is_multicast() || is_unique_local || is_link_local
        }
        Err(_) => true,
    }
}

/// Outcome of comparing UDP and encrypted DNS answer sets. Distinguishes
/// legitimate anycast divergence (separate public IPs from the same CDN) from
/// unexpected sinkhole/private addresses, including mixed overlapping answer sets.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsAnswerOverlap {
    /// Any shared IP or shared /24 (v4) / /48 (v6) prefix.
    Match,
    /// Disjoint answers but both sides are plausibly public.
    CompatibleDivergence,
    /// An unmatched answer contains a sinkhole / private IP.
    SinkholeSubstitution,
}

pub fn classify_dns_answer_overlap(udp: &[String], encrypted: &[String]) -> DnsAnswerOverlap {
    let udp_set = ip_set(udp);
    let enc_set = ip_set(encrypted);
    if udp_set.symmetric_difference(&enc_set).any(|ip| looks_like_sinkhole(ip)) {
        return DnsAnswerOverlap::SinkholeSubstitution;
    }
    if !udp_set.is_disjoint(&enc_set) {
        return DnsAnswerOverlap::Match;
    }
    let udp_v4: BTreeSet<[u8; 3]> = udp_set.iter().filter_map(|ip| ipv4_prefix_24(ip)).collect();
    let enc_v4: BTreeSet<[u8; 3]> = enc_set.iter().filter_map(|ip| ipv4_prefix_24(ip)).collect();
    if !udp_v4.is_disjoint(&enc_v4) {
        return DnsAnswerOverlap::Match;
    }
    let udp_v6: BTreeSet<[u16; 3]> = udp_set.iter().filter_map(|ip| ipv6_prefix_48(ip)).collect();
    let enc_v6: BTreeSet<[u16; 3]> = enc_set.iter().filter_map(|ip| ipv6_prefix_48(ip)).collect();
    if !udp_v6.is_disjoint(&enc_v6) {
        return DnsAnswerOverlap::Match;
    }
    DnsAnswerOverlap::CompatibleDivergence
}

pub fn is_suspected_dns_tampering_outcome(outcome: &str) -> bool {
    matches!(outcome, "dns_suspicious_divergence" | "dns_sinkhole_substitution" | "dns_nxdomain_mismatch")
}

pub fn format_result_set(result: &Result<Vec<String>, String>) -> String {
    match result {
        Ok(values) => values.join("|"),
        Err(err) => format!("error:{err}"),
    }
}

pub fn format_socket_result(result: &Result<Vec<SocketAddr>, String>) -> String {
    match result {
        Ok(values) => values.iter().map(SocketAddr::to_string).collect::<Vec<_>>().join("|"),
        Err(err) => format!("error:{err}"),
    }
}

#[cfg(test)]
mod doh_json_tests {
    use super::{DohJsonResponse, encode_doh_json_query_name, parse_doh_json_response};

    fn parse_answer(body: &[u8]) -> Result<DohJsonResponse, &'static str> {
        parse_doh_json_response(body, "example.com", 1)
    }

    #[test]
    fn query_name_encoding_keeps_untrusted_characters_in_one_parameter() {
        assert_eq!(encode_doh_json_query_name("a&b\r\n.example"), Some("a%26b%0D%0A.example".to_string()));
        assert_eq!(encode_doh_json_query_name(""), None);
        assert_eq!(encode_doh_json_query_name(&"x".repeat(254)), None);
    }

    #[test]
    fn rejects_answers_for_another_question_or_record_type() {
        for body in [
            br#"{"Status":0,"Question":[{"name":"other.example.","type":1}],"Answer":[{"name":"other.example.","type":1,"data":"192.0.2.9"}]}"#.as_slice(),
            br#"{"Status":0,"Question":[{"name":"example.com.","type":28}],"Answer":[{"name":"example.com.","type":28,"data":"2001:db8::9"}]}"#.as_slice(),
            br#"{"Status":0,"Question":[{"name":"example.com.","type":1}],"Answer":[{"name":"example.com.","data":"192.0.2.9"}]}"#.as_slice(),
        ] {
            assert!(parse_answer(body).is_err());
        }
    }

    #[test]
    fn rejects_disconnected_or_malformed_address_records() {
        for body in [
            br#"{"Status":0,"Question":[{"name":"example.com.","type":1}],"Answer":[{"name":"other.example.","type":1,"data":"192.0.2.9"}]}"#.as_slice(),
            br#"{"Status":0,"Question":[{"name":"example.com.","type":1}],"Answer":[{"name":"example.com.","type":1,"data":"2001:db8::9"}]}"#.as_slice(),
            br#"{"Status":0,"Question":[{"name":"example.com.","type":1}],"Answer":[{"name":"example.com.","type":1,"data":"invalid"}]}"#.as_slice(),
        ] {
            assert!(parse_answer(body).is_err());
        }
    }

    #[test]
    fn rejects_truncation_and_cname_cycles() {
        for body in [
            br#"{"Status":0,"TC":true,"Question":[{"name":"example.com.","type":1}],"Answer":[{"name":"example.com.","type":1,"data":"192.0.2.9"}]}"#.as_slice(),
            br#"{"Status":0,"Question":[{"name":"example.com.","type":1}],"Answer":[{"name":"example.com.","type":5,"data":"alias.example."},{"name":"alias.example.","type":5,"data":"example.com."}]}"#.as_slice(),
        ] {
            assert!(parse_answer(body).is_err());
        }
    }

    #[test]
    fn rejects_impossible_dns_presentation_names() {
        for name in [
            "bad..example.".to_string(),
            ".example.".to_string(),
            "bad\u{7f}.example.".to_string(),
            format!("{}.example.", "x".repeat(64)),
            format!("{0}.{0}.{0}.{0}.", "x".repeat(63)),
        ] {
            let alias = serde_json::json!({
                "Status": 0,
                "Question": [{"name": "example.com.", "type": 1}],
                "Answer": [
                    {"name": "example.com.", "type": 5, "data": name},
                    {"name": name, "type": 1, "data": "192.0.2.7"}
                ]
            });
            assert!(parse_answer(&serde_json::to_vec(&alias).unwrap()).is_err(), "accepted alias {name:?}");
            let question = serde_json::json!({
                "Status": 0,
                "Question": [{"name": name, "type": 1}],
                "Answer": [{"name": name, "type": 1, "data": "192.0.2.7"}]
            });
            assert!(parse_doh_json_response(&serde_json::to_vec(&question).unwrap(), &name, 1).is_err());
        }
    }

    #[test]
    fn follows_out_of_order_cname_chain_with_dns_name_equivalence() {
        let body = br#"{"Status":0,"TC":false,"Question":[{"name":"EXAMPLE.COM.","type":1}],"Answer":[{"name":"final.example.","type":1,"data":"192.0.2.7"},{"name":"Alias_&.Example","type":5,"data":"FINAL.example."},{"name":"example.com","type":5,"data":"alias_&.example."}]}"#;
        assert_eq!(parse_answer(body), Ok(DohJsonResponse::Answer("192.0.2.7".parse().unwrap())));
    }

    #[test]
    fn accepts_top_level_status_and_a_or_aaaa_answer() {
        assert_eq!(
            parse_answer(br#"{"Status":0,"Answer":[{"type":1,"data":"203.0.113.7","name":"example.com."}],"Question":[{"name":"example.com.","type":1}]}"#)
                .unwrap(),
            DohJsonResponse::Answer("203.0.113.7".parse().unwrap())
        );
        assert_eq!(
            parse_doh_json_response(br#"{"Status":0,"Answer":[{"type":28,"data":"2001:db8::1","name":"example.com."}],"Question":[{"name":"example.com.","type":28}]}"#, "example.com", 28)
                .unwrap(),
            DohJsonResponse::Answer("2001:db8::1".parse().unwrap())
        );
        assert_eq!(
            parse_answer(br#"{"Status":3,"Answer":[],"Question":[{"name":"example.com.","type":1}]}"#),
            Ok(DohJsonResponse::NxDomain)
        );
        assert_eq!(parse_answer(br#"{"Status":0,"Answer":[{"type":5,"data":"192.0.2.1","name":"example.com."}],"Question":[{"name":"example.com.","type":1}]}"#), Ok(DohJsonResponse::NoData));
        assert!(parse_answer(br#"{"Status":0,"Answer":[{"type":1,"data":"2001:db8::1","name":"example.com."}],"Question":[{"name":"example.com.","type":1}]}"#).is_err());
    }

    #[test]
    fn ignores_nested_status_and_data_outside_answer() {
        assert_eq!(
            parse_answer(
                br#"{"Status":0,"Meta":{"Status":3,"data":"192.0.2.1"},"Answer":[{"type":1,"data":"203.0.113.7","name":"example.com."}],"Question":[{"name":"example.com.","type":1}]}"#
            ),
            Ok(DohJsonResponse::Answer("203.0.113.7".parse().unwrap()))
        );
        assert!(parse_answer(br#"{"Meta":{"Status":0},"Answer":[{"data":"192.0.2.1","name":"example.com."}],"Question":[{"name":"example.com.","type":1}]}"#).is_err());
        assert_eq!(
            parse_answer(br#"{"Status":0,"data":"192.0.2.1","Question":[{"name":"example.com.","type":1}]}"#),
            Ok(DohJsonResponse::NoData)
        );
    }

    #[test]
    fn distinguishes_dns_responses_from_malformed_json() {
        for (status, expected) in [
            (0, DohJsonResponse::NoData),
            (3, DohJsonResponse::NxDomain),
            (2, DohJsonResponse::DnsError { rcode: 2 }),
            (5, DohJsonResponse::DnsError { rcode: 5 }),
            (16, DohJsonResponse::DnsError { rcode: 16 }),
        ] {
            let body = serde_json::json!({"Status": status, "Question": [{"name": "example.com.", "type": 1}]});
            assert_eq!(parse_answer(&serde_json::to_vec(&body).unwrap()), Ok(expected));
        }
        for status in [0, 2, 3] {
            let body =
                serde_json::json!({"Status": status, "Question": [{"name": "example.com.", "type": 1}], "Answer": {}});
            assert!(parse_answer(&serde_json::to_vec(&body).unwrap()).is_err());
            for (record_type, data) in [(1, "not-an-IP"), (1, "2001:db8::1"), (28, "192.0.2.7")] {
                let body = serde_json::json!({
                    "Status": status,
                    "Question": [{"name": "example.com.", "type": 1}],
                    "Answer": [{"name": "example.com.", "type": record_type, "data": data}]
                });
                assert!(parse_answer(&serde_json::to_vec(&body).unwrap()).is_err());
            }
        }
        assert!(parse_answer(br#"{"Status":0,"Answer":[{"data":"192.0.2.1"}"#).is_err());
        for status in [serde_json::json!("0"), serde_json::json!(-1), serde_json::json!(4096)] {
            let body = serde_json::json!({"Status": status, "Question": [{"name": "example.com.", "type": 1}]});
            assert!(parse_answer(&serde_json::to_vec(&body).unwrap()).is_err());
        }
    }
}
