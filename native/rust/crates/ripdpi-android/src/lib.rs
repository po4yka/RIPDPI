use std::net::IpAddr;
use std::os::fd::AsRawFd;
use std::str::FromStr;
use std::sync::Mutex;

use android_support::{
    init_android_logging, throw_illegal_argument, throw_illegal_state, throw_io_exception,
    throw_runtime_exception, HandleRegistry, JNI_VERSION,
};
use ciadpi_config::{
    DesyncGroup, DesyncMode, OffsetExpr, PartSpec, RuntimeConfig, StartupEnv, OFFSET_HOST,
    OFFSET_SNI,
};
use ciadpi_packets::{IS_HTTP, IS_HTTPS, IS_UDP, MH_DMIX, MH_HMIX, MH_SPACE};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use ripdpi_monitor::{MonitorSession, ScanRequest};
use ripdpi_runtime::{process, runtime};
use serde::Deserialize;

const HOSTS_DISABLE: &str = "disable";
const HOSTS_BLACKLIST: &str = "blacklist";
const HOSTS_WHITELIST: &str = "whitelist";

static SESSIONS: once_cell::sync::Lazy<HandleRegistry<ProxySession>> =
    once_cell::sync::Lazy::new(HandleRegistry::new);
static DIAGNOSTIC_SESSIONS: once_cell::sync::Lazy<HandleRegistry<MonitorSession>> =
    once_cell::sync::Lazy::new(HandleRegistry::new);

struct ProxySession {
    config: RuntimeConfig,
    state: Mutex<ProxySessionState>,
}

enum ProxySessionState {
    Idle,
    Running { listener_fd: i32 },
}

#[derive(Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum ProxyConfigPayload {
    CommandLine { args: Vec<String> },
    Ui(ProxyUiConfig),
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProxyUiConfig {
    ip: String,
    port: i32,
    max_connections: i32,
    buffer_size: i32,
    default_ttl: i32,
    custom_ttl: bool,
    no_domain: bool,
    desync_http: bool,
    desync_https: bool,
    desync_udp: bool,
    desync_method: String,
    split_position: i32,
    split_at_host: bool,
    fake_ttl: i32,
    fake_sni: String,
    oob_char: u8,
    host_mixed_case: bool,
    domain_mixed_case: bool,
    host_remove_spaces: bool,
    tls_record_split: bool,
    tls_record_split_position: i32,
    tls_record_split_at_sni: bool,
    hosts_mode: String,
    hosts: Option<String>,
    tcp_fast_open: bool,
    udp_fake_count: i32,
    drop_sack: bool,
    fake_offset: i32,
}

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    init_android_logging("ripdpi-native");
    JNI_VERSION
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxy_jniCreate(
    mut env: JNIEnv,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        create_session(&mut env, config_json)
    }))
    .unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Proxy session creation panicked");
        0
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxy_jniStart(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        start_session(&mut env, handle)
    }))
    .unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Proxy session start panicked");
        libc::EINVAL
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxy_jniStop(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        stop_session(&mut env, handle)
    }))
    .map_err(|_| throw_runtime_exception(&mut env, "Proxy session stop panicked"));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxy_jniDestroy(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        destroy_session(&mut env, handle)
    }))
    .map_err(|_| throw_runtime_exception(&mut env, "Proxy session destroy panicked"));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NetworkDiagnostics_jniCreate(
    mut env: JNIEnv,
    _thiz: JObject,
) -> jlong {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        DIAGNOSTIC_SESSIONS.insert(MonitorSession::new()) as jlong
    }))
    .unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Diagnostics session creation panicked");
        0
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NetworkDiagnostics_jniStartScan(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
    request_json: JString,
    session_id: JString,
) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        start_diagnostics_scan(&mut env, handle, request_json, session_id)
    }))
    .map_err(|_| throw_runtime_exception(&mut env, "Diagnostics scan start panicked"));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NetworkDiagnostics_jniCancelScan(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        diagnostics_session(&mut env, handle)?.cancel_scan();
        Some(())
    }))
    .map_err(|_| throw_runtime_exception(&mut env, "Diagnostics cancel panicked"));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NetworkDiagnostics_jniPollProgress(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        poll_diagnostics_string(&mut env, handle, |session| session.poll_progress_json())
    }))
    .unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Diagnostics progress polling panicked");
        std::ptr::null_mut()
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NetworkDiagnostics_jniTakeReport(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        poll_diagnostics_string(&mut env, handle, |session| session.take_report_json())
    }))
    .unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Diagnostics report polling panicked");
        std::ptr::null_mut()
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NetworkDiagnostics_jniPollPassiveEvents(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jstring {
    init_android_logging("ripdpi-native");
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        poll_diagnostics_string(&mut env, handle, |session| session.poll_passive_events_json())
    }))
    .unwrap_or_else(|_| {
        throw_runtime_exception(&mut env, "Diagnostics passive polling panicked");
        std::ptr::null_mut()
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_NetworkDiagnostics_jniDestroy(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) {
    init_android_logging("ripdpi-native");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        destroy_diagnostics_session(&mut env, handle)
    }))
    .map_err(|_| throw_runtime_exception(&mut env, "Diagnostics session destroy panicked"));
}

