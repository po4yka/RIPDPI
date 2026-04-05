use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use android_support::{init_android_logging, JNI_VERSION};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, JavaVM, Outcome};
use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};

static NEXT_HANDLE: Lazy<Mutex<u64>> = Lazy::new(|| Mutex::new(1));
static SESSIONS: Lazy<Mutex<HashMap<u64, Arc<RelaySession>>>> = Lazy::new(|| Mutex::new(HashMap::new()));

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RelayConfig {
    enabled: bool,
    kind: String,
    profile_id: String,
    server: String,
    server_port: i32,
    server_name: String,
    reality_public_key: String,
    reality_short_id: String,
    chain_entry_server: String,
    chain_entry_port: i32,
    chain_entry_server_name: String,
    chain_entry_public_key: String,
    chain_entry_short_id: String,
    chain_exit_server: String,
    chain_exit_port: i32,
    chain_exit_server_name: String,
    chain_exit_public_key: String,
    chain_exit_short_id: String,
    masque_url: String,
    masque_use_http2_fallback: bool,
    masque_cloudflare_mode: bool,
    local_socks_host: String,
    local_socks_port: i32,
    udp_enabled: bool,
    tcp_fallback_enabled: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RelayTelemetry {
    source: &'static str,
    state: String,
    health: String,
    active_sessions: u64,
    total_sessions: u64,
    listener_address: Option<String>,
    upstream_address: Option<String>,
    last_target: Option<String>,
    last_error: Option<String>,
    captured_at: u64,
}

struct RelaySession {
    config: RelayConfig,
    running: AtomicBool,
    stop_requested: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    listener_address: Mutex<Option<String>>,
    last_target: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
}

impl RelaySession {
    fn telemetry(&self) -> RelayTelemetry {
        RelayTelemetry {
            source: "relay",
            state: if self.running.load(Ordering::SeqCst) {
                "running".to_string()
            } else {
                "idle".to_string()
            },
            health: if self.running.load(Ordering::SeqCst) {
                "running".to_string()
            } else {
                "idle".to_string()
            },
            active_sessions: self.active_sessions.load(Ordering::SeqCst),
            total_sessions: self.total_sessions.load(Ordering::SeqCst),
            listener_address: self.listener_address.lock().expect("listener address").clone(),
            upstream_address: Some(describe_upstream(&self.config)),
            last_target: self.last_target.lock().expect("last target").clone(),
            last_error: self.last_error.lock().expect("last error").clone(),
            captured_at: now_ms(),
        }
    }
}

/// # Safety
#[unsafe(no_mangle)]
#[allow(improper_ctypes_definitions)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    match std::panic::catch_unwind(|| {
        android_support::ignore_sigpipe();
        init_android_logging("ripdpi-relay-native");
        android_support::install_panic_hook();
        JNI_VERSION
    }) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniCreate(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    match env
        .with_env(move |env| -> jni::errors::Result<jlong> {
            let config_json: String = env.get_string(&config_json)?.into();
            let Ok(config) = serde_json::from_str::<RelayConfig>(&config_json) else {
                return Ok(0);
            };
            let handle = {
                let mut next = NEXT_HANDLE.lock().expect("handle mutex");
                let value = *next;
                *next += 1;
                value
            };
            SESSIONS.lock().expect("session mutex").insert(
                handle,
                Arc::new(RelaySession {
                    config,
                    running: AtomicBool::new(false),
                    stop_requested: AtomicBool::new(false),
                    active_sessions: AtomicU64::new(0),
                    total_sessions: AtomicU64::new(0),
                    listener_address: Mutex::new(None),
                    last_target: Mutex::new(None),
                    last_error: Mutex::new(None),
                }),
            );
            Ok(jlong::try_from(handle).unwrap_or(0))
        })
        .into_outcome()
    {
        Outcome::Ok(handle) => handle,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniStart(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    let Some(session) = session_from_handle(handle) else {
        return 1;
    };
    let bind_addr = format!("{}:{}", session.config.local_socks_host, session.config.local_socks_port);
    let listener = match TcpListener::bind(&bind_addr) {
        Ok(listener) => listener,
        Err(error) => {
            *session.last_error.lock().expect("last error") = Some(error.to_string());
            return 2;
        }
    };
    let _ = listener.set_nonblocking(true);
    *session.listener_address.lock().expect("listener address") = Some(bind_addr);
    session.running.store(true, Ordering::SeqCst);

    while !session.stop_requested.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((stream, _)) => {
                let session = Arc::clone(&session);
                thread::spawn(move || handle_client(session, stream));
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(50));
            }
            Err(error) => {
                *session.last_error.lock().expect("last error") = Some(error.to_string());
                thread::sleep(Duration::from_millis(100));
            }
        }
    }

    session.running.store(false, Ordering::SeqCst);
    0
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniStop(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    if let Some(session) = session_from_handle(handle) {
        session.stop_requested.store(true, Ordering::SeqCst);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniPollTelemetry(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let payload = session_from_handle(handle)
                .and_then(|session| serde_json::to_string(&session.telemetry()).ok())
                .unwrap_or_else(|| "{\"source\":\"relay\",\"state\":\"idle\",\"health\":\"idle\",\"capturedAt\":0}".to_string());
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniDestroy(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    if let Ok(handle) = u64::try_from(handle) {
        SESSIONS.lock().expect("session mutex").remove(&handle);
    }
}

fn session_from_handle(handle: jlong) -> Option<Arc<RelaySession>> {
    let handle = u64::try_from(handle).ok()?;
    SESSIONS.lock().expect("session mutex").get(&handle).cloned()
}

fn handle_client(session: Arc<RelaySession>, mut client: TcpStream) {
    session.active_sessions.fetch_add(1, Ordering::SeqCst);
    session.total_sessions.fetch_add(1, Ordering::SeqCst);
    let result = handle_client_inner(&session, &mut client);
    if let Err(error) = result {
        *session.last_error.lock().expect("last error") = Some(error);
    }
    session.active_sessions.fetch_sub(1, Ordering::SeqCst);
}

fn handle_client_inner(session: &RelaySession, client: &mut TcpStream) -> Result<(), String> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting).map_err(|err| err.to_string())?;
    if greeting[0] != 0x05 {
        return Err("unsupported socks version".to_string());
    }
    let methods_len = usize::from(greeting[1]);
    let mut methods = vec![0u8; methods_len];
    client.read_exact(&mut methods).map_err(|err| err.to_string())?;
    client.write_all(&[0x05, 0x00]).map_err(|err| err.to_string())?;

    let mut header = [0u8; 4];
    client.read_exact(&mut header).map_err(|err| err.to_string())?;
    if header[1] != 0x01 {
        client.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).ok();
        return Err("unsupported socks command".to_string());
    }
    let target = read_target(client, header[3])?;
    *session.last_target.lock().expect("last target") = Some(target.to_string());

    let upstream = TcpStream::connect_timeout(&target, Duration::from_secs(10)).map_err(|err| err.to_string())?;
    let bound = upstream.local_addr().unwrap_or_else(|_| SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0));
    client
        .write_all(&encode_success_reply(bound))
        .map_err(|err| err.to_string())?;

    proxy_bidirectional(client, upstream).map_err(|err| err.to_string())
}

