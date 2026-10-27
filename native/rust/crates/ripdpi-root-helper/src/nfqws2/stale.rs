//! Reaps nfqws2 state that an earlier helper left when it died without its
//! cleanup (SIGKILL, low-memory kill, abort).
//!
//! The firewall journal and the nfqws2 child live only in helper memory, and
//! the chain names carry the helper PID, so a new helper never meets the old
//! chains. A chain or process counts as stale only when it names this app uid
//! and a helper PID that is no longer a running helper. The reaper runs before
//! this helper creates any state, so a chain or nfqws2 that names this helper's
//! own PID belongs to an earlier helper whose PID the kernel reused.

use std::ffi::OsStr;
use std::fs;
use std::io;
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use nix::sys::signal::{Signal, kill};
use nix::unistd::Pid;
use tracing::{info, warn};

use super::{FirewallCommand, QUEUE, run_firewall, run_firewall_output};

/// The whole firewall part of the reap: it runs before the accept loop, so a
/// hung iptables or a long-held xtables lock must not stall the helper.
const REAP_TIMEOUT: Duration = Duration::from_secs(3);

/// Removes stale nfqws2 chains and their jumps with each of `programs`
/// (iptables, ip6tables), then kills an orphan nfqws2.
pub(super) fn reap_stale_state(files_dir: &Path, uid: u32, programs: &[PathBuf]) {
    let deadline = Instant::now() + REAP_TIMEOUT;
    let own_pid = std::process::id();
    let own_comm = fs::read_to_string("/proc/self/comm").unwrap_or_default();
    let helper_alive = |pid: u32| {
        fs::read_to_string(format!("/proc/{pid}/comm")).is_ok_and(|comm| !own_comm.is_empty() && comm == own_comm)
    };
    for program in programs {
        let rules = match list_mangle_rules(program, deadline) {
            Ok(rules) => rules,
            Err(error) => {
                warn!(%error, program = %program.display(), "could not list firewall rules for stale nfqws2 chains");
                continue;
            }
        };
        for command in stale_chain_cleanup(program, &rules, uid, own_pid, helper_alive) {
            if let Err(error) = run_firewall(&command, deadline) {
                warn!(%error, args = ?command.args, "stale nfqws2 firewall cleanup failed");
            }
        }
    }
    let Ok(processes) = fs::read_dir("/proc") else { return };
    for process in processes.flatten() {
        let Some(pid) = process.file_name().to_str().and_then(|name| name.parse::<i32>().ok()) else { continue };
        let Ok(cmdline) = fs::read(process.path().join("cmdline")) else { continue };
        let Some(helper_pid) = orphan_nfqws2_helper_pid(&cmdline, files_dir) else { continue };
        let Some((process_uid, parent_pid)) = process_ids(&process.path()) else { continue };
        // A running nfqws2 has dropped root to the app uid, so the app could stop it too.
        if process_uid == uid && nfqws2_is_stale(helper_pid, parent_pid, own_pid, helper_alive) {
            match kill(Pid::from_raw(pid), Signal::SIGKILL) {
                Ok(()) => info!(pid, helper_pid, "stopped an nfqws2 left by an earlier helper"),
                Err(error) => warn!(pid, %error, "could not stop an nfqws2 left by an earlier helper"),
            }
        }
    }
}

fn list_mangle_rules(program: &Path, deadline: Instant) -> io::Result<String> {
    let listing =
        FirewallCommand { program: program.to_path_buf(), args: ["-t", "mangle", "-S"].map(str::to_owned).to_vec() };
    String::from_utf8(run_firewall_output(&listing, deadline)?)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
}

/// Builds the commands that remove stale chains of `uid` from `iptables -S` output:
/// first every jump into them, then their rules, then the chains.
// shortcut: names have no separator between the uid and PID hex, so a uid whose hex
// starts with this uid's hex could match; real app uids (10000..19999 and per-user
// offsets) do not collide in practice. Add a separator to the name if that changes.
fn stale_chain_cleanup(
    program: &Path,
    rules: &str,
    uid: u32,
    own_pid: u32,
    helper_alive: impl Fn(u32) -> bool,
) -> Vec<FirewallCommand> {
    let prefix = format!("RDP{uid:x}");
    let stale: Vec<&str> = rules
        .lines()
        .filter_map(|line| line.strip_prefix("-N "))
        .map(str::trim)
        .filter(|chain| {
            chain
                .strip_prefix(&prefix)
                .and_then(|rest| rest.strip_suffix('O').or_else(|| rest.strip_suffix('I')))
                .and_then(|pid| u32::from_str_radix(pid, 16).ok())
                // Chains that name this helper's PID are the ones it is about to create.
                .is_some_and(|pid| pid == own_pid || !helper_alive(pid))
        })
        .collect();
    let command = |args: Vec<String>| {
        let mut full = vec!["-t".to_owned(), "mangle".to_owned()];
        full.extend(args);
        FirewallCommand { program: PathBuf::from(program), args: full }
    };
    let mut commands = Vec::new();
    for line in rules.lines() {
        let words: Vec<&str> = line.split_whitespace().collect();
        if words.first() == Some(&"-A")
            && let [.., "-j", target] = words.as_slice()
            && stale.contains(target)
        {
            commands
                .push(command(std::iter::once("-D").chain(words[1..].iter().copied()).map(str::to_owned).collect()));
        }
    }
    for chain in &stale {
        commands.push(command(vec!["-F".to_owned(), (*chain).to_owned()]));
        commands.push(command(vec!["-X".to_owned(), (*chain).to_owned()]));
    }
    commands
}

