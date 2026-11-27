use super::tests::pair;
use super::*;
use crate::runtime::config::RuntimeConfig;
use std::io::{Read, Write};
use std::net::Shutdown;
use std::time::Duration;

const REQUEST: &[u8] = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";

#[test]
fn dpi38_http_blockpages_survive_each_header_and_body_split() {
    let request = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
    let body = "This site has been blocked by your ISP";
    let responses = [
        b"HTTP/1.1 451 Unavailable For Legal Reasons\r\nContent-Length: 0\r\n\r\n".to_vec(),
        format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n{body}", body.len()).into_bytes(),
    ];
    for response in responses {
        for split in 1..response.len() {
            let mut config = RuntimeConfig::default();
            config.timeouts.timeout_ms = 1_000;
            let state = RuntimeState::test(config);
            let (client, mut app) = pair();
            let client = ClientStream::Tcp(client);
            let (mut upstream, mut origin) = pair();
            let target = upstream.peer_addr().expect("target");
            origin.write_all(&response[..split]).expect("HTTP prefix");
            app.write_all(b"x").expect("owned client continuation");
            let FirstResponse::Continuation(reader) = read_first_response(
                &state,
                target,
                None,
                &mut upstream,
                request,
                Some(&client),
                FirstResponseReader::new(&state, request),
            )
            .expect("read split HTTP") else {
                panic!("incomplete HTTP headers or body must wait at split {split}");
            };
            origin.write_all(&response[split..]).expect("HTTP remainder");
            let FirstResponse::Failure { failure, response_bytes: Some(bytes) } =
                read_first_response(&state, target, None, &mut upstream, request, None, *reader)
                    .expect("complete HTTP blockpage")
            else {
                panic!("complete HTTP blockpage must classify at split {split}");
            };
            assert_eq!(failure.class.as_str(), "http_blockpage");
            assert_eq!(bytes, response);
        }
    }
}

#[test]
fn dpi38_incomplete_large_http_body_cannot_train_or_match_quoted_blockpage() {
    for phrase in ["This website has been blocked", "This site has been blocked by your ISP"] {
        let head = b"HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n";
        let mut response = head.to_vec();
        response.extend(phrase.as_bytes());
        response.resize(head.len() + 10_000, b'x');
        let mut config = RuntimeConfig::default();
        config.timeouts.timeout_bytes_limit = (head.len() + phrase.len() + 3) as i32;
        let state = RuntimeState::test(config);
        let (client, mut app) = pair();
        let client = ClientStream::Tcp(client);
        let (mut upstream, mut origin) = pair();
        upstream.set_read_timeout(Some(Duration::from_secs(2))).expect("bounded read");
        let target = upstream.peer_addr().expect("target");
        origin.write_all(&response[..head.len() - 1]).expect("fragmented headers");
        app.write_all(b"x").expect("owned continuation");
        let FirstResponse::Continuation(reader) = read_first_response(
            &state,
            target,
            None,
            &mut upstream,
            REQUEST,
            Some(&client),
            FirstResponseReader::new(&state, REQUEST),
        )
        .expect("partial HTTP headers") else {
            panic!("partial headers must retain observation");
        };
        origin.write_all(&response[head.len() - 1..]).expect("body containing quoted blockpage");
        let FirstResponse::Neutral(mut bytes) =
            read_first_response(&state, target, None, &mut upstream, REQUEST, None, *reader)
                .expect("bounded incomplete HTTP body")
        else {
            panic!("an incomplete body must not classify or reward");
        };
        assert_eq!(bytes, response[..bytes.len()]);
        let mut rest = vec![0; response.len() - bytes.len()];
        upstream.read_exact(&mut rest).expect("unread exact body");
        bytes.extend(rest);
        assert_eq!(bytes, response);
    }
}

#[test]
fn dpi38_http_first_message_excludes_pipelined_body_evidence() {
    let response = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nHTTP/1.1 200 OK\r\n\r\nThis website has been blocked";
    let state = RuntimeState::test(RuntimeConfig::default());
    let (mut upstream, mut origin) = pair();
    origin.write_all(response).expect("two source messages");
    let target = upstream.peer_addr().expect("target");
    let FirstResponse::Forward(bytes, _) = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        REQUEST,
        None,
        FirstResponseReader::new(&state, REQUEST),
    )
    .expect("first framed response") else {
        panic!("second-message body cannot classify the first response");
    };
    assert_eq!(bytes, response);
}

#[test]
fn dpi38_close_delimited_http_body_waits_for_graceful_eof() {
    let response = b"HTTP/1.0 200 OK\r\n\r\nThis site has been blocked by your ISP";
    let state = RuntimeState::test(RuntimeConfig::default());
    let (client, mut app) = pair();
    let client = ClientStream::Tcp(client);
    let (mut upstream, mut origin) = pair();
    origin.write_all(response).expect("close-delimited body");
    app.write_all(b"x").expect("owned continuation");
    let target = upstream.peer_addr().expect("target");
    let FirstResponse::Continuation(reader) = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        REQUEST,
        Some(&client),
        FirstResponseReader::new(&state, REQUEST),
    )
    .expect("observe unclosed message") else {
        panic!("close-delimited body requires EOF");
    };
    origin.shutdown(Shutdown::Write).expect("graceful HTTP message end");
    let FirstResponse::Failure { failure, response_bytes: Some(bytes) } =
        read_first_response(&state, target, None, &mut upstream, REQUEST, None, *reader)
            .expect("complete close-delimited body")
    else {
        panic!("complete body must classify");
    };
    assert_eq!(failure.class.as_str(), "http_blockpage");
    assert_eq!(bytes, response);
}