fn create_session(env: &mut JNIEnv, config_json: JString) -> jlong {
    let json: String = match env.get_string(&config_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid proxy config payload");
            return 0;
        }
    };

    let payload = match serde_json::from_str::<ProxyConfigPayload>(&json) {
        Ok(payload) => payload,
        Err(err) => {
            throw_illegal_argument(env, format!("Invalid proxy config JSON: {err}"));
            return 0;
        }
    };

    let config = match runtime_config_from_payload(payload) {
        Ok(config) => config,
        Err(message) => {
            throw_illegal_argument(env, message);
            return 0;
        }
    };

    if let Err(err) = runtime::create_listener(&config) {
        throw_io_exception(env, format!("Failed to prepare proxy listener: {err}"));
        return 0;
    }

    SESSIONS.insert(ProxySession {
        config,
        state: Mutex::new(ProxySessionState::Idle),
    }) as jlong
}

fn start_session(env: &mut JNIEnv, handle: jlong) -> jint {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid proxy handle");
            return libc::EINVAL;
        }
    };
    let Some(session) = SESSIONS.get(handle) else {
        throw_illegal_argument(env, "Unknown proxy handle");
        return libc::EINVAL;
    };

    let config = session.config.clone();
    let listener = match runtime::create_listener(&config) {
        Ok(listener) => listener,
        Err(err) => {
            throw_io_exception(env, format!("Failed to open proxy listener: {err}"));
            return libc::EINVAL;
        }
    };
    let listener_fd = listener.as_raw_fd();

    {
        let mut state = session.state.lock().expect("proxy session poisoned");
        match *state {
            ProxySessionState::Idle => {
                *state = ProxySessionState::Running { listener_fd };
            }
            ProxySessionState::Running { .. } => {
                throw_illegal_state(env, "Proxy session is already running");
                return libc::EINVAL;
            }
        }
    }

    process::prepare_embedded();
    let result = runtime::run_proxy_with_listener(config, listener);

    let mut state = session.state.lock().expect("proxy session poisoned");
    *state = ProxySessionState::Idle;

    match result {
        Ok(()) => 0,
        Err(err) => positive_os_error(&err, libc::EINVAL),
    }
}

fn stop_session(env: &mut JNIEnv, handle: jlong) {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid proxy handle");
            return;
        }
    };
    let Some(session) = SESSIONS.get(handle) else {
        throw_illegal_argument(env, "Unknown proxy handle");
        return;
    };

    let listener_fd = {
        let state = session.state.lock().expect("proxy session poisoned");
        match *state {
            ProxySessionState::Idle => {
                throw_illegal_state(env, "Proxy session is not running");
                return;
            }
            ProxySessionState::Running { listener_fd } => listener_fd,
        }
    };

    process::request_shutdown();
    let rc = unsafe { libc::shutdown(listener_fd, libc::SHUT_RDWR) };
    if rc != 0 {
        throw_io_exception(
            env,
            format!(
                "Failed to stop proxy listener: {}",
                std::io::Error::last_os_error()
            ),
        );
    }
}

fn destroy_session(env: &mut JNIEnv, handle: jlong) {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid proxy handle");
            return;
        }
    };
    let Some(session) = SESSIONS.get(handle) else {
        throw_illegal_argument(env, "Unknown proxy handle");
        return;
    };
    let state = session.state.lock().expect("proxy session poisoned");
    if matches!(*state, ProxySessionState::Running { .. }) {
        throw_illegal_state(env, "Cannot destroy a running proxy session");
        return;
    }
    drop(state);
    let _ = SESSIONS.remove(handle);
}

fn runtime_config_from_payload(payload: ProxyConfigPayload) -> Result<RuntimeConfig, String> {
    match payload {
        ProxyConfigPayload::CommandLine { args } => runtime_config_from_command_line(args),
        ProxyConfigPayload::Ui(config) => runtime_config_from_ui(config),
    }
}

