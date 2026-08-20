//! Integration test: root-helper `SCM_RIGHTS` file-descriptor passing.
//!
//! The privileged `ripdpi-root-helper` binary (Linux / Android) receives the
//! caller's live socket fd as `SCM_RIGHTS` ancillary data alongside a JSON
//! [`HelperRequest`] -- the fd never appears inside the JSON. See
//! `docs/architecture/ROOT_HELPER_CONTRACT.md`, "File-descriptor passing".
//!
//! These tests drive the real protocol helpers -- [`send_message`] /
//! [`recv_message`] -- over a `UnixStream` socket-pair harness. No `su`, no
//! root, no privileged binary, and no separate process are involved: the
//! socket pair stands in for the client<->helper connection. They prove that
//!
//! * an out-of-band fd survives the round trip and arrives as a working
//!   descriptor the receiver can both read from and write through; and
//! * the JSON command payload carries no file-descriptor field, so the fd
//!   channel is independent of the JSON params channel.
//!
//! ## Platform gate
//!
//! The whole file is gated `#![cfg(unix)]`. `SCM_RIGHTS` over `AF_UNIX` is a
//! Unix mechanism and `ripdpi-root-helper-protocol` is itself a Unix-only
//! crate (it builds on `std::os::unix` and `nix`), so the root-helper's
//! Linux / Android target family is a subset of `unix`. On a non-Unix target
//! the file compiles to nothing and contributes zero tests -- a clean skip.
#![cfg(unix)]

use std::io::{Read, Write};
use std::os::fd::{AsFd, BorrowedFd, OwnedFd};
use std::os::unix::net::UnixStream;

use ripdpi_root_helper_protocol::{CMD_SEND_FAKE_RST, HelperRequest, recv_message, send_message};
use serde_json::{Value, json};

/// A nonce of the documented shape (32 URL-safe Base64 chars). Only its
/// presence in the request matters here; this harness does not validate it.
const SAMPLE_NONCE: &str = "abcdefghijklmnopqrstuvwxyzABCDEF";

/// A representative fd-bearing request. `send_fake_rst` is a socket-bound
/// command: the client passes the live TCP socket fd via `SCM_RIGHTS`, so its
/// JSON has only the four [`HelperRequest`] fields; the fd remains out of band.
fn sample_request() -> HelperRequest {
    HelperRequest {
        protocol_version: Some(ripdpi_root_helper_protocol::PROTOCOL_VERSION),
        command: CMD_SEND_FAKE_RST.to_string(),
        params: json!({ "default_ttl": 64, "tcp_flags_set": 4 }),
        session_nonce: Some(SAMPLE_NONCE.to_string()),
    }
}

/// Run one `send_message` -> `recv_message` round trip over a fresh IPC socket
/// pair, exactly as the root-helper client and helper exchange one message.
/// Returns the JSON frame bytes and whichever fd (if any) arrived out-of-band.
fn ipc_round_trip(json: &[u8], fd: Option<BorrowedFd<'_>>) -> (Vec<u8>, Option<OwnedFd>) {
    let (client, helper) = UnixStream::pair().expect("ipc socket pair");
    send_message(&client, json, fd).expect("send_message");
    recv_message(&helper, "ipc channel closed before a message arrived").expect("recv_message")
}

/// Take ownership of an optionally-delivered descriptor so it is closed once.
fn close_received_fd(fd: Option<OwnedFd>) {
    drop(fd);
}

/// Recursively report whether any object key anywhere in `value` looks like a
/// file descriptor -- the JSON command payload must contain no such key.
fn json_has_fd_shaped_key(value: &Value) -> bool {
    match value {
        Value::Object(map) => map.iter().any(|(key, child)| {
            let lower = key.to_ascii_lowercase();
            lower == "fd"
                || lower == "rawfd"
                || lower == "raw_fd"
                || lower.ends_with("_fd")
                || lower.contains("file_descriptor")
                || lower.contains("filedescriptor")
                || json_has_fd_shaped_key(child)
        }),
        Value::Array(items) => items.iter().any(json_has_fd_shaped_key),
        _ => false,
    }
}

