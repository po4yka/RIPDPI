use super::client_hello_parser::parse_client_hello;

fn join_decimal(values: &[u16]) -> String {
    values.iter().map(std::string::ToString::to_string).collect::<Vec<_>>().join("-")
}

/// Compute the JA3 fingerprint hash from recorded TLS handshake bytes.
///
/// Returns `None` if the bytes do not contain a valid ClientHello.
pub fn compute_ja3(recorded_bytes: &[u8]) -> Option<String> {
    let fields = parse_client_hello(recorded_bytes)?;
    let ja3_string = format!(
        "{},{},{},{},{}",
        fields.version,
        join_decimal(&fields.cipher_suites),
        join_decimal(&fields.extensions),
        join_decimal(&fields.supported_groups),
        join_decimal(&fields.ec_point_formats),
    );
    Some(format!("{:x}", md5::compute(ja3_string.as_bytes())))
}