fn runtime_config_from_command_line(mut args: Vec<String>) -> Result<RuntimeConfig, String> {
    if args.first().is_some_and(|value| !value.starts_with('-')) {
        args.remove(0);
    }

    let parsed = ciadpi_config::parse_cli(&args, &StartupEnv::default())
        .map_err(|err| format!("Invalid command-line proxy config: {}", err.option))?;

    match parsed {
        ciadpi_config::ParseResult::Run(config) => Ok(config),
        _ => Err("Command-line proxy config must resolve to a runnable config".to_string()),
    }
}

fn runtime_config_from_ui(payload: ProxyUiConfig) -> Result<RuntimeConfig, String> {
    let listen_ip = IpAddr::from_str(&payload.ip).map_err(|_| "Invalid proxy IP".to_string())?;
    let mut config = RuntimeConfig::default();
    config.listen.listen_ip = listen_ip;
    config.listen.listen_port =
        u16::try_from(payload.port).map_err(|_| "Invalid proxy port".to_string())?;
    if config.listen.listen_port == 0 {
        return Err("Invalid proxy port".to_string());
    }
    if payload.max_connections <= 0 {
        return Err("maxConnections must be positive".to_string());
    }
    config.max_open = payload.max_connections;
    config.buffer_size =
        usize::try_from(payload.buffer_size).map_err(|_| "Invalid bufferSize".to_string())?;
    if config.buffer_size == 0 {
        return Err("bufferSize must be positive".to_string());
    }
    if payload.udp_fake_count < 0 {
        return Err("udpFakeCount must be non-negative".to_string());
    }
    config.resolve = !payload.no_domain;
    config.tfo = payload.tcp_fast_open;
    if payload.custom_ttl {
        let ttl =
            u8::try_from(payload.default_ttl).map_err(|_| "Invalid defaultTtl".to_string())?;
        if ttl == 0 {
            return Err("defaultTtl must be positive when customTtl is enabled".to_string());
        }
        config.default_ttl = ttl;
        config.custom_ttl = true;
    }

    let mut groups = Vec::new();
    if payload.hosts_mode == HOSTS_WHITELIST {
        let mut whitelist = DesyncGroup::new(0);
        whitelist.filters.hosts = parse_hosts(payload.hosts.as_deref())?;
        groups.push(whitelist);
    }

    let mut group = DesyncGroup::new(groups.len());
    match payload.hosts_mode.as_str() {
        HOSTS_DISABLE | HOSTS_WHITELIST => {}
        HOSTS_BLACKLIST => {
            group.filters.hosts = parse_hosts(payload.hosts.as_deref())?;
        }
        _ => return Err("Unknown hostsMode".to_string()),
    }

    if payload.fake_ttl > 0 {
        group.ttl =
            Some(u8::try_from(payload.fake_ttl).map_err(|_| "Invalid fakeTtl".to_string())?);
    }
    group.udp_fake_count = payload.udp_fake_count;
    group.drop_sack = payload.drop_sack;
    group.proto = (u32::from(payload.desync_http) * IS_HTTP)
        | (u32::from(payload.desync_https) * IS_HTTPS)
        | (u32::from(payload.desync_udp) * IS_UDP);
    group.mod_http = (u32::from(payload.host_mixed_case) * MH_HMIX)
        | (u32::from(payload.domain_mixed_case) * MH_DMIX)
        | (u32::from(payload.host_remove_spaces) * MH_SPACE);

    let offset_flag = if group.proto != 0 || payload.desync_https {
        OFFSET_SNI
    } else {
        OFFSET_HOST
    };
    let part_offset = OffsetExpr {
        pos: i64::from(payload.split_position),
        flag: if payload.split_at_host {
            offset_flag
        } else {
            0
        },
        repeats: 0,
        skip: 0,
    };
    let desync_mode = parse_desync_mode(&payload.desync_method)?;
    group.parts.push(PartSpec {
        mode: desync_mode,
        offset: part_offset,
    });

    if payload.tls_record_split {
        group.tls_records.push(OffsetExpr {
            pos: i64::from(payload.tls_record_split_position),
            flag: if payload.tls_record_split_at_sni {
                offset_flag
            } else {
                0
            },
            repeats: 0,
            skip: 0,
        });
    }

    if desync_mode == DesyncMode::Fake {
        group.fake_offset = Some(OffsetExpr {
            pos: i64::from(payload.fake_offset),
            flag: 0,
            repeats: 0,
            skip: 0,
        });
        group.fake_sni_list.push(payload.fake_sni);
    }

    if desync_mode == DesyncMode::Oob {
        group.oob_data = Some(payload.oob_char);
    }

    let action_proto = group.proto;
    groups.push(group);
    if action_proto != 0 {
        groups.push(DesyncGroup::new(groups.len()));
    }

    config.groups = groups;
    if !matches!(config.listen.bind_ip, IpAddr::V6(_)) {
        config.ipv6 = false;
    }

    Ok(config)
}