/// Returns the helper PID named by the pidfile of an nfqws2 command line that
/// this helper's backend starts for `files_dir`, or `None` for any other process.
fn orphan_nfqws2_helper_pid(cmdline: &[u8], files_dir: &Path) -> Option<u32> {
    let queue = format!("--qnum={QUEUE}");
    let args: Vec<&[u8]> = cmdline.split(|byte| *byte == 0).collect();
    if !args.contains(&queue.as_bytes()) {
        return None;
    }
    let pidfile = Path::new(OsStr::from_bytes(args.iter().find_map(|arg| arg.strip_prefix(b"--pidfile="))?));
    if !pidfile.starts_with(files_dir) {
        return None;
    }
    pidfile.file_name()?.to_str()?.strip_prefix(".nfqws2-")?.strip_suffix(".pid")?.parse().ok()
}

/// Returns whether an nfqws2 whose pidfile names `helper_pid` has no live owner.
/// This helper owns only its own children; another helper PID owns its nfqws2
/// while that helper runs.
fn nfqws2_is_stale(helper_pid: u32, parent_pid: u32, own_pid: u32, helper_alive: impl Fn(u32) -> bool) -> bool {
    if helper_pid == own_pid { parent_pid != own_pid } else { !helper_alive(helper_pid) }
}

