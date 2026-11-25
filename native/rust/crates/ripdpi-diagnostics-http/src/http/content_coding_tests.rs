use std::io::{Read, Write};
use std::net::TcpListener;

use super::{HttpRequestOptions, execute_http_request_targets_with_accept, try_http_request};
use crate::http::{HttpRequestError, HttpResponse};
use crate::transport::{TargetAddress, direct_transport};

const BLOCKPAGE: &[u8] = b"<html>This website has been blocked by your internet service provider</html>";
const GZIP_BLOCKPAGE: &[u8] = b"\x1f\x8b\x08\x00\x00\x00\x00\x00\x02\x13\x1d\xcc\x81\x09\x80\x30\x0c\x04\xc0\x55\x7e\x03\x17\x10\xa7\x70\x01\xd3\x3e\x34\x58\x5b\x49\x62\xa5\xdb\x0b\xde\x00\xb7\x96\xb8\xea\xb6\x17\x75\xbc\x14\xd7\x20\xca\xe1\x10\xb2\x41\x6a\x4f\x27\x33\x64\x62\xf6\xc7\xa0\x2d\x68\x8d\x01\xa7\x0d\x4d\xc4\x6d\x7d\x68\xa6\xad\xcb\xbf\x7c\x8e\x5b\x67\x09\x4c\x00\x00\x00";

fn request(reply: Vec<u8>, accept: &str) -> (Result<HttpResponse, HttpRequestError>, String) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        socket.set_read_timeout(Some(std::time::Duration::from_secs(1))).unwrap();
        let mut request = Vec::new();
        while !request.ends_with(b"\r\n\r\n") {
            let mut bytes = [0u8; 1024];
            let count = socket.read(&mut bytes).unwrap();
            assert!(count > 0 && request.len() + count <= 4096);
            request.extend_from_slice(&bytes[..count]);
        }
        let _ = socket.write_all(&reply);
        String::from_utf8(request).unwrap()
    });
    let response = execute_http_request_targets_with_accept(
        &[TargetAddress::Ip(address.ip())],
        address.port(),
        &direct_transport(),
        "example.org",
        "/",
        false,
        None,
        HttpRequestOptions { accept, cancel: None },
    );
    (response, server.join().unwrap())
}

fn response(status: u16, headers: &str, body: &[u8]) -> Vec<u8> {
    let mut reply = format!("HTTP/1.1 {status} OK\r\nContent-Length: {}\r\n{headers}\r\n", body.len()).into_bytes();
    reply.extend_from_slice(body);
    reply
}

#[test]
fn requests_identity_for_both_generic_http_and_json() {
    for (accept, body) in [("*/*", b"ok".as_slice()), ("application/dns-json", b"{\"Status\":0}".as_slice())] {
        let (result, sent) = request(response(200, "Content-Encoding: identity\r\n", body), accept);
        assert_eq!(result.unwrap().body, body);
        assert!(sent.contains("\r\nAccept-Encoding: identity\r\n"), "{sent}");
        assert!(sent.contains(&format!("\r\nAccept: {accept}\r\n")), "{sent}");
    }
}

#[test]
fn compressed_blockpage_cannot_become_a_clean_live_response() {
    for (coding, body, expected) in
        [("", BLOCKPAGE, "http_blockpage"), ("Content-Encoding: gzip\r\n", GZIP_BLOCKPAGE, "http_unreachable")]
    {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let reply = response(200, coding, body);
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let _ = socket.read(&mut [0u8; 1024]).unwrap();
            let _ = socket.write_all(&reply);
        });
        let result = try_http_request(
            &TargetAddress::Ip(address.ip()),
            address.port(),
            &direct_transport(),
            "example.org",
            "/",
            false,
        );
        server.join().unwrap();
        assert_eq!(result.status, expected);
        if !coding.is_empty() {
            assert_eq!(result.error.as_deref(), Some("unsupported_content_encoding"));
            assert!(result.response.is_none());
        }
    }
}

#[test]
fn unsupported_coding_is_rejected_from_the_head() {
    for coding in ["gzip", "br", "identity, gzip", "identity\r\nContent-Encoding: gzip", "identity;q=1", "\"identity\""]
    {
        let reply = format!("HTTP/1.1 200 OK\r\nContent-Length: 20\r\nContent-Encoding: {coding}\r\n\r\n").into_bytes();
        let (result, _) = request(reply, "*/*");
        let error = result.unwrap_err();
        assert_eq!(error.status(), "invalid_response", "{coding}");
        let HttpRequestError::ResponseRead(source) = error else {
            panic!("expected a response-read failure for {coding}, got {error:?}");
        };
        assert_eq!(source.kind(), std::io::ErrorKind::InvalidData, "{coding}");
        assert_eq!(source.to_string(), "unsupported_content_encoding", "{coding}");
    }
}

#[test]
fn identity_and_bodyless_metadata_preserve_framing_and_size_limits() {
    for coding in
        ["IDENTITY, identity", "identity,", " , identity, , ", "identity\r\nContent-Encoding:", "", ",", ", \t ,"]
    {
        let (result, _) = request(response(200, &format!("Content-Encoding: {coding}\r\n"), b"ok"), "*/*");
        assert_eq!(result.unwrap().body, b"ok", "{coding}");
    }
    let (result, _) =
        request(b"HTTP/1.1 304 Not Modified\r\nContent-Length: 123\r\nContent-Encoding: gzip\r\n\r\n".to_vec(), "*/*");
    assert_eq!(result.unwrap().status_code, 304);
    let (result, _) = request(
        b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Encoding: identity\r\n\r\nx".to_vec(),
        "application/dns-json",
    );
    assert_eq!(result.unwrap_err().to_string(), "response_truncated");
    let (result, _) =
        request(b"HTTP/1.1 200 OK\r\nContent-Length: 65537\r\nContent-Encoding: identity\r\n\r\n".to_vec(), "*/*");
    assert_eq!(result.unwrap_err().to_string(), "response_too_large");
}
