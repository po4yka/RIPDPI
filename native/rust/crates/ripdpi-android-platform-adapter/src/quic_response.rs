use jni::objects::JByteArray;
use jni::sys::{JNI_FALSE, jboolean};
use jni::{EnvUnowned, Outcome};
use ripdpi_packets::{QuicResponseKind, validate_quic_response, validate_quic_version_negotiation_response};

pub fn validate_entry(
    mut env: EnvUnowned<'_>,
    request: JByteArray<'_>,
    response: JByteArray<'_>,
    version_negotiation: jboolean,
) -> jboolean {
    match env
        .with_env(move |env| -> jni::errors::Result<jboolean> {
            let request = env.convert_byte_array(&request)?;
            let response = env.convert_byte_array(&response)?;
            Ok(validate_response(&request, &response, version_negotiation))
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => JNI_FALSE,
    }
}

fn validate_response(request: &[u8], response: &[u8], version_negotiation: bool) -> bool {
    if version_negotiation {
        validate_quic_version_negotiation_response(request, response)
    } else {
        matches!(validate_quic_response(request, response), Some(QuicResponseKind::Initial | QuicResponseKind::Retry))
    }
}

#[cfg(test)]
mod tests;
