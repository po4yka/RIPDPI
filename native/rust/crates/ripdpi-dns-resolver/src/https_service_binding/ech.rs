use rustls::pki_types::DnsName;

use super::cursor::ByteCursor;
use super::dto::{EchCipherSuite, EchConfig, EchConfigEntry, EchExtension, HttpsSvcbParseError};

const ECH_CONFIG_VERSION_V18: u16 = 0xfe0d;

pub fn parse_ech_config_list(bytes: &[u8]) -> Result<EchConfig, HttpsSvcbParseError> {
    let mut cursor = ByteCursor::new(bytes);
    let total_len = usize::from(cursor.read_u16("ECHConfigList length")?);
    if total_len == 0 {
        return Err(HttpsSvcbParseError::EmptyEchConfigList);
    }
    let config_bytes = cursor.read_bytes(total_len, "ECHConfigList payload")?;
    cursor.expect_empty("ECHConfigList trailing bytes")?;

    let mut payload = ByteCursor::new(config_bytes);
    let mut configs = Vec::new();
    while !payload.is_empty() {
        let version = payload.read_u16("ECHConfig version")?;
        let contents_len = usize::from(payload.read_u16("ECHConfig contents length")?);
        let contents = payload.read_bytes(contents_len, "ECHConfig contents")?;
        configs.push(parse_ech_config_entry(version, contents)?);
    }

    if configs.is_empty() {
        return Err(HttpsSvcbParseError::EmptyEchConfigList);
    }

    Ok(EchConfig { raw_list_bytes: bytes.to_vec(), configs })
}

fn parse_ech_config_entry(version: u16, contents: &[u8]) -> Result<EchConfigEntry, HttpsSvcbParseError> {
    if version != ECH_CONFIG_VERSION_V18 {
        return Ok(EchConfigEntry {
            version,
            config_id: None,
            kem_id: None,
            public_key_len: None,
            maximum_name_length: None,
            public_name: None,
            cipher_suites: Vec::new(),
            extensions: Vec::new(),
            has_unknown_mandatory_extension: false,
            raw_contents: contents.to_vec(),
        });
    }

    let mut cursor = ByteCursor::new(contents);
    let config_id = cursor.read_u8("ECH config_id")?;
    let kem_id = cursor.read_u16("ECH kem_id")?;
    let public_key = cursor.read_vec_u16("ECH public key")?;
    if public_key.is_empty() {
        return Err(HttpsSvcbParseError::MalformedEchConfigList("ECH public key must not be empty".to_string()));
    }

    let cipher_suite_bytes = cursor.read_vec_u16("ECH cipher suites")?;
    if cipher_suite_bytes.len() < 4 || cipher_suite_bytes.len() % 4 != 0 {
        return Err(HttpsSvcbParseError::MalformedEchConfigList(
            "ECH cipher suites must contain whole 4-byte entries".to_string(),
        ));
    }
    let cipher_suites = parse_cipher_suites(cipher_suite_bytes)?;

    let maximum_name_length = cursor.read_u8("ECH maximum_name_length")?;
    let public_name_bytes = cursor.read_vec_u8("ECH public_name")?;
    let public_name = DnsName::try_from(public_name_bytes)
        .map_err(|error| HttpsSvcbParseError::MalformedEchConfigList(format!("invalid ECH public_name: {error}")))?
        .as_ref()
        .to_string();

    let extensions_bytes = cursor.read_vec_u16("ECH extensions")?;
    let extensions = parse_extensions(extensions_bytes)?;
    cursor.expect_empty("ECHConfig contents trailing bytes")?;

    Ok(EchConfigEntry {
        version,
        config_id: Some(config_id),
        kem_id: Some(kem_id),
        public_key_len: Some(public_key.len()),
        maximum_name_length: Some(maximum_name_length),
        public_name: Some(public_name),
        cipher_suites,
        has_unknown_mandatory_extension: extensions.iter().any(|extension| extension.extension_type & 0x8000 != 0),
        extensions,
        raw_contents: contents.to_vec(),
    })
}

fn parse_cipher_suites(bytes: &[u8]) -> Result<Vec<EchCipherSuite>, HttpsSvcbParseError> {
    let mut cursor = ByteCursor::new(bytes);
    let mut suites = Vec::new();
    while !cursor.is_empty() {
        suites.push(EchCipherSuite {
            kdf_id: cursor.read_u16("ECH cipher suite KDF")?,
            aead_id: cursor.read_u16("ECH cipher suite AEAD")?,
        });
    }
    Ok(suites)
}

fn parse_extensions(bytes: &[u8]) -> Result<Vec<EchExtension>, HttpsSvcbParseError> {
    let mut cursor = ByteCursor::new(bytes);
    let mut extensions = Vec::new();
    while !cursor.is_empty() {
        let extension_type = cursor.read_u16("ECH extension type")?;
        let data_len = usize::from(cursor.read_u16("ECH extension data length")?);
        let _ = cursor.read_bytes(data_len, "ECH extension data")?;
        extensions.push(EchExtension { extension_type, data_len });
    }
    Ok(extensions)
}