#[test]
fn dpi38_bodyless_and_unsupported_http_responses_do_not_wait_for_body() {
    for (request, head, neutral) in [
        (b"HEAD / HTTP/1.1\r\n\r\n".as_slice(), "HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n", false),
        (REQUEST, "HTTP/1.1 204 No Content\r\nContent-Length: 10000\r\n\r\n", false),
        (REQUEST, "HTTP/1.1 304 Not Modified\r\nContent-Length: 10000\r\n\r\n", false),
        (b"CONNECT example.test:443 HTTP/1.1\r\n\r\n".as_slice(), "HTTP/1.1 200 OK\r\n\r\n", false),
        (REQUEST, "HTTP/1.1 100 Continue\r\n\r\n", true),
        (REQUEST, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n", true),
        (REQUEST, "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 100\r\n\r\n", true),
        (REQUEST, "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nContent-Length: 9\r\n\r\n", true),
    ] {
        let state = RuntimeState::test(RuntimeConfig::default());
        let (mut upstream, mut origin) = pair();
        origin.write_all(head.as_bytes()).expect("source response headers");
        let target = upstream.peer_addr().expect("target");
        let result = read_first_response(
            &state,
            target,
            None,
            &mut upstream,
            request,
            None,
            FirstResponseReader::new(&state, request),
        )
        .expect("header-only framing");
        let bytes = match result {
            FirstResponse::Neutral(bytes) if neutral => bytes,
            FirstResponse::Forward(bytes, _) if !neutral => bytes,
            _ => panic!("wrong header-only outcome for {head}"),
        };
        assert_eq!(bytes, head.as_bytes());
    }
}

#[test]
fn dpi38_truncated_content_length_eof_keeps_transport_failure() {
    let response = b"HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\nThis website has been blocked";
    let state = RuntimeState::test(RuntimeConfig::default());
    let (mut upstream, mut origin) = pair();
    origin.write_all(response).expect("incomplete HTTP body");
    origin.shutdown(Shutdown::Write).expect("premature body EOF");
    let target = upstream.peer_addr().expect("target");
    let FirstResponse::Failure { failure, response_bytes: None } = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        REQUEST,
        None,
        FirstResponseReader::new(&state, REQUEST),
    )
    .expect("independent transport EOF") else {
        panic!("a known-length truncated response is not complete");
    };
    assert_eq!(failure.class.as_str(), "silent_drop");
}

#[test]
fn dpi38_http_header_failure_survives_unsupported_body_framing() {
    let response = b"HTTP/1.1 451 Unavailable For Legal Reasons\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n";
    let state = RuntimeState::test(RuntimeConfig::default());
    let (mut upstream, mut origin) = pair();
    origin.write_all(response).expect("header failure with opaque body");
    let target = upstream.peer_addr().expect("target");
    let FirstResponse::Failure { failure, response_bytes: Some(bytes) } = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        REQUEST,
        None,
        FirstResponseReader::new(&state, REQUEST),
    )
    .expect("independent header evidence") else {
        panic!("unsupported body inspection does not hide status 451");
    };
    assert_eq!(failure.class.as_str(), "http_blockpage");
    assert_eq!(bytes, response);
}

#[test]
fn dpi38_http_bodyless_methods_match_accepted_leading_line_prefixes() {
    let mut wrong = Vec::new();
    for prefix in ["", "\r", "\n", "\r\n", "\n\r", "\r\r", "\n\n"] {
        for (method, response) in [
            ("HEAD", b"HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n".as_slice()),
            ("HEAD", b"HTTP/1.1 200 OK\r\n\r\nHTTP/1.1 200 OK\r\n\r\nThis website has been blocked".as_slice()),
            ("CONNECT", b"HTTP/1.1 200 Connection Established\r\n\r\nThis website has been blocked".as_slice()),
        ] {
            let target = if method == "CONNECT" { "example.test:443" } else { "/" };
            let request = format!("{prefix}{method} {target} HTTP/1.1\r\nHost: example.test\r\n\r\n");
            assert_eq!(
                ripdpi_packets::http_request_head_end(request.as_bytes()),
                Some(request.len()),
                "owning request parser accepts prefix {prefix:?}"
            );
            let mut config = RuntimeConfig::default();
            config.timeouts.timeout_ms = 50;
            let state = RuntimeState::test(config);
            let (mut upstream, mut origin) = pair();
            origin.write_all(response).expect("bodyless response and possible opaque spill");
            origin.shutdown(Shutdown::Write).expect("source closes after exact opaque bytes");
            let target = upstream.peer_addr().expect("target");
            match read_first_response(
                &state,
                target,
                None,
                &mut upstream,
                request.as_bytes(),
                None,
                FirstResponseReader::new(&state, request.as_bytes()),
            )
            .expect("bodyless method framing")
            {
                FirstResponse::Forward(bytes, _) => {
                    assert_eq!(bytes, response, "all pipelined or tunnel bytes remain exact");
                }
                FirstResponse::Failure { failure, .. } => {
                    wrong.push(format!("{method} prefix {prefix:?}: {}", failure.class.as_str()));
                }
                _ => wrong.push(format!("{method} prefix {prefix:?}: incomplete or neutral")),
            }
        }
    }
    assert!(wrong.is_empty(), "bodyless method must end inspection at headers: {wrong:?}");
}
