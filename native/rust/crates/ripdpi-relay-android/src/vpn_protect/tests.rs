use std::net::IpAddr;
use std::path::PathBuf;
use std::process::Command;
use std::sync::OnceLock;

use jni::objects::{JClass, JClassLoader};
use jni::{InitArgsBuilder, JNIVersion};
use ripdpi_native_protect::SocketProtectionPolicy;

use super::*;

fn test_jvm() -> &'static (JavaVM, Global<JClass<'static>>) {
    static JVM: OnceLock<(JavaVM, Global<JClass<'static>>)> = OnceLock::new();
    JVM.get_or_init(|| {
        let output_dir = std::env::temp_dir().join(format!(
            "ripdpi-relay-jni-{}-{}",
            std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock").as_nanos()
        ));
        std::fs::create_dir(&output_dir).expect("create owned Java fixture directory");
        let javac = std::env::var_os("JAVA_HOME")
            .map_or_else(|| PathBuf::from("javac"), |home| PathBuf::from(home).join("bin/javac"));
        let output = Command::new(javac)
            .args(["--release", "8", "-d"])
            .arg(&output_dir)
            .arg(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/RelayVpnService.java"))
            .output()
            .expect("compile Java fixture with the required JDK");
        assert!(output.status.success(), "javac failed: {}", String::from_utf8_lossy(&output.stderr));
        let class_bytes = std::fs::read(output_dir.join("RelayVpnService.class")).expect("read compiled fixture");
        std::fs::remove_dir_all(&output_dir).expect("remove owned Java fixture directory");

        let args =
            InitArgsBuilder::new().version(JNIVersion::V1_8).option("-Xcheck:jni").build().expect("JVM arguments");
        let vm = JavaVM::new(args).expect("test JVM");
        let class = vm
            .attach_current_thread_for_scope(|env| -> jni::errors::Result<_> {
                let class =
                    env.define_class(Some(jni::jni_str!("RelayVpnService")), JClassLoader::null(), &class_bytes)?;
                env.new_global_ref(class)
            })
            .expect("load Java fixture");
        (vm, class)
    })
}

fn callback() -> JniProtectCallback {
    let (vm, class) = test_jvm();
    let vpn_service = vm
        .attach_current_thread_for_scope(|env| -> jni::errors::Result<_> {
            let service = env.new_object(class, jni::jni_sig!("()V"), &[])?;
            env.new_global_ref(service)
        })
        .expect("create Java service");
    JniProtectCallback { vm: SharedJvm::new(vm), vpn_service }
}

#[test]
fn relay_callback_resolves_ipv4_and_ipv6_from_a_rust_worker() {
    let callback = callback();
    let addresses = std::thread::spawn(move || callback.resolve_host("relay.invalid"))
        .join()
        .expect("worker")
        .expect("protected hostname resolution");
    assert_eq!(addresses, vec!["203.0.113.7".parse::<IpAddr>().unwrap(), "2001:db8::7".parse().unwrap()]);
}

#[test]
fn relay_callback_rejects_empty_and_malformed_answers() {
    let callback = callback();
    assert_eq!(callback.resolve_host("empty.invalid").unwrap_err().kind(), io::ErrorKind::AddrNotAvailable);
    assert_eq!(callback.resolve_host("malformed.invalid").unwrap_err().kind(), io::ErrorKind::InvalidData);
    assert!(callback.resolve_host("null.invalid").is_err());
    assert!(callback.resolve_host("null-element.invalid").is_err());
}

#[test]
fn relay_callback_clears_stale_underlay_exception_before_next_call() {
    let callback = callback();
    let error = callback.resolve_host("stale.invalid").expect_err("stale underlay must fail closed");
    assert!(!error.to_string().contains("stale.invalid"), "do not expose the hostname in native errors");
    assert_eq!(callback.resolve_host("relay.invalid").expect("next lookup succeeds").len(), 2);
}

#[test]
fn relay_callback_bounds_local_references_for_large_answers() {
    assert_eq!(callback().resolve_host("many.invalid").expect("large answer").len(), 1024);
}

#[test]
fn registered_relay_callback_survives_stale_unregister_and_fails_closed_after_release() {
    let (vm, _) = test_jvm();
    let stale = register_vpn_protect(vm, callback().vpn_service);
    let current = register_vpn_protect(vm, callback().vpn_service);
    unregister_entry(stale);
    let runtime = tokio::runtime::Builder::new_current_thread().build().expect("runtime");
    let policy = SocketProtectionPolicy::VpnRequired;
    let addresses = runtime.block_on(policy.resolve_host("relay.invalid", 443)).expect("registered resolver");
    assert_eq!(addresses, vec!["203.0.113.7:443".parse().unwrap(), "[2001:db8::7]:443".parse().unwrap()]);
    unregister_entry(current);
    assert_eq!(
        runtime.block_on(policy.resolve_host("relay.invalid", 443)).unwrap_err().kind(),
        io::ErrorKind::NotConnected
    );
    for literal in ["203.0.113.7", "2001:db8::7"] {
        let resolved = runtime.block_on(policy.resolve_host(literal, 443)).expect("literal needs no DNS");
        assert_eq!(resolved, vec![std::net::SocketAddr::new(literal.parse().unwrap(), 443)]);
    }
}
