use std::io::Read;
use std::net::{TcpListener, TcpStream};
use std::time::Duration;

use super::RuntimeState;
use crate::runtime::desync::DesyncSendRequest;
use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyRuntimeContext;
use ripdpi_proxy_runtime_adapter::model::session::OutboundProgress;

fn lua_runtime(script: &str) -> (RuntimeState, tempfile::TempDir) {
    lua_runtime_with_matcher(script, "")
}

fn lua_runtime_with_matcher(script: &str, matcher: &str) -> (RuntimeState, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join("candidate.lua"), script).unwrap();
    let state = RuntimeState::test_with_context(
        RuntimeConfig::default(),
        Some(ProxyRuntimeContext {
            strategy_chain_yaml: Some(format!(
                "version: 1\nstrategies:\n  - id: socket\n{matcher}    on_fail: fallback_plain\n    steps:\n      - type: lua\n        function: candidate\n        script_paths: [candidate.lua]\n"
            )),
            lua_script_base_dir: Some(dir.path().to_string_lossy().into_owned()),
            ..ProxyRuntimeContext::default()
        }),
    );
    (state, dir)
}

fn connected_pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let writer = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    let (reader, _) = listener.accept().unwrap();
    reader.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    (writer, reader)
}

fn send(
    state: &RuntimeState,
    writer: &mut TcpStream,
    flow: &ripdpi_proxy_runtime_adapter::desync_platform::SocketLuaFlow,
    bytes: &[u8],
) -> crate::runtime::desync::OutboundSendOutcome {
    state
        .send_tcp_desync_payload(
            writer,
            DesyncSendRequest {
                lua_flow: Some(flow),
                group_index: 0,
                group_override: None,
                payload: bytes,
                progress: OutboundProgress {
                    round: 1,
                    payload_size: bytes.len(),
                    stream_start: 0,
                    stream_end: bytes.len().saturating_sub(1),
                },
                host: None,
                target: writer.peer_addr().unwrap(),
            },
        )
        .unwrap()
}

#[test]
fn lua_nonroot_tcp_split_uses_existing_socket() {
    let (state, _dir) = lua_runtime("function candidate(d) d.split(2,false); return VERDICT_MODIFY end");
    let (mut writer, mut reader) = connected_pair();
    let flow = state.new_lua_flow().unwrap();
    let result = send(&state, &mut writer, &flow, b"abcdef");
    let mut bytes = [0; 6];
    reader.read_exact(&mut bytes).unwrap();
    assert_eq!(&bytes, b"abcdef");
    assert_eq!(result.execution_receipt.real_writes_committed, 2);
    assert_eq!(result.bytes_committed, 6);
}

#[test]
fn lua_nonroot_tcp_keeps_state_and_releases_closed_flows() {
    let (state, _dir) =
        lua_runtime("function candidate(d) d.conn.n=(d.conn.n or 0)+1; return tostring(d.conn.n)..d.dis.payload end");
    let (mut writer, mut reader) = connected_pair();
    let flow = state.new_lua_flow().unwrap();
    send(&state, &mut writer, &flow, b"ab");
    send(&state, &mut writer, &flow, b"cd");
    let other = state.new_lua_flow().unwrap();
    send(&state, &mut writer, &other, b"ef");
    let mut bytes = [0; 9];
    reader.read_exact(&mut bytes).unwrap();
    assert_eq!(&bytes, b"1ab2cd1ef");
    drop(flow);
    drop(other);
    for _ in 0..1100 {
        let flow = state.new_lua_flow().unwrap();
        send(&state, &mut writer, &flow, b"x");
        let mut bytes = [0; 2];
        reader.read_exact(&mut bytes).unwrap();
        assert_eq!(&bytes, b"1x", "closed flows must release the bounded Lua state slot");
    }
}

#[test]
fn lua_nonroot_tcp_unsupported_plan_sends_only_plain_payload() {
    let (state, _dir) = lua_runtime("function candidate(d) d.split(2,false); d.fake(3); return VERDICT_MODIFY end");
    let (mut writer, mut reader) = connected_pair();
    let flow = state.new_lua_flow().unwrap();
    let result = send(&state, &mut writer, &flow, b"abcdef");
    let mut bytes = [0; 6];
    reader.read_exact(&mut bytes).unwrap();
    assert_eq!(&bytes, b"abcdef");
    assert_eq!(result.execution_receipt.real_writes_committed, 1);
    reader.set_nonblocking(true).unwrap();
    assert_eq!(reader.read(&mut bytes).unwrap_err().kind(), std::io::ErrorKind::WouldBlock);
}

#[test]
fn lua_nonroot_tcp_keeps_logical_destination_after_socks_first_write() {
    let (state, _dir) = lua_runtime_with_matcher(
        "function candidate(d) d.conn.n=(d.conn.n or 0)+1; return tostring(d.conn.n)..d.dis.payload end",
        "    match:\n      port: [443]\n",
    );
    let (mut writer, mut reader) = connected_pair();
    assert_ne!(writer.peer_addr().unwrap().port(), 443);
    let flow = state.new_lua_flow().unwrap();
    state
        .send_tcp_desync_payload(
            &mut writer,
            DesyncSendRequest {
                lua_flow: Some(&flow),
                group_index: 0,
                group_override: None,
                payload: b"ab",
                progress: OutboundProgress { round: 1, payload_size: 2, stream_start: 0, stream_end: 1 },
                host: None,
                target: "127.0.0.1:443".parse().unwrap(),
            },
        )
        .unwrap();
    // Steady relay supplies the physical SOCKS peer; the Lua flow keeps :443.
    send(&state, &mut writer, &flow, b"cd");
    let mut bytes = [0; 6];
    reader.read_exact(&mut bytes).unwrap();
    assert_eq!(&bytes, b"1ab2cd");
}

#[cfg(any(target_os = "linux", target_os = "android"))]
#[test]
fn lua_nonroot_tcp_reads_physical_socket_mss_on_each_send() {
    let (state, _dir) = lua_runtime_with_matcher(
        "function candidate(d) return tostring(d.tcp_mss)..':'..d.dis.payload end",
        "    match:\n      port: [443]\n",
    );
    let flow = state.new_lua_flow().unwrap();
    for requested in [536, 1200] {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        socket2::SockRef::from(&listener).set_tcp_mss(requested).unwrap();
        let mut writer = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (mut reader, _) = listener.accept().unwrap();
        reader.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let mss = socket2::SockRef::from(&writer).tcp_mss().unwrap();
        assert!(mss > 0 && mss <= requested);
        assert_ne!(mss, 1460);
        assert_ne!(writer.peer_addr().unwrap().port(), 443);
        eprintln!("requested MSS {requested}; actual sending MSS {mss}");
        for payload in [b"first".as_slice(), b"steady"] {
            let target =
                if payload == b"first" { "127.0.0.1:443".parse().unwrap() } else { writer.peer_addr().unwrap() };
            state
                .send_tcp_desync_payload(
                    &mut writer,
                    DesyncSendRequest {
                        lua_flow: Some(&flow),
                        group_index: 0,
                        group_override: None,
                        payload,
                        progress: OutboundProgress {
                            round: 1,
                            payload_size: payload.len(),
                            stream_start: 0,
                            stream_end: payload.len() - 1,
                        },
                        host: None,
                        target,
                    },
                )
                .unwrap();
            let expected = [format!("{mss}:").as_bytes(), payload].concat();
            let mut received = vec![0; expected.len()];
            reader.read_exact(&mut received).unwrap();
            assert_eq!(received, expected);
        }
    }
}
