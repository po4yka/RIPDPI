use ripdpi_privileged_ops as platform;
use ripdpi_root_helper_protocol::{HelperResponse, IpFragmentErrorData};

pub(super) fn fragment_error_response(error: std::io::Error) -> HelperResponse {
    let opaque = platform::IpFragmentSendError { source: error, payload_commit_uncertain: true };
    let phase = opaque
        .source
        .get_ref()
        .and_then(|source| source.downcast_ref::<platform::IpFragmentSendError>())
        .unwrap_or(&opaque);
    let mut response = HelperResponse::error(phase.source.to_string());
    response.data = serde_json::json!(IpFragmentErrorData {
        error_kind: format!("{:?}", phase.source.kind()),
        raw_os_error: phase.raw_os_error(),
        payload_commit_uncertain: phase.payload_commit_uncertain,
    });
    response
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io;

    #[test]
    fn fragment_error_metadata_retains_kind_and_emission_boundary() {
        for uncertain in [false, true] {
            let phase = platform::IpFragmentSendError {
                source: io::Error::from(io::ErrorKind::WouldBlock),
                payload_commit_uncertain: uncertain,
            };
            let response = fragment_error_response(io::Error::new(io::ErrorKind::WouldBlock, phase));
            assert_eq!(response.data["error_kind"], "WouldBlock");
            assert_eq!(response.data["payload_commit_uncertain"], uncertain);
            let data: IpFragmentErrorData = serde_json::from_value(response.data).expect("typed fragment error");
            let (source, recovered_uncertainty) =
                data.into_source_error(response.error.expect("error message")).expect("decode source");
            assert_eq!(source.kind(), io::ErrorKind::WouldBlock);
            assert_eq!(recovered_uncertainty, uncertain);
        }
    }

    #[test]
    fn normalized_capability_error_keeps_its_inner_errno() {
        let phase = platform::IpFragmentSendError {
            source: io::Error::new(io::ErrorKind::Unsupported, io::Error::from_raw_os_error(libc::EPERM)),
            payload_commit_uncertain: false,
        };
        let response = fragment_error_response(io::Error::new(io::ErrorKind::Unsupported, phase));
        assert_eq!(response.data["error_kind"], "Unsupported");
        assert_eq!(response.data["raw_os_error"], libc::EPERM);
        let data: IpFragmentErrorData = serde_json::from_value(response.data).expect("typed metadata");
        let (source, uncertain) = data.into_source_error(response.error.unwrap()).expect("decode source");
        let recovered = platform::IpFragmentSendError { source, payload_commit_uncertain: uncertain };
        assert_eq!(recovered.source.kind(), io::ErrorKind::Unsupported);
        assert_eq!(recovered.raw_os_error(), Some(libc::EPERM));
        assert!(!recovered.payload_commit_uncertain);
    }

    #[test]
    fn unknown_error_is_conservatively_uncertain() {
        let response = fragment_error_response(io::Error::from(io::ErrorKind::WouldBlock));
        assert_eq!(response.data["error_kind"], "WouldBlock");
        assert_eq!(response.data["payload_commit_uncertain"], true);
    }
}
