//! Reaps nfqws2 state that an earlier helper left when it died without its
//! cleanup (SIGKILL, low-memory kill, abort).
//!
//! The firewall journal and the nfqws2 child live only in helper memory, and
//! the chain names carry the helper PID, so a new helper never meets the old
//! chains. A chain or process counts as stale only when it names this app uid
//! and a helper PID that is no longer a running helper. The reaper runs before
//! this helper creates any state, so a chain or nfqws2 that names this helper's
//! own PID belongs to an earlier helper whose PID the kernel reused.

use std::collections::VecDeque;
use std::ffi::OsStr;
use std::fs;
use std::io;
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use nix::sys::signal::{Signal, kill};
use nix::unistd::Pid;
use tracing::info;

use super::{FirewallCommand, QUEUE, run_firewall, run_firewall_output};

/// The whole firewall part of the reap: it runs before the accept loop, so a
/// hung iptables or a long-held xtables lock must not stall the helper.
const REAP_TIMEOUT: Duration = Duration::from_secs(3);

pub(super) struct Cleanup {
    programs: VecDeque<PathBuf>,
    commands: VecDeque<FirewallCommand>,
    discovery_pending: bool,
    orphans: Option<VecDeque<Orphan>>,
}

struct Orphan {
    pid: i32,
    start: String,
    signalled: bool,
}

impl Cleanup {
    pub(super) fn new(programs: Vec<PathBuf>) -> Self {
        Self { programs: programs.into(), commands: VecDeque::new(), discovery_pending: false, orphans: None }
    }

    pub(super) fn discover() -> Self {
        Self { discovery_pending: true, ..Self::new(Vec::new()) }
    }

    pub(super) fn run(
        &mut self,
        files_dir: &Path,
        uid: u32,
        resolve_programs: impl FnOnce() -> io::Result<Vec<PathBuf>>,
    ) -> io::Result<()> {
        let deadline = Instant::now() + REAP_TIMEOUT;
        if self.discovery_pending {
            self.programs = resolve_programs()?.into();
            self.discovery_pending = false;
        }
        let own_pid = std::process::id();
        let own_comm = fs::read_to_string("/proc/self/comm").unwrap_or_default();
        let helper_alive = |pid: u32| {
            fs::read_to_string(format!("/proc/{pid}/comm")).is_ok_and(|comm| !own_comm.is_empty() && comm == own_comm)
        };
        self.finish_commands(deadline)?;
        while let Some(program) = self.programs.front() {
            let rules = list_mangle_rules(program, deadline)?;
            self.commands.extend(stale_chain_cleanup(program, &rules, uid, own_pid, helper_alive));
            self.programs.pop_front();
            self.finish_commands(deadline)?;
        }
        if self.orphans.is_none() {
            self.orphans = Some(discover_orphans(files_dir, uid, own_pid, helper_alive, deadline)?);
        }
        if let Some(orphans) = self.orphans.as_mut() {
            finish_orphans(
                orphans,
                deadline,
                |pid| read_process_identity(&Path::new("/proc").join(pid.to_string()), uid),
                signal_orphan,
            )?;
        }
        Ok(())
    }

    fn finish_commands(&mut self, deadline: Instant) -> io::Result<()> {
        while let Some(command) = self.commands.front() {
            if let Err(error) = run_firewall(command, deadline) {
                let check = super::existence_check(command)?;
                if !run_firewall(&check, deadline).is_err_and(|error| super::target_absent(&error)) {
                    return Err(error);
                }
            }
            self.commands.pop_front();
        }
        Ok(())
    }
}

fn discover_orphans(
    files_dir: &Path,
    uid: u32,
    own_pid: u32,
    helper_alive: impl Fn(u32) -> bool,
    deadline: Instant,
) -> io::Result<VecDeque<Orphan>> {
    let processes = match fs::read_dir("/proc") {
        Ok(processes) => processes,
        Err(error)
            if !cfg!(any(target_os = "linux", target_os = "android")) && error.kind() == io::ErrorKind::NotFound =>
        {
            return Ok(VecDeque::new());
        }
        Err(error) => return Err(error),
    };
    let mut orphans = VecDeque::new();
    for process in processes {
        if Instant::now() >= deadline {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "nfqws2 orphan discovery timed out"));
        }
        let process = process?;
        let Some(pid) = process.file_name().to_str().and_then(|name| name.parse::<i32>().ok()).filter(|pid| *pid > 0)
        else {
            continue;
        };
        let Some(start) = read_process_identity(&process.path(), uid)? else { continue };
        let identified = match orphan_nfqws2_of_uid(&process.path(), files_dir, uid) {
            Ok(identified) => identified,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error),
        };
        let Some((helper_pid, parent_pid)) = identified else { continue };
        if nfqws2_is_stale(helper_pid, parent_pid, own_pid, &helper_alive) {
            orphans.push_back(Orphan { pid, start, signalled: false });
        }
    }
    Ok(orphans)
}

