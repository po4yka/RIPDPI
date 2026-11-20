use std::net::{IpAddr, SocketAddr};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use jni::objects::JString;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};
use ripdpi_dns_morph::{Bridge, FETCH_DEADLINE};
use ripdpi_runtime_platform::protect::{has_protect_callback, protect_socket_via_callback};
use serde::{Deserialize, Serialize};

const MAX_REQUEST_BYTES: usize = 1024;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct FetchRequest {
    address: String,
    port: u16,
    zone: String,
    public_key: String,
    token: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct FetchResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    payload_base64: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

/// Blocks the calling JNI thread for at most [`FETCH_DEADLINE`].
pub fn fetch_entry(mut env: EnvUnowned<'_>, request_json: JString<'_>) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let chars = request_json.mutf8_chars(env)?;
            let reply = if chars.to_bytes().len() > MAX_REQUEST_BYTES {
                response(Err("DNS-Morph request is too large".to_string()))
            } else {
                response(fetch(&chars.to_str()))
            };
            Ok(env.new_string(reply)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

fn fetch(request_json: &str) -> Result<Vec<u8>, String> {
    let request: FetchRequest =
        serde_json::from_str(request_json).map_err(|_| "invalid DNS-Morph request".to_string())?;
    let ip: IpAddr = request.address.parse().map_err(|_| "invalid DNS-Morph bridge address".to_string())?;
    let public_key = decode_key(&request.public_key).ok_or_else(|| "invalid DNS-Morph public key".to_string())?;
    let token = decode_key(&request.token).ok_or_else(|| "invalid DNS-Morph token".to_string())?;
    let bridge =
        Bridge::new(SocketAddr::new(ip, request.port), &request.zone, public_key, token).map_err(|e| e.to_string())?;
    ripdpi_dns_morph::fetch(&bridge, FETCH_DEADLINE, |fd| {
        // No callback means no active VPN: the direct socket cannot loop into a TUN.
        if has_protect_callback() { protect_socket_via_callback(fd) } else { Ok(()) }
    })
    .map_err(|error| error.to_string())
}

fn decode_key(encoded: &str) -> Option<[u8; 32]> {
    STANDARD.decode(encoded).ok()?.try_into().ok()
}

fn response(result: Result<Vec<u8>, String>) -> String {
    let reply = match result {
        Ok(payload) => FetchResponse { payload_base64: Some(STANDARD.encode(payload)), error: None },
        Err(error) => FetchResponse { payload_base64: None, error: Some(error) },
    };
    serde_json::to_string(&reply).unwrap_or_else(|_| "{\"error\":\"serialize DNS-Morph reply\"}".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_malformed_requests_before_any_socket() {
        let key = STANDARD.encode([0u8; 32]);
        for request in [
            "{}".to_string(),
            format!(r#"{{"address":"bridge.example","port":53,"zone":"m.test","publicKey":"{key}","token":"{key}"}}"#),
            r#"{"address":"192.0.2.1","port":53,"zone":"m.test","publicKey":"AAAA","token":"AAAA"}"#.to_string(),
            format!(
                r#"{{"address":"192.0.2.1","port":53,"zone":"m.test","publicKey":"{key}","token":"{key}","extra":1}}"#
            ),
        ] {
            assert!(fetch(&request).is_err(), "{request}");
        }
    }

    #[test]
    fn encodes_payload_or_error() {
        assert_eq!(response(Ok(b"hi".to_vec())), r#"{"payloadBase64":"aGk="}"#);
        assert_eq!(response(Err("no".into())), r#"{"error":"no"}"#);
    }
}
