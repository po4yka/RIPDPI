use std::os::unix::fs::PermissionsExt;

use super::*;

fn backend(files_dir: PathBuf) -> Nfqws2Backend {
    Nfqws2Backend {
        files_dir,
        uid: 10196,
        active: None,
        last_error: None,
        shutdown_requested: false,
        cleanup_backoff: Duration::ZERO,
        cleanup_retry_at: None,
        stale_cleanup: None,
    }
}

fn firewall(directory: &Path, hold: &str) -> PathBuf {
    fs::create_dir_all(directory).expect("firewall dir");
    fs::write(directory.join("hold"), hold).expect("hold phase");
    fs::write(directory.join("locked"), "").expect("lock");
    fs::write(
        directory.join("rules"),
        "-N RDP27d41234O\n-N RDP27d41234I\n\
        -A OUTPUT ! -o lo -m owner --uid-owner 10196 -j RDP27d41234O\n\
        -A INPUT ! -i lo -m connmark --mark 0x20000000/0x20000000 -j RDP27d41234I\n",
    )
    .expect("legacy rules");
    let script = directory.join("iptables");
    fs::write(
        &script,
        "#!/bin/sh\nd=\"${0%/*}\"\n\
        case \"$3\" in\n\
          -S)\n\
            if [ -z \"$4\" ]; then\n\
              if [ -e \"$d/locked\" ] && [ \"$(cat \"$d/hold\")\" = listing ]; then exit 4; fi\n\
              [ -e \"$d/clean\" ] && exit 0\n\
              if [ -e \"$d/output-removed\" ]; then grep -v '^-A OUTPUT ' \"$d/rules\"; else cat \"$d/rules\"; fi\n\
            else [ -e \"$d/clean\" ] && exit 1; fi;;\n\
          -D)\n\
            if [ \"$4\" = OUTPUT ]; then : > \"$d/output-removed\"; exit 0; fi\n\
            if [ -e \"$d/locked\" ] && [ \"$(cat \"$d/hold\")\" = deletion ]; then exit 4; fi;;\n\
          -X) case \"$4\" in *I) : > \"$d/clean\";; esac;;\n\
        esac\nexit 0\n",
    )
    .expect("firewall script");
    fs::set_permissions(&script, fs::Permissions::from_mode(0o700)).expect("firewall mode");
    script
}

#[test]
fn startup_without_backend_assets_does_not_owe_nfqueue_cleanup() {
    let dir = std::env::temp_dir().join(format!("nfqws2-unused-root-test-{}", std::process::id()));
    fs::create_dir_all(&dir).expect("test dir");
    let mut backend = backend(dir.clone());
    backend.reap_stale_state();
    assert!(!backend.status().cleanup_pending);
    backend.request_shutdown();
    backend.poll();
    assert!(backend.shutdown_complete());
    fs::remove_dir_all(dir).expect("remove test dir");
}

#[test]
fn failed_discovery_and_partial_legacy_cleanup_are_retried_before_activation_and_shutdown() {
    for held_phase in ["listing", "deletion"] {
        let dir = std::env::temp_dir().join(format!("nfqws2-recovery-{held_phase}-{}", std::process::id()));
        let ipv4 = dir.join("ipv4");
        let ipv6 = dir.join("ipv6");
        let v4_program = firewall(&ipv4, held_phase);
        let v6_program = firewall(&ipv6, "none");
        let mut backend = backend(dir.clone());
        backend.begin_stale_cleanup(stale::Cleanup::new(vec![v4_program.clone(), v6_program]));
        let status = backend.status();
        assert!(status.cleanup_pending);
        assert!(!status.running);
        assert_eq!(status.pid, None);
        assert_eq!(ipv4.join("output-removed").exists(), held_phase == "deletion");
        let requested = StartNfqws2Params {
            binary_path: dir.join("custom/nfqws2").display().to_string(),
            working_directory: dir.join("custom").display().to_string(),
            args: vec!["--filter-tcp=443".to_owned()],
            owner_pid: std::process::id(),
            protect_path: None,
        };
        let rejected = backend.start(requested);
        assert!(rejected.cleanup_pending);
        assert_eq!(rejected.capability_error, status.capability_error, "activation bypassed pending recovery");
        backend.request_shutdown();
        assert!(!backend.shutdown_complete());

        if held_phase == "listing" {
            let script = fs::read(&v4_program).expect("script bytes");
            fs::remove_file(&v4_program).expect("remove owed program");
            backend.cleanup_retry_at = None;
            backend.poll();
            assert!(backend.status().cleanup_pending, "missing owed executable cleared recovery");
            fs::write(&v4_program, script).expect("restore program");
            fs::set_permissions(&v4_program, fs::Permissions::from_mode(0o700)).expect("restore mode");
        }
        fs::remove_file(ipv4.join("locked")).expect("release lock");
        backend.cleanup_retry_at = None;
        backend.poll();
        assert!(!backend.status().cleanup_pending);
        assert!(backend.shutdown_complete());
        assert!(ipv4.join("clean").exists(), "IPv4 legacy cleanup was abandoned");
        assert!(ipv6.join("clean").exists(), "IPv6 recovery was skipped");
        fs::remove_dir_all(dir).expect("remove test dir");
    }
}

#[test]
fn initial_program_discovery_failure_keeps_recovery_pending() {
    let dir = std::env::temp_dir().join(format!("nfqws2-discovery-test-{}", std::process::id()));
    let v4 = firewall(&dir.join("ipv4"), "none");
    let v6 = firewall(&dir.join("ipv6"), "none");
    let mut cleanup = stale::Cleanup::discover();
    let error = cleanup
        .run(&dir, 10196, || Err(io::Error::new(io::ErrorKind::NotFound, "missing ip6tables")))
        .expect_err("initial discovery failed");
    assert_eq!(error.kind(), io::ErrorKind::NotFound);
    let mut backend = backend(dir.clone());
    backend.stale_cleanup = Some(cleanup);
    assert!(backend.status().cleanup_pending);
    backend.request_shutdown();
    assert!(!backend.shutdown_complete());
    backend
        .stale_cleanup
        .as_mut()
        .expect("pending cleanup")
        .run(&dir, 10196, || Ok(vec![v4, v6]))
        .expect("restored programs are discovered");
    backend.poll();
    assert!(!backend.status().cleanup_pending);
    assert!(backend.shutdown_complete());
    assert!(dir.join("ipv4/clean").exists());
    assert!(dir.join("ipv6/clean").exists());
    fs::remove_dir_all(dir).expect("remove test dir");
}
