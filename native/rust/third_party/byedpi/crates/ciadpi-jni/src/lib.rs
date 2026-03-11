use std::collections::HashMap;
use std::net::IpAddr;
use std::os::fd::{FromRawFd, IntoRawFd};
use std::str::FromStr;
use std::sync::Mutex;

use ciadpi_config::{DesyncGroup, DesyncMode, OffsetExpr, PartSpec, RuntimeConfig};
use ciadpi_packets::{IS_HTTP, IS_HTTPS, IS_UDP, MH_DMIX, MH_HMIX, MH_SPACE};
use jni::objects::{JObject, JObjectArray, JString};
use jni::sys::jint;
use jni::JNIEnv;
use once_cell::sync::Lazy;

#[path = "../../ciadpi-bin/src/platform/mod.rs"]
mod platform;
#[path = "../../ciadpi-bin/src/process.rs"]
mod process;
#[path = "../../ciadpi-bin/src/runtime.rs"]
mod runtime;
#[path = "../../ciadpi-bin/src/runtime_policy.rs"]
mod runtime_policy;

const HOSTS_DISABLE: jint = 0;
const HOSTS_BLACKLIST: jint = 1;
const HOSTS_WHITELIST: jint = 2;

const DESYNC_NONE: jint = 0;
const DESYNC_SPLIT: jint = 1;
const DESYNC_DISORDER: jint = 2;
const DESYNC_FAKE: jint = 3;
const DESYNC_OOB: jint = 4;
const DESYNC_DISOOB: jint = 5;

static CONFIGS: Lazy<Mutex<HashMap<i32, RuntimeConfig>>> = Lazy::new(|| Mutex::new(HashMap::new()));

#[no_mangle]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxy_jniCreateSocketWithCommandLine(
    mut env: JNIEnv,
    _thiz: JObject,
    args: JObjectArray,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        create_socket_with_command_line(&mut env, args)
    }))
    .unwrap_or(-libc::EINVAL)
}

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxy_jniCreateSocket(
    mut env: JNIEnv,
    _thiz: JObject,
    ip: JString,
    port: jint,
    max_connections: jint,
    buffer_size: jint,
    default_ttl: jint,
    custom_ttl: u8,
    no_domain: u8,
    desync_http: u8,
    desync_https: u8,
    desync_udp: u8,
    desync_method: jint,
    split_position: jint,
    split_at_host: u8,
    fake_ttl: jint,
    fake_sni: JString,
    custom_oob_char: i8,
    host_mixed_case: u8,
    domain_mixed_case: u8,
    host_remove_spaces: u8,
    tls_record_split: u8,
    tls_record_split_position: jint,
    tls_record_split_at_sni: u8,
    hosts_mode: jint,
    hosts: JObject,
    tfo: u8,
    udp_fake_count: jint,
    drop_sack: u8,
    fake_offset: jint,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        create_socket_from_ui(
            &mut env,
            ip,
            port,
            max_connections,
            buffer_size,
            default_ttl,
            custom_ttl != 0,
            no_domain != 0,
            desync_http != 0,
            desync_https != 0,
            desync_udp != 0,
            desync_method,
            split_position,
            split_at_host != 0,
            fake_ttl,
            fake_sni,
            custom_oob_char as u8,
            host_mixed_case != 0,
            domain_mixed_case != 0,
            host_remove_spaces != 0,
            tls_record_split != 0,
            tls_record_split_position,
            tls_record_split_at_sni != 0,
            hosts_mode,
            hosts,
            tfo != 0,
            udp_fake_count,
            drop_sack != 0,
            fake_offset,
        )
    }))
    .unwrap_or(-libc::EINVAL)
}

#[no_mangle]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxy_jniStartProxy(
    _env: JNIEnv,
    _thiz: JObject,
    fd: jint,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| start_proxy(fd)))
        .unwrap_or(libc::EINVAL)
}

#[no_mangle]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxy_jniStopProxy(
    _env: JNIEnv,
    _thiz: JObject,
    fd: jint,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| stop_proxy(fd)))
        .unwrap_or(libc::EINVAL)
}