fn read_target(stream: &mut TcpStream, atyp: u8) -> Result<SocketAddr, String> {
    match atyp {
        0x01 => {
            let mut ip = [0u8; 4];
            let mut port = [0u8; 2];
            stream.read_exact(&mut ip).map_err(|err| err.to_string())?;
            stream.read_exact(&mut port).map_err(|err| err.to_string())?;
            Ok(SocketAddr::new(
                IpAddr::V4(Ipv4Addr::from(ip)),
                u16::from_be_bytes(port),
            ))
        }
        0x04 => {
            let mut ip = [0u8; 16];
            let mut port = [0u8; 2];
            stream.read_exact(&mut ip).map_err(|err| err.to_string())?;
            stream.read_exact(&mut port).map_err(|err| err.to_string())?;
            Ok(SocketAddr::new(
                IpAddr::V6(Ipv6Addr::from(ip)),
                u16::from_be_bytes(port),
            ))
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).map_err(|err| err.to_string())?;
            let mut host = vec![0u8; usize::from(len[0])];
            let mut port = [0u8; 2];
            stream.read_exact(&mut host).map_err(|err| err.to_string())?;
            stream.read_exact(&mut port).map_err(|err| err.to_string())?;
            let host = String::from_utf8(host).map_err(|err| err.to_string())?;
            let port = u16::from_be_bytes(port);
            (host.as_str(), port)
                .to_socket_addrs()
                .map_err(|err| err.to_string())?
                .next()
                .ok_or_else(|| "domain resolution failed".to_string())
        }
        _ => Err("unsupported address type".to_string()),
    }
}

fn encode_success_reply(bound: SocketAddr) -> Vec<u8> {
    match bound {
        SocketAddr::V4(addr) => {
            let mut reply = vec![0x05, 0x00, 0x00, 0x01];
            reply.extend_from_slice(&addr.ip().octets());
            reply.extend_from_slice(&addr.port().to_be_bytes());
            reply
        }
        SocketAddr::V6(addr) => {
            let mut reply = vec![0x05, 0x00, 0x00, 0x04];
            reply.extend_from_slice(&addr.ip().octets());
            reply.extend_from_slice(&addr.port().to_be_bytes());
            reply
        }
    }
}

fn proxy_bidirectional(client: &mut TcpStream, upstream: TcpStream) -> std::io::Result<()> {
    let mut upstream_reader = upstream.try_clone()?;
    let mut upstream_writer = upstream;
    let mut client_reader = client.try_clone()?;
    let mut client_writer = client.try_clone()?;

    let forward = thread::spawn(move || {
        let _ = std::io::copy(&mut client_reader, &mut upstream_writer);
        let _ = upstream_writer.shutdown(Shutdown::Write);
    });
    let _ = std::io::copy(&mut upstream_reader, &mut client_writer);
    let _ = client_writer.shutdown(Shutdown::Write);
    let _ = forward.join();
    Ok(())
}

fn describe_upstream(config: &RelayConfig) -> String {
    let _ = config.enabled;
    let _ = config.profile_id.as_str();
    let _ = config.server_name.as_str();
    let _ = config.reality_public_key.as_str();
    let _ = config.reality_short_id.as_str();
    let _ = config.chain_entry_server_name.as_str();
    let _ = config.chain_entry_public_key.as_str();
    let _ = config.chain_entry_short_id.as_str();
    let _ = config.chain_exit_server_name.as_str();
    let _ = config.chain_exit_public_key.as_str();
    let _ = config.chain_exit_short_id.as_str();
    let _ = config.masque_use_http2_fallback;
    let _ = config.masque_cloudflare_mode;
    let _ = config.udp_enabled;
    let _ = config.tcp_fallback_enabled;
    match config.kind.as_str() {
        "chain_relay" if !config.chain_entry_server.is_empty() || !config.chain_exit_server.is_empty() => format!(
            "{}:{} -> {}:{}",
            config.chain_entry_server,
            config.chain_entry_port,
            config.chain_exit_server,
            config.chain_exit_port
        ),
        "masque" if !config.masque_url.is_empty() => config.masque_url.clone(),
        _ if !config.server.is_empty() => format!("{}:{}", config.server, config.server_port),
        _ => "direct".to_string(),
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