fn finish_orphans(
    orphans: &mut VecDeque<Orphan>,
    deadline: Instant,
    mut identity: impl FnMut(i32) -> io::Result<Option<String>>,
    mut signal: impl FnMut(i32) -> io::Result<()>,
) -> io::Result<()> {
    while let Some(orphan) = orphans.front_mut() {
        if Instant::now() >= deadline {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "nfqws2 orphan exit timed out"));
        }
        let current = identity(orphan.pid)?;
        if current.as_deref() != Some(orphan.start.as_str()) {
            orphans.pop_front();
            continue;
        }
        if !orphan.signalled {
            signal(orphan.pid)?;
            orphan.signalled = true;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    Ok(())
}

fn signal_orphan(pid: i32) -> io::Result<()> {
    match kill(Pid::from_raw(pid), Signal::SIGKILL) {
        Ok(()) => {
            info!(pid, "stopped an nfqws2 left by an earlier helper");
            Ok(())
        }
        Err(nix::errno::Errno::ESRCH) => Ok(()),
        Err(error) => Err(io::Error::from(error)),
    }
}

fn read_process_identity(process: &Path, uid: u32) -> io::Result<Option<String>> {
    let (actual_uid, _) = match process_ids(process) {
        Ok(ids) => ids,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    if actual_uid != uid {
        return Ok(None);
    }
    let stat = match fs::read_to_string(process.join("stat")) {
        Ok(stat) => stat,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    super::process_start_from_stat(&stat)
}

/// Removes chains that already carry this helper's names, and the jumps into
/// them, so that a stale copy the startup reap did not finish (its deadline
/// expired) cannot block a start. Call only while this helper owns no chains.
pub(super) fn remove_own_chains(program: &Path, uid: u32, own_pid: u32, deadline: Instant) -> io::Result<()> {
    let rules = list_mangle_rules(program, deadline)?;
    for command in stale_chain_cleanup(program, &rules, uid, own_pid, |pid| pid != own_pid) {
        run_firewall(&command, deadline)?;
    }
    Ok(())
}

fn list_mangle_rules(program: &Path, deadline: Instant) -> io::Result<String> {
    let listing =
        FirewallCommand { program: program.to_path_buf(), args: ["-t", "mangle", "-S"].map(str::to_owned).to_vec() };
    String::from_utf8(run_firewall_output(&listing, deadline)?)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
}

/// Builds the commands that remove stale chains of `uid` from `iptables -S` output:
/// first every jump into them, then their rules, then the chains.
fn stale_chain_cleanup(
    program: &Path,
    rules: &str,
    uid: u32,
    own_pid: u32,
    helper_alive: impl Fn(u32) -> bool,
) -> Vec<FirewallCommand> {
    let stale: Vec<&str> = rules
        .lines()
        .filter_map(|line| line.strip_prefix("-N "))
        .map(str::trim)
        .filter(|chain| {
            owned_chain_pid(chain, rules, uid)
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

// Legacy names need an OUTPUT owner rule because their UID and PID have no delimiter.
fn owned_chain_pid(chain: &str, rules: &str, uid: u32) -> Option<u32> {
    let stem = chain.strip_suffix('O').or_else(|| chain.strip_suffix('I'))?;
    let prefix = format!("RDP{uid:x}");
    let remainder = stem.strip_prefix(&prefix)?;
    if let Some(pid) = remainder.strip_prefix('_') {
        let parsed = u32::from_str_radix(pid, 16).ok()?;
        return (pid == format!("{parsed:x}")).then_some(parsed);
    }
    let pid = u32::from_str_radix(remainder, 16).ok()?;
    if remainder != format!("{pid:x}") {
        return None;
    }
    let owner_rule = format!("-A OUTPUT ! -o lo -m owner --uid-owner {uid} -j {stem}O");
    rules.lines().any(|line| line.trim() == owner_rule).then_some(pid)
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

/// Returns the helper PID named by the pidfile and the parent PID of the
/// `/proc/<pid>` entry `process` when it is an nfqws2 of `uid`.
///
/// The uid comes first, from `status`: a `cmdline` read takes the target's mmap
/// lock, which a stuck process of any uid can hold for good. The owner of
/// `/proc/<pid>` cannot serve: nfqws2 drops root without PR_SET_DUMPABLE, so its
/// entry is owned by root.
fn orphan_nfqws2_of_uid(process: &Path, files_dir: &Path, uid: u32) -> io::Result<Option<(u32, u32)>> {
    let (process_uid, parent_pid) = process_ids(process)?;
    // A running nfqws2 has dropped root to the app uid, so the app could stop it too.
    if process_uid != uid {
        return Ok(None);
    }
    let cmdline = fs::read(process.join("cmdline"))?;
    Ok(orphan_nfqws2_helper_pid(&cmdline, files_dir).map(|helper_pid| (helper_pid, parent_pid)))
}

/// Returns whether an nfqws2 whose pidfile names `helper_pid` has no live owner.
/// This helper owns only its own children; another helper PID owns its nfqws2
/// while that helper runs.
fn nfqws2_is_stale(helper_pid: u32, parent_pid: u32, own_pid: u32, helper_alive: impl Fn(u32) -> bool) -> bool {
    if helper_pid == own_pid { parent_pid != own_pid } else { !helper_alive(helper_pid) }
}

/// Returns the real uid and the parent PID of a `/proc/<pid>` entry.
fn process_ids(process: &Path) -> io::Result<(u32, u32)> {
    let status = fs::read_to_string(process.join("status"))?;
    let field = |name: &str| -> Option<u32> {
        status.lines().find_map(|line| line.strip_prefix(name))?.split_whitespace().next()?.parse().ok()
    };
    let uid = field("Uid:").ok_or_else(|| super::invalid("missing process UID"))?;
    let parent = field("PPid:").ok_or_else(|| super::invalid("missing process parent PID"))?;
    Ok((uid, parent))
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
    fn an_orphan_exit_timeout_retains_identity_without_resignalling() {
        use std::collections::VecDeque;
        use std::io;
        use std::time::{Duration, Instant};
        let mut orphans = VecDeque::from([super::Orphan { pid: 123, start: "100".to_owned(), signalled: false }]);
        let mut signals = 0;
        for _ in 0..2 {
            let error = super::finish_orphans(
                &mut orphans,
                Instant::now() + Duration::from_millis(20),
                |_| Ok(Some("100".to_owned())),
                |_| {
                    signals += 1;
                    Ok(())
                },
            )
            .expect_err("the original process still owns its resources");
            assert_eq!(error.kind(), io::ErrorKind::TimedOut);
            assert_eq!(orphans.len(), 1);
        }
        assert_eq!(signals, 1);
        super::finish_orphans(
            &mut orphans,
            Instant::now() + Duration::from_secs(1),
            |_| Ok(Some("101".to_owned())),
            |_| {
                signals += 1;
                Ok(())
            },
        )
        .expect("a replaced identity has released the old resources");
        assert!(orphans.is_empty());
        assert_eq!(signals, 1, "the replacement was signalled");
    }

    #[test]
    fn an_unproven_orphan_identity_stays_pending_without_a_signal() {
        use std::collections::VecDeque;
        use std::io;
        use std::time::{Duration, Instant};
        let mut orphans = VecDeque::from([super::Orphan { pid: 123, start: "100".to_owned(), signalled: false }]);
        let error = super::finish_orphans(
            &mut orphans,
            Instant::now() + Duration::from_secs(1),
            |_| Err(io::Error::new(io::ErrorKind::PermissionDenied, "cannot inspect identity")),
            |_| panic!("an unproven identity must not be signalled"),
        )
        .expect_err("identity failure is not a completed cleanup");
        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
        assert_eq!(orphans.len(), 1);
        assert!(!orphans[0].signalled);
    }

    #[test]
    fn process_identity_treats_zombies_and_other_uids_as_released() {
        let dir = std::env::temp_dir().join(format!("nfqws2-orphan-identity-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("test dir");
        let status = |uid: u32| format!("Uid:\t{uid}\t{uid}\t{uid}\t{uid}\nPPid:\t1\n");
        let stat = |state: &str, start: &str| format!("123 (nfqws2) {state} {} {start} 0", ["0"; 18].join(" "));
        std::fs::write(dir.join("status"), status(10196)).expect("status");
        std::fs::write(dir.join("stat"), stat("S", "100")).expect("stat");
        assert_eq!(super::read_process_identity(&dir, 10196).expect("live identity"), Some("100".to_owned()));
        std::fs::write(dir.join("stat"), stat("Z", "100")).expect("zombie stat");
        assert_eq!(super::read_process_identity(&dir, 10196).expect("zombie identity"), None);
        std::fs::write(dir.join("status"), status(2610196)).expect("foreign status");
        std::fs::write(dir.join("stat"), stat("S", "101")).expect("replacement stat");
        assert_eq!(super::read_process_identity(&dir, 10196).expect("other uid"), None);
        std::fs::remove_dir_all(dir).expect("remove test dir");
    }

    #[test]
    fn legacy_chains_of_a_uid_with_a_shared_hex_prefix_are_not_removed() {
        let rules = "-N RDP27d4141234O\n-N RDP27d4141234I\n\
            -A OUTPUT ! -o lo -m owner --uid-owner 2610196 -j RDP27d4141234O\n\
            -A INPUT ! -i lo -m connmark --mark 0x20000000/0x20000000 -j RDP27d4141234I\n";
        let commands = stale_chain_cleanup(Path::new("iptables"), rules, 10196, 999, |pid| pid == 0x1234);
        assert!(commands.is_empty(), "foreign rules were selected: {commands:?}");
    }

    #[test]
    fn delimited_names_are_owned_by_one_uid_even_without_an_output_jump() {
        let rules = "-N RDP27d4_1234O\n-N RDP27d4_1234I\n-N RDP27d414_1234O\n\
            -A INPUT ! -i lo -m connmark --mark 0x20000000/0x20000000 -j RDP27d4_1234I\n";
        let commands = stale_chain_cleanup(Path::new("iptables"), rules, 10196, 999, |_| false);
        assert_eq!(commands.len(), 5);
        assert!(commands.iter().all(|command| !command.args.iter().any(|arg| arg.contains("RDP27d414_"))));
    }

    #[test]
    fn unproven_legacy_chains_are_preserved() {
        let rules = "-N RDP27d41234O\n-N RDP27d41234I\n\
            -A INPUT ! -i lo -m connmark --mark 0x20000000/0x20000000 -j RDP27d41234I\n";
        assert!(stale_chain_cleanup(Path::new("iptables"), rules, 10196, 999, |_| false).is_empty());
    }

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
    fn a_process_of_another_uid_is_skipped_before_its_cmdline_is_read() {
        use std::time::Duration;

        let dir = std::env::temp_dir().join(format!("nfqws2-proc-entry-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("test dir");
        let files = Path::new("/data/user/0/app/files");
        let status = |uid: u32| format!("Name:\tnfqws2\nPPid:\t1\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\n");
        std::fs::write(dir.join("status"), status(10999)).expect("status");
        // A FIFO with no writer blocks its reader, as a cmdline read does while the target holds its mmap lock.
        nix::unistd::mkfifo(&dir.join("cmdline"), nix::sys::stat::Mode::S_IRWXU).expect("fifo");
        let (sender, receiver) = std::sync::mpsc::channel();
        let entry = dir.clone();
        std::thread::spawn(move || sender.send(super::orphan_nfqws2_of_uid(&entry, files, 10234)));
        let skipped = receiver.recv_timeout(Duration::from_secs(2));
        if skipped.is_err() {
            // Unblock the reader before the assertion fails.
            drop(std::fs::OpenOptions::new().write(true).open(dir.join("cmdline")));
        }
        assert_eq!(skipped.expect("the cmdline of another uid was read").expect("process status"), None);

        std::fs::remove_file(dir.join("cmdline")).expect("remove fifo");
        std::fs::write(
            dir.join("cmdline"),
            b"nfqws2\0--qnum=49379\0--pidfile=/data/user/0/app/files/.nfqws2-456.pid\0",
        )
        .expect("cmdline");
        std::fs::write(dir.join("status"), status(10234)).expect("status");
        assert_eq!(super::orphan_nfqws2_of_uid(&dir, files, 10234).expect("owned process"), Some((456, 1)));
        std::fs::remove_dir_all(dir).expect("remove test dir");
    }

    #[test]
    fn a_start_removes_only_the_chains_that_carry_its_own_names() {
        use std::os::unix::fs::PermissionsExt;

        let dir = std::env::temp_dir().join(format!("nfqws2-own-chains-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("test dir");
        std::fs::write(dir.join("rules"), RULES).expect("rules");
        let script = dir.join("iptables");
        std::fs::write(
            &script,
            "#!/bin/sh\nd=\"${0%/*}\"\necho \"$*\" >> \"$d/log\"\n[ \"$3\" = -S ] && cat \"$d/rules\"\nexit 0\n",
        )
        .expect("script");
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o700)).expect("script mode");

        // This helper has PID 0x2d0; the dead helper 0x1c8 is left to the reap.
        let deadline = std::time::Instant::now() + super::REAP_TIMEOUT;
        super::remove_own_chains(&script, 10234, 0x2d0, deadline).expect("own chains removed");

        let log = std::fs::read_to_string(dir.join("log")).expect("log");
        assert_eq!(
            log.lines().collect::<Vec<_>>(),
            [
                "-t mangle -S",
                "-t mangle -D OUTPUT ! -o lo -m owner --uid-owner 10234 -j RDP27fa2d0O",
                "-t mangle -F RDP27fa2d0O",
                "-t mangle -X RDP27fa2d0O",
            ]
        );
        std::fs::remove_dir_all(dir).expect("remove test dir");
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
        super::Cleanup::new(vec![script.clone(), script])
            .run(&dir, 10234, || Ok(Vec::new()))
            .expect_err("listing deadline");
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
        super::Cleanup::new(vec![script]).run(&dir, 10234, || Ok(Vec::new())).expect("stale state removed");

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
