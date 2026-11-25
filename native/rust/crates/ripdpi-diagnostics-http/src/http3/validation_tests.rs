use super::*;

#[test]
fn ordinary_response_trailers_preserve_completion() {
    let server =
        Server::start(Reply::Raw(&[1, 3, 0, 0, 0xd9, 0, 2, b'o', b'k', 1, 6, 0, 0, 0x21, b'x', 1, b'y']), b"h3");
    let facts = run(&server, &server.config());
    assert_eq!(facts.status, "COMPLETE", "{facts:?}");
    assert!(facts.http3_validated && facts.body_complete, "{facts:?}");
    assert_eq!(facts.response_bytes, 2);
    server.finish();
}

#[test]
fn connection_specific_response_fields_never_complete() {
    for name in ["connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "te"] {
        let server = Server::start(Reply::ForbiddenField(name), b"h3");
        let facts = run(&server, &server.config());
        assert_eq!(facts.status, "FAILED", "{name}: {facts:?}");
        assert!(!facts.http3_validated && !facts.body_complete, "{name}: {facts:?}");
        server.finish();
    }
}

#[test]
fn connection_specific_trailers_never_complete() {
    for name in ["connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "te"] {
        let server = Server::start(Reply::ForbiddenTrailer(name), b"h3");
        let facts = run(&server, &server.config());
        assert_eq!(facts.status, "FAILED", "{name}: {facts:?}");
        assert!(facts.http3_validated && !facts.body_complete, "{name}: {facts:?}");
        server.finish();
    }
}

#[test]
fn malformed_pseudo_fields_never_complete() {
    let responses: &[&[u8]] = &[
        &[1, 4, 0, 0, 0xd9, 0xd9],
        &[1, 4, 0, 0, 0xd9, 0xd1],
        &[1, 8, 0, 0, 0xd9, 0x21, b'x', 1, b'y', 0xd9],
        &[1, 3, 0, 0, 0xd9, 1, 3, 0, 0, 0xd9],
    ];
    for response in responses {
        let server = Server::start(Reply::Raw(response), b"h3");
        let facts = run(&server, &server.config());
        assert_eq!(facts.status, "FAILED", "{response:?}: {facts:?}");
        assert!(!facts.body_complete, "{facts:?}");
        server.finish();
    }
}
