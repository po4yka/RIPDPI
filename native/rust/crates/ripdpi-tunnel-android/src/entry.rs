//! JNI export facade for the tun2socks tunnel session lifecycle.
//!
//! Each `Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_*` symbol wraps a
//! `tunnel_*_entry` delegate from the `session` module in
//! `android_support::ffi_boundary` for panic containment. This module owns
//! only the export symbols and panic sentinels; the session state machine
//! lives in `crate::session`.
//!
//! ## Handle lifecycle
//! `jniCreate` -> `jniStart` -> `jniStop` -> `jniDestroy`, per session.
//! `jniCreate` registers a `Ready` session and returns an opaque `jlong`
//! registry key (`0` on failure). `jniStart` adopts the TUN fd, transitions
//! `Ready -> Running` and spawns the tunnel worker. `jniStop` cancels the
//! worker and joins it. `jniDestroy` retires the session.
//!
//! ## Idempotency
//! None of the lifecycle entries are idempotent: `jniStart` throws
//! `IllegalStateException` unless `Ready`, `jniStop` throws unless `Running`,
//! and `jniDestroy` throws if the worker is still running.
//!
//! ## fd ownership
//! `jniStart` takes the TUN `tun_fd` by value and **dups** it (`adopt_tun_fd`).
//! The JVM caller keeps ownership of the original descriptor (the `VpnService`
//! `ParcelFileDescriptor`) and must close it; the native side closes only its
//! dup, when the worker exits. Ordinary outbound sockets use the `protectPath`
//! Unix-domain socket; direct-DNS sockets use the generation-guarded JNI
//! underlay binder registered by the VPN service.
//!
//! ## Blocking and errors
//! `jniStart` returns after the worker thread is spawned (it does not run
//! packet IO on the caller thread); `jniStop` blocks until the worker joins.
//! Failures surface as Java exceptions (`IllegalArgumentException`,
//! `IllegalStateException`, `IOException`); a contained panic yields the
//! panic-default sentinel (`0` handle, null `jstring`/`jlongArray`).
//!
//! See `docs/architecture/JNI_CONTRACT.md` §4 (handle lifecycle), §9 (TUN fd
//! ownership) and §6 (panic containment).

use android_support::ffi_boundary;
use jni::EnvUnowned;
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong, jlongArray};

use crate::session::{
    PROBE_BRIDGE_FAILURE, bind_to_device_probe_entry, tunnel_create_entry, tunnel_destroy_entry,
    tunnel_forwarding_evidence_entry, tunnel_icmp_ingress_packets_entry, tunnel_pcap_list_captures_entry,
    tunnel_pcap_redact_entry, tunnel_pcap_start_entry, tunnel_pcap_stop_entry, tunnel_start_entry, tunnel_stats_entry,
    tunnel_stop_entry, tunnel_telemetry_entry,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_TunDeviceQualificationNativeBindings_jniProbeUnprivilegedBindToDevice(
    env: EnvUnowned<'_>,
    _thiz: JObject,
) -> jint {
    ffi_boundary(PROBE_BRIDGE_FAILURE, move || bind_to_device_probe_entry(env))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniCreate(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    ffi_boundary(0, move || tunnel_create_entry(env, config_json))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStart(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
    tun_fd: jint,
) {
    ffi_boundary((), move || tunnel_start_entry(env, handle, tun_fd));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStop(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || tunnel_stop_entry(env, handle));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetStats(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jlongArray {
    ffi_boundary(core::ptr::null_mut(), move || tunnel_stats_entry(env, handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_TunForwardingEvidenceNativeBindings_jniGetForwardingEvidence(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    ffi_boundary(core::ptr::null_mut(), move || tunnel_forwarding_evidence_entry(env, handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetIcmpIngressPackets(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jlong {
    ffi_boundary(0, move || tunnel_icmp_ingress_packets_entry(env, handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetTelemetry(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    ffi_boundary(core::ptr::null_mut(), move || tunnel_telemetry_entry(env, handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniDestroy(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    ffi_boundary((), move || tunnel_destroy_entry(env, handle));
}

/// Register the Kotlin flow-attribution bridge and start the worker that pushes
/// per-flow `noteFlow` notifications up to Kotlin. Returns a generation token
/// (`0` on failure) that Kotlin threads back to `jniUnregisterFlowAttribution`.
/// Register after `jniStart`, unregister before `jniStop`/`jniDestroy`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniRegisterFlowAttribution(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    bridge: JObject,
) -> jlong {
    ffi_boundary(0, move || crate::flow_attribution::register_from_jni(env, bridge))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniUnregisterFlowAttribution(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    token: jlong,
) {
    ffi_boundary((), move || crate::flow_attribution::unregister_flow_attribution(token));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniRegisterDirectDnsSocketBinderNative(
    env: EnvUnowned<'_>,
    _thiz: JObject,
    bridge: JObject,
) -> jlong {
    ffi_boundary(0, move || crate::direct_dns_binding::register_from_jni(env, bridge))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniUnregisterDirectDnsSocketBinderNative(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    token: jlong,
) {
    ffi_boundary((), move || {
        if let Ok(generation) =
            ripdpi_tunnel_core::tunnel_api::direct_dns_binding::DirectDnsBinderGeneration::try_from(token)
        {
            crate::direct_dns_binding::unregister(generation);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapStart(
    env: EnvUnowned<'_>,
    _class: JObject,
    handle: jlong,
    capture_dir: JString,
    max_file_bytes: jlong,
    max_files: jint,
) -> jlong {
    ffi_boundary(0, move || tunnel_pcap_start_entry(env, handle, capture_dir, max_file_bytes, max_files))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapStop(
    env: EnvUnowned<'_>,
    _class: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    ffi_boundary(core::ptr::null_mut(), move || tunnel_pcap_stop_entry(env, handle))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapListCaptures(
    env: EnvUnowned<'_>,
    _class: JObject,
    capture_dir: JString,
) -> jni::sys::jstring {
    ffi_boundary(core::ptr::null_mut(), move || tunnel_pcap_list_captures_entry(env, capture_dir))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_jni_PcapBridge_jniPcapRedactToFile(
    env: EnvUnowned<'_>,
    _class: JObject,
    source_path: JString,
    dest_fd: jint,
) -> jlong {
    ffi_boundary(0, move || tunnel_pcap_redact_entry(env, source_path, dest_fd))
}