/// Returns the real uid and the parent PID of a `/proc/<pid>` entry.
fn process_ids(process: &Path) -> Option<(u32, u32)> {
    let status = fs::read_to_string(process.join("status")).ok()?;
    let field = |name: &str| -> Option<u32> {
        status.lines().find_map(|line| line.strip_prefix(name))?.split_whitespace().next()?.parse().ok()
    };
    Some((field("Uid:")?, field("PPid:")?))
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use super::{nfqws2_is_stale, orphan_nfqws2_helper_pid, stale_chain_cleanup};

    const RULES: &str = "-P PREROUTING ACCEPT\n\
        -P OUTPUT ACCEPT\n\
        -N RDP27fa1c8O\n\
        -N RDP27fa1c8I\n\
        -N RDP27fa2d0O\n\
        -N RDP27fbabcO\n\
        -N netd_owned\n\
        -A OUTPUT ! -o lo -m owner --uid-owner 10234 -j RDP27fa1c8O\n\
        -A OUTPUT ! -o lo -m owner --uid-owner 10234 -j RDP27fa2d0O\n\
        -A OUTPUT -j netd_owned\n\
        -A INPUT ! -i lo -m connmark --mark 0x20000000/0x20000000 -j RDP27fa1c8I\n\
        -A RDP27fa1c8O -j NFQUEUE --queue-num 49379 --queue-bypass\n";

    #[test]
    fn only_chains_of_dead_helpers_of_this_uid_are_removed() {
        // 0x1c8 = 456 is dead, 0x2d0 = 720 is a running helper, RDP27fb... is another uid.
        let commands = stale_chain_cleanup(Path::new("/system/bin/iptables"), RULES, 10234, 999, |pid| pid == 720);
        let lines: Vec<String> = commands.iter().map(|command| command.args[2..].join(" ")).collect();
        assert_eq!(
            lines,
            [
                "-D OUTPUT ! -o lo -m owner --uid-owner 10234 -j RDP27fa1c8O",
                "-D INPUT ! -i lo -m connmark --mark 0x20000000/0x20000000 -j RDP27fa1c8I",
                "-F RDP27fa1c8O",
                "-X RDP27fa1c8O",
                "-F RDP27fa1c8I",
                "-X RDP27fa1c8I",
            ]
        );
        assert!(commands.iter().all(|command| command.args[..2] == ["-t", "mangle"]));
    }

    #[test]
    fn chains_that_name_a_reused_own_pid_are_removed_at_startup() {
        // An earlier helper with PID 456 = 0x1c8 died; this helper got the same PID.
        // Every PID looks like a running helper, this one included.
        let commands = stale_chain_cleanup(Path::new("iptables"), RULES, 10234, 456, |_| true);
        let lines: Vec<String> = commands.iter().map(|command| command.args[2..].join(" ")).collect();
        assert_eq!(
            lines,
            [
                "-D OUTPUT ! -o lo -m owner --uid-owner 10234 -j RDP27fa1c8O",
                "-D INPUT ! -i lo -m connmark --mark 0x20000000/0x20000000 -j RDP27fa1c8I",
                "-F RDP27fa1c8O",
                "-X RDP27fa1c8O",
                "-F RDP27fa1c8I",
                "-X RDP27fa1c8I",
            ]
        );
    }

    #[test]
    fn an_nfqws2_is_kept_only_while_its_helper_owns_it() {
        let alive = |pid: u32| pid == 720;
        // The pidfile names a live other helper: that helper owns the process.
        assert!(!nfqws2_is_stale(720, 720, 456, alive));
        // The pidfile names a dead helper.
        assert!(nfqws2_is_stale(999, 1, 456, alive));
        // The pidfile names this helper's PID: only its own child is not stale.
        assert!(!nfqws2_is_stale(456, 456, 456, alive));
        assert!(nfqws2_is_stale(456, 1, 456, |_| true));
    }

    #[test]
    fn a_hung_firewall_listing_ends_at_the_one_reap_deadline() {
        use std::os::unix::fs::PermissionsExt;
        use std::time::{Duration, Instant};

        let dir = std::env::temp_dir().join(format!("nfqws2-reap-hang-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("test dir");
        let script = dir.join("iptables");
        std::fs::write(&script, "#!/bin/sh\nexec sleep 30\n").expect("script");
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o700)).expect("script mode");

        let started = Instant::now();
        // Two hung programs share one deadline instead of one each.
        super::reap_stale_state(&dir, 10234, &[script.clone(), script]);
        let waited = started.elapsed();

        assert!(waited >= super::REAP_TIMEOUT, "the listing ended early: {waited:?}");
        assert!(waited < super::REAP_TIMEOUT + Duration::from_secs(1), "the reap blocked for {waited:?}");
        std::fs::remove_dir_all(dir).expect("remove test dir");
    }

    #[test]
    fn the_reap_lists_through_a_held_lock_and_removes_dead_helper_chains() {
        use std::os::unix::fs::PermissionsExt;

        let dir = std::env::temp_dir().join(format!("nfqws2-reap-entry-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("test dir");
        std::fs::write(dir.join("rules"), RULES).expect("rules");
        let script = dir.join("iptables");
        // The first listing finds the xtables lock held (exit 4); every call is logged.
        std::fs::write(
            &script,
            "#!/bin/sh\nd=\"${0%/*}\"\necho \"$*\" >> \"$d/log\"\n\
             if [ \"$3\" = -S ]; then\n  [ -e \"$d/held\" ] || { : > \"$d/held\"; exit 4; }\n  cat \"$d/rules\"\nfi\n",
        )
        .expect("script");
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o700)).expect("script mode");

        // PIDs 0x1c8 and 0x2d0 run no helper in the test environment.
        super::reap_stale_state(&dir, 10234, &[script]);

        let log = std::fs::read_to_string(dir.join("log")).expect("log");
        let calls: Vec<&str> = log.lines().collect();
        assert_eq!(
            calls,
            [
                "-t mangle -S",
                "-t mangle -S",
                "-t mangle -D OUTPUT ! -o lo -m owner --uid-owner 10234 -j RDP27fa1c8O",
                "-t mangle -D OUTPUT ! -o lo -m owner --uid-owner 10234 -j RDP27fa2d0O",
                "-t mangle -D INPUT ! -i lo -m connmark --mark 0x20000000/0x20000000 -j RDP27fa1c8I",
                "-t mangle -F RDP27fa1c8O",
                "-t mangle -X RDP27fa1c8O",
                "-t mangle -F RDP27fa1c8I",
                "-t mangle -X RDP27fa1c8I",
                "-t mangle -F RDP27fa2d0O",
                "-t mangle -X RDP27fa2d0O",
            ]
        );
        std::fs::remove_dir_all(dir).expect("remove test dir");
    }

    #[test]
    fn orphan_nfqws2_is_recognized_only_by_its_queue_and_private_pidfile() {
        let files = Path::new("/data/user/0/app/files");
        let cmdline = |pidfile: &str| {
            ["/data/user/0/app/files/nfqws2/nfqws2", "--uid=10234", "--qnum=49379", pidfile, "--intercept=1"].join("\0")
        };
        assert_eq!(
            orphan_nfqws2_helper_pid(
                cmdline("--pidfile=/data/user/0/app/files/nfqws2/.nfqws2-456.pid").as_bytes(),
                files
            ),
            Some(456)
        );
        assert_eq!(orphan_nfqws2_helper_pid(cmdline("--pidfile=/tmp/.nfqws2-456.pid").as_bytes(), files), None);
        assert_eq!(
            orphan_nfqws2_helper_pid(b"/system/bin/sh\0--pidfile=/data/user/0/app/files/.nfqws2-456.pid", files),
            None
        );
    }
}