fn create_socket_with_command_line(env: &mut JNIEnv, args: JObjectArray) -> jint {
    let args = match read_string_array(env, args) {
        Ok(args) => args,
        Err(code) => return code,
    };
    let args = normalize_args(args);
    let parsed = match ciadpi_config::parse_cli(&args, &ciadpi_config::StartupEnv::default()) {
        Ok(ciadpi_config::ParseResult::Run(config)) => config,
        Ok(_) => return -libc::EINVAL,
        Err(_) => return -libc::EINVAL,
    };
    create_socket_from_config(parsed)
}

#[allow(clippy::too_many_arguments)]
fn create_socket_from_ui(
    env: &mut JNIEnv,
    ip: JString,
    port: jint,
    max_connections: jint,
    buffer_size: jint,
    default_ttl: jint,
    custom_ttl: bool,
    no_domain: bool,
    desync_http: bool,
    desync_https: bool,
    desync_udp: bool,
    desync_method: jint,
    split_position: jint,
    split_at_host: bool,
    fake_ttl: jint,
    fake_sni: JString,
    custom_oob_char: u8,
    host_mixed_case: bool,
    domain_mixed_case: bool,
    host_remove_spaces: bool,
    tls_record_split: bool,
    tls_record_split_position: jint,
    tls_record_split_at_sni: bool,
    hosts_mode: jint,
    hosts: JObject,
    tfo: bool,
    udp_fake_count: jint,
    drop_sack: bool,
    fake_offset: jint,
) -> jint {
    let ip = match env.get_string(&ip) {
        Ok(value) => String::from(value),
        Err(_) => return -libc::EINVAL,
    };
    let listen_ip = match IpAddr::from_str(&ip) {
        Ok(value) => value,
        Err(_) => return -libc::EINVAL,
    };

    let fake_sni = match env.get_string(&fake_sni) {
        Ok(value) => String::from(value),
        Err(_) => return -libc::EINVAL,
    };

    let hosts = if hosts.is_null() {
        None
    } else {
        let hosts_string = JString::from(hosts);
        let parsed_hosts = match env.get_string(&hosts_string) {
            Ok(value) => Some(String::from(value)),
            Err(_) => return -libc::EINVAL,
        };
        parsed_hosts
    };

    let mut config = RuntimeConfig::default();
    config.listen.listen_ip = listen_ip;
    config.listen.listen_port = match u16::try_from(port) {
        Ok(value) if value != 0 => value,
        _ => return -libc::EINVAL,
    };
    if max_connections <= 0 {
        return -libc::EINVAL;
    }
    config.max_open = max_connections;
    config.buffer_size = match usize::try_from(buffer_size) {
        Ok(value) if value != 0 => value,
        _ => return -libc::EINVAL,
    };
    if udp_fake_count < 0 {
        return -libc::EINVAL;
    }
    config.resolve = !no_domain;
    config.tfo = tfo;
    if custom_ttl {
        let ttl = match u8::try_from(default_ttl) {
            Ok(value) if value != 0 => value,
            _ => return -libc::EINVAL,
        };
        config.default_ttl = ttl;
        config.custom_ttl = true;
    }

    let mut groups = Vec::new();
    if hosts_mode == HOSTS_WHITELIST {
        let mut whitelist = DesyncGroup::new(0);
        whitelist.filters.hosts = match parse_hosts(hosts.as_deref()) {
            Ok(value) => value,
            Err(code) => return code,
        };
        groups.push(whitelist);
    }

    let mut group = DesyncGroup::new(groups.len());
    if hosts_mode == HOSTS_BLACKLIST {
        group.filters.hosts = match parse_hosts(hosts.as_deref()) {
            Ok(value) => value,
            Err(code) => return code,
        };
    } else if hosts_mode != HOSTS_DISABLE && hosts_mode != HOSTS_WHITELIST {
        return -libc::EINVAL;
    }

    if fake_ttl > 0 {
        group.ttl = match u8::try_from(fake_ttl) {
            Ok(value) => Some(value),
            Err(_) => return -libc::EINVAL,
        };
    }
    group.udp_fake_count = udp_fake_count;
    group.drop_sack = drop_sack;
    group.proto = (u32::from(desync_http) * IS_HTTP)
        | (u32::from(desync_https) * IS_HTTPS)
        | (u32::from(desync_udp) * IS_UDP);
    group.mod_http = (u32::from(host_mixed_case) * MH_HMIX)
        | (u32::from(domain_mixed_case) * MH_DMIX)
        | (u32::from(host_remove_spaces) * MH_SPACE);

    let use_tls_host = group.proto != 0 || desync_https;
    let part_offset = if split_at_host {
        if use_tls_host {
            OffsetExpr::tls_host(i64::from(split_position))
        } else {
            OffsetExpr::host(i64::from(split_position))
        }
    } else {
        OffsetExpr::absolute(i64::from(split_position))
    };
    group.parts.push(PartSpec {
        mode: match desync_method {
            DESYNC_NONE => DesyncMode::None,
            DESYNC_SPLIT => DesyncMode::Split,
            DESYNC_DISORDER => DesyncMode::Disorder,
            DESYNC_FAKE => DesyncMode::Fake,
            DESYNC_OOB => DesyncMode::Oob,
            DESYNC_DISOOB => DesyncMode::Disoob,
            _ => return -libc::EINVAL,
        },
        offset: part_offset,
    });

    if tls_record_split {
        let expr = if tls_record_split_at_sni {
            if use_tls_host {
                OffsetExpr::tls_host(i64::from(tls_record_split_position))
            } else {
                OffsetExpr::host(i64::from(tls_record_split_position))
            }
        } else {
            OffsetExpr::absolute(i64::from(tls_record_split_position))
        };
        group.tls_records.push(expr);
    }

    if desync_method == DESYNC_FAKE {
        group.fake_offset = Some(OffsetExpr::absolute(i64::from(fake_offset)));
        group.fake_sni_list.push(fake_sni);
    }

    if desync_method == DESYNC_OOB {
        group.oob_data = Some(custom_oob_char);
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

    create_socket_from_config(config)
}

fn create_socket_from_config(config: RuntimeConfig) -> jint {
    let listener = match runtime::create_listener(&config) {
        Ok(listener) => listener,
        Err(err) => return -negative_os_error(&err, libc::EADDRINUSE),
    };
    let fd = listener.into_raw_fd();
    CONFIGS
        .lock()
        .expect("config registry poisoned")
        .insert(fd, config);
    fd
}

fn start_proxy(fd: jint) -> jint {
    let config = {
        let mut guard = CONFIGS.lock().expect("config registry poisoned");
        guard.remove(&fd)
    };
    let Some(config) = config else {
        return libc::EINVAL;
    };

    process::prepare_embedded();
    let listener = unsafe { std::net::TcpListener::from_raw_fd(fd) };
    match runtime::run_proxy_with_listener(config, listener) {
        Ok(()) => 0,
        Err(err) => positive_os_error(&err, libc::EINVAL),
    }
}

fn stop_proxy(fd: jint) -> jint {
    process::request_shutdown();
    let rc = unsafe { libc::shutdown(fd, libc::SHUT_RDWR) };
    if rc == 0 {
        0
    } else {
        positive_os_error(&std::io::Error::last_os_error(), libc::EINVAL)
    }
}

fn read_string_array(env: &mut JNIEnv, args: JObjectArray) -> Result<Vec<String>, jint> {
    let len = env.get_array_length(&args).map_err(|_| -libc::EINVAL)?;
    let mut out = Vec::with_capacity(len as usize);
    for idx in 0..len {
        let value = env
            .get_object_array_element(&args, idx)
            .map_err(|_| -libc::EINVAL)?;
        let value = JString::from(value);
        let value = env.get_string(&value).map_err(|_| -libc::EINVAL)?;
        out.push(value.into());
    }
    Ok(out)
}

fn normalize_args(mut args: Vec<String>) -> Vec<String> {
    if args.first().is_some_and(|value| !value.starts_with('-')) {
        args.remove(0);
    }
    args
}

fn parse_hosts(hosts: Option<&str>) -> Result<Vec<String>, jint> {
    let hosts = hosts.unwrap_or_default();
    ciadpi_config::parse_hosts_spec(hosts).map_err(|_| -libc::EINVAL)
}

fn negative_os_error(err: &std::io::Error, fallback: i32) -> i32 {
    err.raw_os_error().unwrap_or(fallback)
}

fn positive_os_error(err: &std::io::Error, fallback: i32) -> i32 {
    err.raw_os_error().unwrap_or(fallback)
}