fn parse_desync_mode(value: &str) -> Result<DesyncMode, String> {
    match value {
        "none" => Ok(DesyncMode::None),
        "split" => Ok(DesyncMode::Split),
        "disorder" => Ok(DesyncMode::Disorder),
        "fake" => Ok(DesyncMode::Fake),
        "oob" => Ok(DesyncMode::Oob),
        "disoob" => Ok(DesyncMode::Disoob),
        _ => Err("Unknown desyncMethod".to_string()),
    }
}

fn parse_hosts(hosts: Option<&str>) -> Result<Vec<String>, String> {
    let hosts = hosts.unwrap_or_default();
    ciadpi_config::parse_hosts_spec(hosts).map_err(|_| "Invalid hosts list".to_string())
}

fn diagnostics_session(env: &mut JNIEnv, handle: jlong) -> Option<std::sync::Arc<MonitorSession>> {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid diagnostics handle");
            return None;
        }
    };
    let Some(session) = DIAGNOSTIC_SESSIONS.get(handle) else {
        throw_illegal_argument(env, "Unknown diagnostics handle");
        return None;
    };
    Some(session)
}

fn start_diagnostics_scan(
    env: &mut JNIEnv,
    handle: jlong,
    request_json: JString,
    session_id: JString,
) {
    let Some(session) = diagnostics_session(env, handle) else {
        return;
    };
    let request_json: String = match env.get_string(&request_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid diagnostics request JSON");
            return;
        }
    };
    let session_id: String = match env.get_string(&session_id) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid diagnostics session id");
            return;
        }
    };
    let request: ScanRequest = match serde_json::from_str(&request_json) {
        Ok(request) => request,
        Err(err) => {
            throw_illegal_argument(env, format!("Invalid diagnostics request: {err}"));
            return;
        }
    };
    if let Err(err) = session.start_scan(session_id, request) {
        throw_illegal_state(env, err);
    }
}

fn poll_diagnostics_string<F>(
    env: &mut JNIEnv,
    handle: jlong,
    op: F,
) -> jstring
where
    F: FnOnce(&MonitorSession) -> Result<Option<String>, String>,
{
    let Some(session) = diagnostics_session(env, handle) else {
        return std::ptr::null_mut();
    };
    match op(&session) {
        Ok(Some(value)) => env
            .new_string(value)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Ok(None) => std::ptr::null_mut(),
        Err(err) => {
            throw_runtime_exception(env, err);
            std::ptr::null_mut()
        }
    }
}

fn destroy_diagnostics_session(env: &mut JNIEnv, handle: jlong) {
    let handle = match to_handle(handle) {
        Some(handle) => handle,
        None => {
            throw_illegal_argument(env, "Invalid diagnostics handle");
            return;
        }
    };
    let Some(session) = DIAGNOSTIC_SESSIONS.remove(handle) else {
        throw_illegal_argument(env, "Unknown diagnostics handle");
        return;
    };
    session.destroy();
}

fn to_handle(value: jlong) -> Option<u64> {
    u64::try_from(value).ok().filter(|handle| *handle != 0)
}

fn positive_os_error(err: &std::io::Error, fallback: i32) -> i32 {
    err.raw_os_error().unwrap_or(fallback)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ui_config_payload() {
        let payload = ProxyConfigPayload::Ui(ProxyUiConfig {
            ip: "127.0.0.1".to_string(),
            port: 1080,
            max_connections: 512,
            buffer_size: 16384,
            default_ttl: 0,
            custom_ttl: false,
            no_domain: false,
            desync_http: true,
            desync_https: true,
            desync_udp: false,
            desync_method: "disorder".to_string(),
            split_position: 1,
            split_at_host: false,
            fake_ttl: 8,
            fake_sni: "www.iana.org".to_string(),
            oob_char: b'a',
            host_mixed_case: false,
            domain_mixed_case: false,
            host_remove_spaces: false,
            tls_record_split: false,
            tls_record_split_position: 0,
            tls_record_split_at_sni: false,
            hosts_mode: HOSTS_DISABLE.to_string(),
            hosts: None,
            tcp_fast_open: false,
            udp_fake_count: 0,
            drop_sack: false,
            fake_offset: 0,
        });

        let config = runtime_config_from_payload(payload).expect("ui config");
        assert_eq!(config.listen.listen_port, 1080);
        assert_eq!(config.groups.len(), 2);
    }

    #[test]
    fn rejects_invalid_handle() {
        assert!(to_handle(0).is_none());
        assert!(to_handle(-1).is_none());
    }
}
