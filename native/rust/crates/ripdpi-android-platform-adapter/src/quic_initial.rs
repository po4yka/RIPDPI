use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use jni::objects::JString;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};
use ripdpi_packets::{
    build_browser_like_quic_initial, build_realistic_quic_initial, tamper_quic_version, QuicInitialBrowserProfile,
    QUIC_V1_VERSION,
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

fn build_packet(request: &QuicInitialRequest) -> Option<Vec<u8>> {
    let target = request.target.trim();
    let host = (!target.is_empty()).then_some(target);
    match request.fingerprint.as_str() {
        "chrome120" => build_browser_like_quic_initial(QUIC_V1_VERSION, host, QuicInitialBrowserProfile::ChromeAndroid),
        "firefox121" => {
            build_browser_like_quic_initial(QUIC_V1_VERSION, host, QuicInitialBrowserProfile::FirefoxAndroid)
        }
        "generic_v1" => build_realistic_quic_initial(QUIC_V1_VERSION, host),
        "vn_probe" => build_realistic_quic_initial(QUIC_V1_VERSION, host)
            .and_then(|packet| tamper_quic_version(&packet, RESERVED_VERSION)),
        _ => None,
    }
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
    fn vn_probe_uses_reserved_version() {
        let response = create(r#"{"fingerprint":"vn_probe","target":"cloudflare.com"}"#);
        let value: Value = serde_json::from_str(&response).expect("response json");
        assert_eq!(value.get("error").and_then(Value::as_str), None);
        let packet = decode_packet(&value);
        assert_eq!(&packet[1..5], &RESERVED_VERSION.to_be_bytes());
    }

    fn decode_packet(value: &Value) -> Vec<u8> {
        STANDARD.decode(value.get("packetBase64").and_then(Value::as_str).expect("packet body")).expect("packet base64")
    }
}
