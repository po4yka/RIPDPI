use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use jni::objects::JString;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};
use ripdpi_packets::{
    QUIC_V1_VERSION, QuicInitialBrowserProfile, QuicInitialPacketLayout, build_browser_like_quic_initial,
    build_generic_quic_initial, build_probe_quic_initial, packetize_quic_initial, parse_quic_initial_seed,
    tamper_quic_version,
};
use serde::{Deserialize, Serialize};

const RESERVED_VERSION: u32 = 0x1a2a_3a4a;

pub fn create_entry(mut env: EnvUnowned<'_>, request_json: JString<'_>) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let request_json: String = request_json.mutf8_chars(env)?.to_str().into_owned();
            let payload = create(&request_json);
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

fn create(request_json: &str) -> String {
    let request = match serde_json::from_str::<QuicInitialRequest>(request_json) {
        Ok(request) => request,
        Err(error) => return response_error(format!("invalid QUIC Initial request: {error}")),
    };
    let Some(packet) = build_packet(&request) else {
        return response_error("build QUIC Initial packet failed");
    };
    serde_json::to_string(&QuicInitialResponse { packet_base64: Some(STANDARD.encode(packet)), error: None })
        .unwrap_or_else(|error| response_error(format!("serialize QUIC Initial response: {error}")))
}

/// Build an active-probe Initial. Every profile gets fresh connection IDs
/// (RFC 9000 section 7.2: the client's first DCID is unpredictable), so the
/// packet is not a constant a censor can match; only the ClientHello profile
/// stays fixed.
fn build_packet(request: &QuicInitialRequest) -> Option<Vec<u8>> {
    let target = request.target.trim();
    let host = (!target.is_empty()).then_some(target);
    match request.fingerprint.as_str() {
        "chrome120" => build_probe_quic_initial(QUIC_V1_VERSION, host),
        "firefox121" => with_fresh_connection_ids(
            &build_browser_like_quic_initial(QUIC_V1_VERSION, host, QuicInitialBrowserProfile::FirefoxAndroid)?,
            host,
        ),
        "generic_v1" => with_fresh_connection_ids(&build_generic_quic_initial(QUIC_V1_VERSION, host)?, host),
        "vn_probe" => build_probe_quic_initial(QUIC_V1_VERSION, host)
            .and_then(|packet| tamper_quic_version(&packet, RESERVED_VERSION)),
        _ => None,
    }
}

/// Re-packetize `packet` with the random DCID and SCID of a fresh probe
/// Initial. `build_probe_quic_initial` draws them from ring's CSPRNG; this
/// crate has no RNG dependency of its own.
fn with_fresh_connection_ids(packet: &[u8], host: Option<&str>) -> Option<Vec<u8>> {
    let fresh = parse_quic_initial_seed(&build_probe_quic_initial(QUIC_V1_VERSION, host)?)?;
    let mut seed = parse_quic_initial_seed(packet)?;
    seed.dcid = fresh.dcid;
    seed.scid = fresh.scid;
    packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(packet.len()))
}

fn response_error(error: impl Into<String>) -> String {
    serde_json::to_string(&QuicInitialResponse { packet_base64: None, error: Some(error.into()) })
        .unwrap_or_else(|_| "{\"error\":\"serialize QUIC Initial error\"}".to_string())
}

#[derive(Debug, Deserialize)]
struct QuicInitialRequest {
    fingerprint: String,
    target: String,
}

#[derive(Debug, Serialize)]
struct QuicInitialResponse {
    #[serde(rename = "packetBase64")]
    packet_base64: Option<String>,
    error: Option<String>,
}

#[cfg(test)]
mod tests {
    use serde_json::Value;

    use super::*;

    #[test]
    fn chrome_request_returns_v1_initial() {
        let response = create(r#"{"fingerprint":"chrome120","target":"cloudflare.com"}"#);
        let value: Value = serde_json::from_str(&response).expect("response json");
        assert_eq!(value.get("error").and_then(Value::as_str), None);
        let packet = decode_packet(&value);
        assert_eq!(&packet[1..5], &QUIC_V1_VERSION.to_be_bytes());
    }

    #[test]
    fn firefox_request_returns_v1_initial() {
        let response = create(r#"{"fingerprint":"firefox121","target":"cloudflare.com"}"#);
        let value: Value = serde_json::from_str(&response).expect("response json");
        assert_eq!(value.get("error").and_then(Value::as_str), None);
        let packet = decode_packet(&value);
        assert_eq!(&packet[1..5], &QUIC_V1_VERSION.to_be_bytes());
    }

    #[test]
    fn generic_request_returns_distinct_v1_initial() {
        let chrome = create(r#"{"fingerprint":"chrome120","target":"cloudflare.com"}"#);
        let generic = create(r#"{"fingerprint":"generic_v1","target":"cloudflare.com"}"#);
        let chrome_packet = decode_packet(&serde_json::from_str(&chrome).expect("chrome response json"));
        let generic_packet = decode_packet(&serde_json::from_str(&generic).expect("generic response json"));

        assert_eq!(&generic_packet[1..5], &QUIC_V1_VERSION.to_be_bytes());
        assert_ne!(chrome_packet, generic_packet);
    }

    #[test]
    fn vn_probe_uses_reserved_version() {
        let response = create(r#"{"fingerprint":"vn_probe","target":"cloudflare.com"}"#);
        let value: Value = serde_json::from_str(&response).expect("response json");
        assert_eq!(value.get("error").and_then(Value::as_str), None);
        let packet = decode_packet(&value);
        assert_eq!(&packet[1..5], &RESERVED_VERSION.to_be_bytes());
    }

    /// Connection IDs from the long header: (DCID, SCID).
    fn connection_ids(packet: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let dcid_len = packet[5] as usize;
        let dcid = packet[6..6 + dcid_len].to_vec();
        let scid_len = packet[6 + dcid_len] as usize;
        let scid = packet[7 + dcid_len..7 + dcid_len + scid_len].to_vec();
        (dcid, scid)
    }

    #[test]
    fn every_fingerprint_uses_fresh_connection_ids() {
        // RFC 9001 Appendix A.2 test-vector DCID, formerly sent by every probe.
        const RFC_VECTOR_DCID: [u8; 8] = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
        for fingerprint in ["chrome120", "firefox121", "generic_v1", "vn_probe"] {
            let request = format!(r#"{{"fingerprint":"{fingerprint}","target":"cloudflare.com"}}"#);
            let first = decode_packet(&serde_json::from_str(&create(&request)).expect("first response"));
            let second = decode_packet(&serde_json::from_str(&create(&request)).expect("second response"));
            let (first_dcid, first_scid) = connection_ids(&first);
            let (second_dcid, second_scid) = connection_ids(&second);

            assert!(first_dcid.len() >= 8, "{fingerprint}: DCID shorter than 8 bytes");
            assert_ne!(first_dcid, RFC_VECTOR_DCID, "{fingerprint}: fixed RFC test-vector DCID");
            assert_ne!(first_dcid, second_dcid, "{fingerprint}: DCID repeats across calls");
            assert_ne!(first_scid, second_scid, "{fingerprint}: SCID repeats across calls");
            assert_eq!(first.len(), 1200, "{fingerprint}: Initial must stay padded to 1200 bytes");
        }
    }

    fn decode_packet(value: &Value) -> Vec<u8> {
        STANDARD.decode(value.get("packetBase64").and_then(Value::as_str).expect("packet body")).expect("packet base64")
    }
}
