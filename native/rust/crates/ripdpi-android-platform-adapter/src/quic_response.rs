use jni::objects::JByteArray;
use jni::sys::{jboolean, jint};
use jni::{EnvUnowned, Outcome};
use ripdpi_packets::{QuicProbeResponseKind, validate_quic_probe_response, validate_quic_version_negotiation_response};

pub const QUIC_RESPONSE_VALIDATION_ERROR: jint = -1;

// JNI wire values shared with NativeQuicInitialPacketBindings.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i32)]
enum ProbeReplyStatus {
    Uncorrelated = 0,
    InitialCrypto = 1,
    InitialAck = 2,
    InitialOther = 3,
    InitialClose = 4,
    Retry = 5,
    VersionNegotiation = 6,
}

pub fn validate_entry(
    mut env: EnvUnowned<'_>,
    request: JByteArray<'_>,
    response: JByteArray<'_>,
    version_negotiation: jboolean,
) -> jint {
    match env
        .with_env(move |env| -> jni::errors::Result<jint> {
            let request = env.convert_byte_array(&request)?;
            let response = env.convert_byte_array(&response)?;
            Ok(validate_response(&request, &response, version_negotiation) as jint)
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => QUIC_RESPONSE_VALIDATION_ERROR,
    }
}

fn validate_response(request: &[u8], response: &[u8], version_negotiation: bool) -> ProbeReplyStatus {
    if version_negotiation {
        return if validate_quic_version_negotiation_response(request, response) {
            ProbeReplyStatus::VersionNegotiation
        } else {
            ProbeReplyStatus::Uncorrelated
        };
    }
    match validate_quic_probe_response(request, response) {
        Some(QuicProbeResponseKind::InitialCrypto) => ProbeReplyStatus::InitialCrypto,
        Some(QuicProbeResponseKind::InitialAck) => ProbeReplyStatus::InitialAck,
        Some(QuicProbeResponseKind::InitialOther) => ProbeReplyStatus::InitialOther,
        Some(QuicProbeResponseKind::InitialClose) => ProbeReplyStatus::InitialClose,
        Some(QuicProbeResponseKind::Retry) => ProbeReplyStatus::Retry,
        Some(QuicProbeResponseKind::VersionNegotiation) | None => ProbeReplyStatus::Uncorrelated,
    }
}

#[cfg(test)]
mod tests;