/// Task 2 + 3: a real fd sent via `SCM_RIGHTS` survives the round trip and the
/// received descriptor is a live socket the receiver can read from and write
/// through -- proving ancillary fd passing works on its own.
#[test]
fn passed_fd_round_trips_as_a_working_descriptor() {
    // The payload: one end of an independent connected socket pair. `app_end`
    // stays with the test; `passed_end`'s fd is what crosses the IPC boundary.
    let (mut app_end, passed_end) = UnixStream::pair().expect("payload socket pair");

    let json = serde_json::to_vec(&sample_request()).expect("serialize request");
    let (received_json, received_fd) = ipc_round_trip(&json, Some(passed_end.as_fd()));
    // The helper now owns an independent `SCM_RIGHTS` duplicate; dropping the
    // test's original handle proves the surviving fd is the kernel's duplicate.
    drop(passed_end);

    // The JSON deserializes back to the request -- the fd was never in it.
    let decoded: HelperRequest = serde_json::from_slice(&received_json).expect("deserialize request");
    assert_eq!(decoded.command, CMD_SEND_FAKE_RST);
    let received_fd = received_fd.expect("an fd must arrive as SCM_RIGHTS ancillary data");

    let mut helper_view = UnixStream::from(received_fd);

    // Readable: bytes written on the retained app end arrive through the fd
    // that crossed the IPC boundary.
    let ping = b"ping-via-app-end";
    app_end.write_all(ping).expect("write to app end");
    let mut got = vec![0u8; ping.len()];
    helper_view.read_exact(&mut got).expect("read through received fd");
    assert_eq!(got, ping, "received fd must be readable");

    // Writable: bytes written through the received fd arrive on the app end.
    let pong = b"pong-via-received-fd";
    helper_view.write_all(pong).expect("write through received fd");
    let mut back = vec![0u8; pong.len()];
    app_end.read_exact(&mut back).expect("read from app end");
    assert_eq!(back, pong, "received fd must be writable");
}

/// Task 4: the on-wire command JSON carries exactly the four `HelperRequest`
/// fields and no file-descriptor field at any depth -- the fd travels only as
/// `SCM_RIGHTS` ancillary data.
#[test]
fn command_json_carries_no_file_descriptor_field() {
    let (_app_end, passed_end) = UnixStream::pair().expect("payload socket pair");

    let json = serde_json::to_vec(&sample_request()).expect("serialize request");
    let (received_json, received_fd) = ipc_round_trip(&json, Some(passed_end.as_fd()));

    // The fd still travels -- but only out of band.
    assert!(received_fd.is_some(), "the fd must be delivered as ancillary data");
    close_received_fd(received_fd);

    let value: Value = serde_json::from_slice(&received_json).expect("parse request json");
    let object = value.as_object().expect("request is a JSON object");
    let mut keys: Vec<&str> = object.keys().map(String::as_str).collect();
    keys.sort_unstable();
    assert_eq!(
        keys,
        ["command", "params", "protocol_version", "session_nonce"],
        "request JSON has only its four fields",
    );
    assert!(!json_has_fd_shaped_key(&value), "no fd-shaped key may appear anywhere in the command JSON");
}

/// Task 3: the fd channel and the JSON channel are orthogonal -- the fd
/// arrives whether the request carries full params or none, and no fd arrives
/// when none is attached even though the JSON is unchanged.
#[test]
fn fd_channel_is_independent_of_the_json_params() {
    // A fully-populated params payload still delivers the fd.
    let (_full_app, full_passed) = UnixStream::pair().expect("payload socket pair");
    let full_json = serde_json::to_vec(&sample_request()).expect("serialize full request");
    let (full_received, full_fd) = ipc_round_trip(&full_json, Some(full_passed.as_fd()));
    assert!(full_fd.is_some(), "fd must arrive alongside a full params payload");
    close_received_fd(full_fd);
    let full_decoded: HelperRequest = serde_json::from_slice(&full_received).expect("deserialize full");
    assert_eq!(full_decoded.command, CMD_SEND_FAKE_RST);

    // A request with no params at all rides the very same fd channel.
    let bare = HelperRequest {
        protocol_version: Some(ripdpi_root_helper_protocol::PROTOCOL_VERSION),
        command: CMD_SEND_FAKE_RST.to_string(),
        params: Value::Null,
        session_nonce: None,
    };
    let (_bare_app, bare_passed) = UnixStream::pair().expect("payload socket pair");
    let bare_json = serde_json::to_vec(&bare).expect("serialize bare request");
    let (bare_received, bare_fd) = ipc_round_trip(&bare_json, Some(bare_passed.as_fd()));
    assert!(bare_fd.is_some(), "fd must arrive even when the request has no params");
    close_received_fd(bare_fd);
    let bare_value: Value = serde_json::from_slice(&bare_received).expect("parse bare json");
    assert_eq!(
        bare_value.as_object().expect("bare request is a JSON object").len(),
        2,
        "a params-less request serializes to protocol_version and command only",
    );

    // Conversely, the same JSON with no fd attached delivers no ancillary fd.
    let (no_fd_received, no_fd) = ipc_round_trip(&full_json, None);
    assert!(no_fd.is_none(), "no fd is delivered when none is attached");
    let no_fd_decoded: HelperRequest = serde_json::from_slice(&no_fd_received).expect("deserialize no-fd");
    assert_eq!(no_fd_decoded.command, CMD_SEND_FAKE_RST, "the JSON channel is unaffected by the absent fd");
}
