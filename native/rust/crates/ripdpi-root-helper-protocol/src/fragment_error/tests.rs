use super::*;

#[test]
fn fragment_failure_decodes_kind_and_emission_phase_without_an_errno() {
    for (label, expected) in [
        ("WouldBlock", ErrorKind::WouldBlock),
        ("InvalidInput", ErrorKind::InvalidInput),
        ("Unsupported", ErrorKind::Unsupported),
        ("ConnectionReset", ErrorKind::ConnectionReset),
        ("Other", ErrorKind::Other),
    ] {
        for uncertain in [false, true] {
            let data: IpFragmentErrorData = serde_json::from_value(serde_json::json!({
                "error_kind": label, "raw_os_error": null, "payload_commit_uncertain": uncertain,
            }))
            .expect("fragment error data");
            let (error, phase) = data.into_source_error("flow rejected".to_owned()).expect("validated source");
            assert_eq!(error.kind(), expected);
            assert_eq!(error.to_string(), "flow rejected");
            assert_eq!(phase, uncertain);
        }
    }
}

#[test]
fn normalized_kind_does_not_turn_back_into_its_original_errno_kind() {
    let data: IpFragmentErrorData = serde_json::from_value(serde_json::json!({
        "error_kind": "Unsupported", "raw_os_error": libc::EPERM, "payload_commit_uncertain": false,
    }))
    .expect("normalized capability error");
    let (error, uncertain) = data.into_source_error("capability unavailable".to_owned()).expect("validated source");
    assert_eq!(error.kind(), ErrorKind::Unsupported);
    assert!(!uncertain);
    let original = error.get_ref().and_then(|inner| inner.downcast_ref::<io::Error>()).expect("original OS error");
    assert_eq!(original.raw_os_error(), Some(libc::EPERM));
}

#[test]
fn raw_error_kind_and_errno_round_trip_without_loss() {
    let original = io::Error::from_raw_os_error(libc::EAGAIN);
    let data: IpFragmentErrorData = serde_json::from_value(serde_json::json!({
        "error_kind": format!("{:?}", original.kind()),
        "raw_os_error": libc::EAGAIN,
        "payload_commit_uncertain": true,
    }))
    .expect("raw error data");
    let (error, uncertain) = data.into_source_error("busy flow".to_owned()).expect("validated source");
    assert_eq!(error.kind(), original.kind());
    assert_eq!(error.raw_os_error(), Some(libc::EAGAIN));
    assert!(uncertain);
}

#[test]
fn malformed_fragment_failure_data_is_rejected() {
    for value in [
        serde_json::Value::Null,
        serde_json::json!([]),
        serde_json::json!({"error_kind":"WouldBlock"}),
        serde_json::json!({"error_kind":"WouldBlock","payload_commit_uncertain":"false"}),
        serde_json::json!({"error_kind":false,"payload_commit_uncertain":false}),
        serde_json::json!({"error_kind":"WouldBlock","raw_os_error":"11","payload_commit_uncertain":false}),
        serde_json::json!({"error_kind":"WouldBlock","payload_commit_uncertain":false,"unknown":0}),
    ] {
        assert!(serde_json::from_value::<IpFragmentErrorData>(value).is_err());
    }
    for (kind, errno) in [
        ("InventedKind", None),
        ("InventedKind", Some(1)),
        ("WouldBlock", Some(0)),
        ("WouldBlock", Some(-1)),
        ("WouldBlock", Some(4096)),
    ] {
        let data =
            IpFragmentErrorData { error_kind: kind.to_owned(), raw_os_error: errno, payload_commit_uncertain: false };
        let error = data.into_source_error("untrusted error".to_owned()).expect_err("invalid data");
        assert_eq!(error.kind(), ErrorKind::InvalidData);
    }
}
