//! Foreground nfqws2 and firewall state owned by the helper main loop.

use std::fs;
use std::io;
use std::os::unix::fs::{FileTypeExt, MetadataExt};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use nix::sys::signal::{Signal, kill};
use nix::unistd::Pid;
use ripdpi_root_helper_protocol::{Nfqws2Status, StartNfqws2Params};

const QUEUE: u16 = 49379;
const BYPASS_MARK: &str = "0x40000000/0x40000000";
const CONNECTION_MARK: &str = "0x20000000/0x20000000";
const READY_TIMEOUT: Duration = Duration::from_secs(5);
const STOP_TIMEOUT: Duration = Duration::from_millis(500);
const FIREWALL_START_TIMEOUT: Duration = Duration::from_secs(3);
const FIREWALL_STOP_TIMEOUT: Duration = Duration::from_secs(1);

#[derive(Debug, Clone, PartialEq, Eq)]
struct ValidatedStart {
    binary: PathBuf,
    directory: PathBuf,
    args: Vec<String>,
    owner_pid: u32,
    owner_start: String,
    protect_path: Option<PathBuf>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct FirewallCommand {
    program: PathBuf,
    args: Vec<String>,
}

struct ActiveBackend {
    config: ValidatedStart,
    child: Option<Child>,
    pidfile: PathBuf,
    undo: Vec<FirewallCommand>,
}

pub(crate) struct Nfqws2Backend {
    files_dir: PathBuf,
    uid: u32,
    active: Option<ActiveBackend>,
    last_error: Option<String>,
    shutdown_requested: bool,
}

impl Nfqws2Backend {
    pub(crate) fn new(files_dir: &Path) -> io::Result<Self> {
        let files_dir = fs::canonicalize(files_dir)?;
        let metadata = fs::metadata(&files_dir)?;
        if metadata.uid() == 0 || metadata.mode() & 0o002 != 0 {
            return Err(invalid("nfqws2 needs a private non-root app directory"));
        }
        Ok(Self { files_dir, uid: metadata.uid(), active: None, last_error: None, shutdown_requested: false })
    }

    pub(crate) fn start(&mut self, params: StartNfqws2Params) -> Nfqws2Status {
        self.poll();
        if self.shutdown_requested {
            let mut status = self.status();
            status.capability_error = Some("root helper shutdown is pending".to_owned());
            return status;
        }
        let config = match self.validate(params) {
            Ok(config) => config,
            Err(error) => {
                let mut status = self.status();
                status.capability_error = Some(error.to_string());
                return status;
            }
        };
        if self.active.as_ref().is_some_and(|active| active.child.is_some() && active.config == config) {
            return self.status();
        }
        self.stop();
        if self.active.is_some() {
            return self.status();
        }
        self.last_error = None;
        let pidfile = config.directory.join(format!(".nfqws2-{}.pid", std::process::id()));
        let result = (|| {
            remove_pidfile(&pidfile)?;
            let mut command = Command::new(&config.binary);
            command.current_dir(&config.directory).args(&config.args).args([
                format!("--uid={}", self.uid),
                format!("--qnum={QUEUE}"),
                "--fwmark=0x40000000".to_owned(),
                format!("--pidfile={}", pidfile.display()),
                "--intercept=1".to_owned(),
                "--debug=0".to_owned(),
            ]);
            command.env_remove("RIPDPI_PROTECT_PATH");
            if let Some(path) = &config.protect_path {
                command.env("RIPDPI_PROTECT_PATH", path);
            }
            let child = command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn()?;
            self.active = Some(ActiveBackend { config, child: Some(child), pidfile, undo: Vec::new() });
            let Some(active) = self.active.as_mut() else {
                return Err(io::Error::other("nfqws2 state was not installed"));
            };
            await_ready(active, self.uid)?;
            let commands = firewall_commands(self.uid, std::process::id())?;
            let deadline = Instant::now() + FIREWALL_START_TIMEOUT;
            install_rules(&commands, &mut active.undo, &mut |command| run_firewall(command, deadline))
        })();
        if let Err(error) = result {
            self.stop();
            if self.last_error.is_none() {
                self.last_error = Some(format!("nfqws2 activation failed: {error}"));
            }
        }
        self.status()
    }

    pub(crate) fn stop(&mut self) -> Nfqws2Status {
        self.last_error = None;
        let Some(mut active) = self.active.take() else {
            return self.status();
        };
        let deadline = Instant::now() + FIREWALL_STOP_TIMEOUT;
        if let Err(error) = undo_rules(&mut active.undo, &mut |command| run_firewall(command, deadline)) {
            self.last_error = Some(format!("nfqws2 firewall cleanup failed: {error}"));
        }
        if let Some(mut child) = active.child.take()
            && let Err(error) = terminate_child(&mut child)
        {
            self.last_error = Some(format!("nfqws2 child cleanup failed: {error}"));
            active.child = Some(child);
        }
        if let Err(error) = remove_pidfile(&active.pidfile) {
            self.last_error = Some(format!("nfqws2 pidfile cleanup failed: {error}"));
        }
        if !active.undo.is_empty() || active.child.is_some() {
            self.active = Some(active);
        }
        self.status()
    }

    pub(crate) fn poll(&mut self) {
        if self.active.is_some() && self.last_error.is_some() {
            self.stop();
            return;
        }
        let Some(active) = self.active.as_mut() else { return };
        let reason = match active.child.as_mut() {
            Some(child) => match child.try_wait() {
                Ok(Some(status)) => Some(format!("nfqws2 exited: {status}")),
                Err(error) => Some(format!("nfqws2 process status failed: {error}")),
                Ok(None) => match owner_identity(active.config.owner_pid, self.uid) {
                    Ok(start) if start == active.config.owner_start => None,
                    _ => Some("nfqws2 app owner exited".to_owned()),
                },
            },
            None => Some("nfqws2 firewall cleanup is pending".to_owned()),
        };
        if let Some(reason) = reason {
            self.stop();
            if self.last_error.is_none() {
                self.last_error = Some(reason);
            }
        }
    }

    pub(crate) fn request_shutdown(&mut self) {
        self.shutdown_requested = true;
    }

    pub(crate) fn shutdown_complete(&self) -> bool {
        self.shutdown_requested && self.active.is_none()
    }

    pub(crate) fn status(&self) -> Nfqws2Status {
        let active = self.active.as_ref();
        Nfqws2Status {
            running: active.is_some_and(|active| active.child.is_some() && self.last_error.is_none()),
            pid: active.and_then(|active| active.child.as_ref().map(Child::id)),
            owner_pid: active.map(|active| active.config.owner_pid),
            cleanup_pending: active.is_some() && self.last_error.is_some(),
            capability_error: self.last_error.clone(),
        }
    }

    fn validate(&self, params: StartNfqws2Params) -> io::Result<ValidatedStart> {
        let binary = private_path(&self.files_dir, Path::new(&params.binary_path))?;
        let directory = private_path(&self.files_dir, Path::new(&params.working_directory))?;
        let binary_metadata = fs::metadata(&binary)?;
        let directory_metadata = fs::metadata(&directory)?;
        if !binary_metadata.is_file()
            || binary_metadata.mode() & 0o111 == 0
            || binary_metadata.uid() != self.uid
            || binary_metadata.mode() & 0o022 != 0
            || !directory_metadata.is_dir()
            || directory_metadata.uid() != self.uid
            || directory_metadata.mode() & 0o002 != 0
        {
            return Err(invalid("nfqws2 paths must be private app-owned executable and directory"));
        }
        validate_args(&params.args, &self.files_dir, &directory)?;
        let owner_start = owner_identity(params.owner_pid, self.uid)?;
        let protect_path = params
            .protect_path
            .map(|path| {
                let path = private_path(&self.files_dir, Path::new(&path))?;
                let metadata = fs::metadata(&path)?;
                if !metadata.file_type().is_socket() || metadata.uid() != self.uid {
                    return Err(invalid("nfqws2 protect path must be an app-owned Unix socket"));
                }
                Ok(path)
            })
            .transpose()?;
        Ok(ValidatedStart {
            binary,
            directory,
            args: params.args,
            owner_pid: params.owner_pid,
            owner_start,
            protect_path,
        })
    }
}

fn private_path(base: &Path, path: &Path) -> io::Result<PathBuf> {
    if !path.is_absolute() {
        return Err(invalid("nfqws2 supervisor paths must be absolute"));
    }
    let canonical = fs::canonicalize(path)?;
    if !canonical.starts_with(base) || canonical == base {
        return Err(invalid("nfqws2 path escapes the app directory"));
    }
    Ok(canonical)
}

// Exact names also reject getopt long-option abbreviations of reserved options.
const USER_OPTIONS: &[&str] = &[
    "comment",
    "bind-fix4",
    "bind-fix6",
    "ctrack-timeouts",
    "ctrack-disable",
    "payload-disable",
    "server",
    "ipcache-lifetime",
    "ipcache-hostname",
    "reasm-disable",
    "blob",
    "lua-init",
    "lua-gc",
    "hostlist",
    "hostlist-domains",
    "hostlist-exclude",
    "hostlist-exclude-domains",
    "hostlist-auto",
    "hostlist-auto-fail-threshold",
    "hostlist-auto-fail-time",
    "hostlist-auto-retrans-threshold",
    "hostlist-auto-retrans-maxseq",
    "hostlist-auto-retrans-reset",
    "hostlist-auto-incoming-maxseq",
    "hostlist-auto-udp-in",
    "hostlist-auto-udp-out",
    "new",
    "skip",
    "name",
    "template",
    "import",
    "cookie",
    "filter-l3",
    "filter-tcp",
    "filter-udp",
    "filter-icmp",
    "filter-ipp",
    "filter-l7",
    "filter-ssid",
    "filter-ssid-neg",
    "filter-mark",
    "ipset",
    "ipset-ip",
    "ipset-exclude",
    "ipset-exclude-ip",
    "payload",
    "in-range",
    "out-range",
    "lua-desync",
];

fn validate_args(args: &[String], base: &Path, directory: &Path) -> io::Result<()> {
    if args.is_empty() || args.len() > 256 || args.iter().map(String::len).sum::<usize>() > 256 * 1024 {
        return Err(invalid("invalid nfqws2 option count or size"));
    }
    for argument in args {
        if argument.contains('\0') {
            return Err(invalid("nfqws2 option contains a NUL byte"));
        }
        let option = argument.strip_prefix("--").ok_or_else(|| invalid("nfqws2 requires long options"))?;
        let (name, value) = option.split_once('=').unwrap_or((option, ""));
        if !USER_OPTIONS.contains(&name) {
            return Err(invalid("nfqws2 option is reserved or unsupported"));
        }
        match name {
            "hostlist" | "hostlist-exclude" | "hostlist-auto" | "ipset" | "ipset-exclude" => {
                validate_file_arg(value, base, directory)?;
            }
            "lua-init" if value.starts_with('@') => validate_file_arg(&value[1..], base, directory)?,
            "blob" => {
                if let Some((_, file)) = value.split_once('@') {
                    validate_file_arg(file, base, directory)?;
                }
            }
            _ => {}
        }
    }
    Ok(())
}

fn validate_file_arg(value: &str, base: &Path, directory: &Path) -> io::Result<()> {
    if value.is_empty() {
        return Err(invalid("nfqws2 file argument is empty"));
    }
    let candidate = directory.join(value);
    // Writable host lists need not exist yet. Their parent must already be private.
    let canonical = if candidate.exists() {
        fs::canonicalize(&candidate)?
    } else {
        let parent = candidate.parent().ok_or_else(|| invalid("nfqws2 file has no parent"))?;
        let filename = candidate.file_name().ok_or_else(|| invalid("nfqws2 file has no name"))?;
        fs::canonicalize(parent)?.join(filename)
    };
    if !canonical.starts_with(base) || canonical == base {
        return Err(invalid("nfqws2 file argument escapes the app directory"));
    }
    Ok(())
}

fn owner_identity(pid: u32, uid: u32) -> io::Result<String> {
    if pid == 0 || pid > i32::MAX as u32 {
        return Err(invalid("invalid nfqws2 app owner PID"));
    }
    let status = fs::read_to_string(format!("/proc/{pid}/status"))?;
    let actual_uid = status
        .lines()
        .find_map(|line| line.strip_prefix("Uid:"))
        .and_then(|uids| uids.split_whitespace().next())
        .and_then(|uid| uid.parse::<u32>().ok());
    if actual_uid != Some(uid) {
        return Err(invalid("nfqws2 owner PID does not belong to the app"));
    }
    let stat = fs::read_to_string(format!("/proc/{pid}/stat"))?;
    owner_start_from_stat(&stat)
}

fn owner_start_from_stat(stat: &str) -> io::Result<String> {
    // comm can contain spaces and parentheses; fields after its final ')' start at field 3.
    let (_, fields) = stat.rsplit_once(')').ok_or_else(|| invalid("invalid app process stat"))?;
    let fields: Vec<_> = fields.split_whitespace().collect();
    if matches!(fields.first(), Some(&"Z" | &"X")) {
        return Err(invalid("nfqws2 app owner has exited"));
    }
    fields.get(19).map(|value| (*value).to_owned()).ok_or_else(|| invalid("missing app process start time"))
}

fn await_ready(active: &mut ActiveBackend, uid: u32) -> io::Result<()> {
    let started = Instant::now();
    loop {
        if owner_identity(active.config.owner_pid, uid)? != active.config.owner_start {
            return Err(invalid("nfqws2 app owner changed during startup"));
        }
        let Some(child) = active.child.as_mut() else { return Err(io::Error::other("nfqws2 has no child")) };
        if let Some(status) = child.try_wait()? {
            return Err(io::Error::other(format!("nfqws2 exited before queue readiness: {status}")));
        }
        if fs::read_to_string(&active.pidfile).ok().and_then(|pid| pid.trim().parse::<u32>().ok()) == Some(child.id()) {
            return Ok(());
        }
        if started.elapsed() >= READY_TIMEOUT {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "nfqws2 queue readiness timed out"));
        }
        std::thread::sleep(Duration::from_millis(25));
    }
}

fn remove_pidfile(pidfile: &Path) -> io::Result<()> {
    match fs::remove_file(pidfile) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error),
    }
}

fn terminate_child(child: &mut Child) -> io::Result<()> {
    if child.try_wait()?.is_some() {
        return Ok(());
    }
    let pid = i32::try_from(child.id()).map_err(|_| invalid("invalid nfqws2 child PID"))?;
    let _ = kill(Pid::from_raw(pid), Signal::SIGTERM);
    let start = Instant::now();
    while start.elapsed() < STOP_TIMEOUT {
        if child.try_wait()?.is_some() {
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(25));
    }
    child.kill()?;
    child.wait()?;
    Ok(())
}

fn firewall_program(name: &str) -> io::Result<PathBuf> {
    let candidates = if cfg!(target_os = "android") {
        vec![PathBuf::from(format!("/system/bin/{name}"))]
    } else {
        ["/usr/sbin", "/sbin", "/usr/bin"].into_iter().map(|dir| Path::new(dir).join(name)).collect()
    };
    candidates
        .into_iter()
        .find(|path| path.is_file())
        .ok_or_else(|| io::Error::new(io::ErrorKind::Unsupported, "IPv4 and IPv6 iptables are required for nfqws2"))
}

fn firewall_commands(uid: u32, helper_pid: u32) -> io::Result<Vec<FirewallCommand>> {
    let mut commands = Vec::new();
    for program in [firewall_program("iptables")?, firewall_program("ip6tables")?] {
        commands.extend(family_commands(program, uid, helper_pid));
    }
    Ok(commands)
}

fn family_commands(program: PathBuf, uid: u32, helper_pid: u32) -> Vec<FirewallCommand> {
    let outgoing = format!("RDP{uid:x}{helper_pid:x}O");
    let incoming = format!("RDP{uid:x}{helper_pid:x}I");
    let make = |args: Vec<String>| {
        // Omit -w: a netd lock conflict fails immediately and cleanup stays retryable.
        let mut base = vec!["-t".to_owned(), "mangle".to_owned()];
        base.extend(args);
        FirewallCommand { program: program.clone(), args: base }
    };
    let words = |args: &[&str]| args.iter().map(|word| (*word).to_owned()).collect::<Vec<_>>();
    vec![
        make(words(&["-N", &outgoing])),
        make(words(&["-N", &incoming])),
        make(words(&["-A", &outgoing, "-m", "mark", "--mark", BYPASS_MARK, "-j", "RETURN"])),
        make(words(&["-A", &outgoing, "-j", "CONNMARK", "--or-mark", "0x20000000"])),
        make(words(&["-A", &outgoing, "-j", "NFQUEUE", "--queue-num", &QUEUE.to_string(), "--queue-bypass"])),
        make(words(&["-A", &incoming, "-m", "mark", "--mark", BYPASS_MARK, "-j", "RETURN"])),
        make(words(&["-A", &incoming, "-j", "NFQUEUE", "--queue-num", &QUEUE.to_string(), "--queue-bypass"])),
        make(words(&[
            "-A",
            "OUTPUT",
            "!",
            "-o",
            "lo",
            "-m",
            "owner",
            "--uid-owner",
            &uid.to_string(),
            "-j",
            &outgoing,
        ])),
        make(words(&["-A", "INPUT", "!", "-i", "lo", "-m", "connmark", "--mark", CONNECTION_MARK, "-j", &incoming])),
    ]
}

fn inverse(command: &FirewallCommand) -> io::Result<FirewallCommand> {
    let mut undo = command.clone();
    let Some(operation) = undo.args.get_mut(2) else { return Err(invalid("missing firewall operation")) };
    *operation = match operation.as_str() {
        "-N" => "-X",
        "-A" => "-D",
        _ => return Err(invalid("unsupported firewall operation")),
    }
    .to_owned();
    Ok(undo)
}

fn install_rules(
    commands: &[FirewallCommand],
    undo: &mut Vec<FirewallCommand>,
    run: &mut impl FnMut(&FirewallCommand) -> io::Result<()>,
) -> io::Result<()> {
    for command in commands {
        let inverse = inverse(command)?;
        if command.args.get(2).is_some_and(|operation| operation == "-N") {
            match run(&existence_check(&inverse)?) {
                Err(error) if target_absent(&error) => {}
                Err(error) => return Err(error),
                Ok(()) => return Err(io::Error::new(io::ErrorKind::AlreadyExists, "nfqws2 chain already exists")),
            }
        }
        // A timeout can occur after the kernel accepts the mutation.
        undo.push(inverse);
        run(command)?;
    }
    Ok(())
}

fn undo_rules(
    undo: &mut Vec<FirewallCommand>,
    run: &mut impl FnMut(&FirewallCommand) -> io::Result<()>,
) -> io::Result<()> {
    // Stop on a failed deletion: preserve its dependent chain until the next retry.
    while let Some(command) = undo.last() {
        if let Err(error) = run(command) {
            let check = existence_check(command)?;
            if !run(&check).is_err_and(|error| target_absent(&error)) {
                return Err(error);
            }
        }
        undo.pop();
    }
    Ok(())
}

fn existence_check(command: &FirewallCommand) -> io::Result<FirewallCommand> {
    let mut check = command.clone();
    let Some(operation) = check.args.get_mut(2) else { return Err(invalid("missing firewall operation")) };
    *operation = match operation.as_str() {
        "-D" => "-C",
        "-X" => "-S",
        _ => return Err(invalid("unsupported firewall cleanup operation")),
    }
    .to_owned();
    Ok(check)
}

#[derive(Debug)]
struct FirewallTargetAbsent;

impl std::fmt::Display for FirewallTargetAbsent {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("owned firewall target is absent")
    }
}

impl std::error::Error for FirewallTargetAbsent {}

fn target_absent(error: &io::Error) -> bool {
    error.get_ref().is_some_and(<dyn std::error::Error + Send + Sync>::is::<FirewallTargetAbsent>)
}

fn run_firewall(command: &FirewallCommand, deadline: Instant) -> io::Result<()> {
    if Instant::now() >= deadline {
        return Err(io::Error::new(io::ErrorKind::TimedOut, "nfqws2 firewall deadline expired"));
    }
    let mut child = Command::new(&command.program)
        .args(&command.args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()?;
    loop {
        let status = match child.try_wait() {
            Ok(status) => status,
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(error);
            }
        };
        if let Some(status) = status {
            return if status.success() {
                Ok(())
            } else if status.code() == Some(1) && matches!(command.args.get(2).map(String::as_str), Some("-C" | "-S")) {
                // iptables uses 1 for a missing exact rule or chain. Other failures,
                // including lock, permission, spawn and timeout errors, remain pending.
                Err(io::Error::new(io::ErrorKind::NotFound, FirewallTargetAbsent))
            } else {
                Err(io::Error::other(format!("iptables rejected owned nfqws2 rule: {status}")))
            };
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(io::Error::new(io::ErrorKind::TimedOut, "nfqws2 firewall deadline expired"));
        }
        std::thread::sleep(Duration::from_millis(5));
    }
}

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rules_scope_the_app_both_directions_and_exclude_reinjection() {
        let rules = family_commands(PathBuf::from("iptables"), 10234, 567);
        assert_eq!(rules.len(), 9);
        let output = rules[7].args.join(" ");
        assert!(output.contains("OUTPUT ! -o lo -m owner --uid-owner 10234"));
        let input = rules[8].args.join(" ");
        assert!(input.contains("INPUT ! -i lo -m connmark --mark 0x20000000/0x20000000"));
        assert_eq!(rules.iter().filter(|rule| rule.args.contains(&"NFQUEUE".to_owned())).count(), 2);
        assert_eq!(rules.iter().filter(|rule| rule.args.contains(&BYPASS_MARK.to_owned())).count(), 2);
        assert!(
            rules
                .iter()
                .filter(|rule| rule.args.contains(&"NFQUEUE".to_owned()))
                .all(|rule| rule.args.contains(&"--queue-bypass".to_owned()))
        );
        assert!(rules.iter().all(|rule| !rule.args.contains(&"-F".to_owned())));
    }

    #[test]
    fn each_partial_install_can_be_rolled_back_in_reverse_order() {
        let rules = family_commands(PathBuf::from("iptables"), 10234, 567);
        for fail_at in 0..rules.len() {
            let mut undo = Vec::new();
            let mut count = 0;
            let result = install_rules(&rules, &mut undo, &mut |command| {
                if command.args[2] == "-S" {
                    return Err(io::Error::new(io::ErrorKind::NotFound, FirewallTargetAbsent));
                }
                let current = count;
                count += 1;
                if current == fail_at { Err(io::Error::other("injected install failure")) } else { Ok(()) }
            });
            assert!(result.is_err());
            assert_eq!(undo.len(), fail_at + 1);
            let expected: Vec<_> = undo.iter().rev().cloned().collect();
            let mut executed = Vec::new();
            undo_rules(&mut undo, &mut |command| {
                executed.push(command.clone());
                Ok(())
            })
            .expect("rollback");
            assert_eq!(executed, expected);
            assert!(undo.is_empty());
        }
    }

    #[test]
    fn failed_cleanup_preserves_pending_rules_for_retry() {
        let rules = family_commands(PathBuf::from("iptables"), 10234, 567);
        let mut undo = Vec::new();
        install_rules(&rules, &mut undo, &mut |command| {
            if command.args[2] == "-S" {
                Err(io::Error::new(io::ErrorKind::NotFound, FirewallTargetAbsent))
            } else {
                Ok(())
            }
        })
        .expect("install");
        let initial = undo.clone();
        assert!(undo_rules(&mut undo, &mut |_| Err(io::Error::other("busy"))).is_err());
        assert_eq!(undo, initial);
        undo_rules(&mut undo, &mut |_| Ok(())).expect("retry");
        assert!(undo.is_empty());
    }

    #[test]
    fn existing_chain_is_preserved_without_mutation_or_journal() {
        let command = family_commands(PathBuf::from("iptables"), 10234, 567).remove(0);
        let mut undo = Vec::new();
        let mut executed = Vec::new();
        let error = install_rules(std::slice::from_ref(&command), &mut undo, &mut |command| {
            executed.push(command.clone());
            Ok(())
        })
        .expect_err("chain collision");
        assert_eq!(error.kind(), io::ErrorKind::AlreadyExists);
        assert_eq!(executed, [existence_check(&inverse(&command).expect("inverse")).expect("check")]);
        assert!(undo.is_empty());
    }

    #[test]
    fn cleanup_confirms_only_the_exact_missing_target() {
        let rules = family_commands(PathBuf::from("iptables"), 10234, 567);
        for rule in [&rules[0], &rules[7]] {
            let inverse = inverse(rule).expect("inverse");
            let mut undo = vec![inverse.clone()];
            let mut checked = None;
            undo_rules(&mut undo, &mut |command| {
                if command == &inverse {
                    return Err(io::Error::other("failed before effect"));
                }
                checked = Some(command.clone());
                Err(io::Error::new(io::ErrorKind::NotFound, FirewallTargetAbsent))
            })
            .expect("target confirmed absent");
            assert_eq!(checked, Some(existence_check(&inverse).expect("exact check")));
            assert!(undo.is_empty());

            // Missing executable and a failed or successful probe do not prove absence.
            for probe_result in [Ok(()), Err(io::Error::from(io::ErrorKind::NotFound))] {
                let mut probe_result = Some(probe_result);
                let mut undo = vec![inverse.clone()];
                assert!(
                    undo_rules(&mut undo, &mut |command| {
                        if command == &inverse {
                            Err(io::Error::other("uncertain mutation"))
                        } else {
                            probe_result.take().expect("one probe")
                        }
                    })
                    .is_err()
                );
                assert_eq!(undo, std::slice::from_ref(&inverse));
            }
        }
    }

    #[test]
    fn firewall_effect_then_timeout_and_failure_before_effect_are_reconciled() {
        use std::os::unix::fs::PermissionsExt;

        let directory = std::env::temp_dir().join(format!("nfqws2-uncertain-rule-test-{}", std::process::id()));
        fs::create_dir_all(&directory).expect("test directory");
        let program = directory.join("iptables-test");
        let marker = directory.join("owned-rule");
        // This fixture changes only its private marker. It models an iptables
        // commit followed by a stalled process, without touching host firewall state.
        fs::write(
            &program,
            "#!/bin/sh\nmarker=\"${0%/*}/owned-rule\"\ncase \"$3\" in\n-N) : >\"$marker\"; exec sleep 10;;\n-X) rm -f \"$marker\";;\n-S) test -f \"$marker\";;\nesac\n",
        )
        .expect("test runner");
        fs::set_permissions(&program, fs::Permissions::from_mode(0o700)).expect("executable");
        let command = FirewallCommand {
            program: program.clone(),
            args: ["-t", "mangle", "-N", "owned-chain"].map(str::to_owned).to_vec(),
        };
        let mut undo = Vec::new();
        let deadline = Instant::now() + Duration::from_secs(1);
        let error =
            install_rules(std::slice::from_ref(&command), &mut undo, &mut |command| run_firewall(command, deadline))
                .expect_err("effect followed by timeout");
        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
        assert!(marker.exists());
        assert_eq!(undo.len(), 1);
        let deadline = Instant::now() + Duration::from_secs(1);
        undo_rules(&mut undo, &mut |command| run_firewall(command, deadline)).expect("rollback committed mutation");
        assert!(!marker.exists());
        assert!(undo.is_empty());

        fs::write(
            &program,
            "#!/bin/sh\nmarker=\"${0%/*}/owned-rule\"\ncase \"$3\" in\n-N|-X) exit 1;;\n-S) test -f \"$marker\";;\nesac\n",
        )
        .expect("failure-before-effect runner");
        let deadline = Instant::now() + Duration::from_secs(1);
        assert!(install_rules(&[command], &mut undo, &mut |command| run_firewall(command, deadline)).is_err());
        assert_eq!(undo.len(), 1);
        undo_rules(&mut undo, &mut |command| run_firewall(command, deadline)).expect("confirmed missing target");
        assert!(!marker.exists());
        assert!(undo.is_empty());
        fs::remove_dir_all(directory).expect("remove test directory");
    }

    #[test]
    fn owner_identity_handles_spaces_pid_reuse_and_zombies() {
        let fields = (4..=21).map(|value| value.to_string()).collect::<Vec<_>>().join(" ");
        let first = format!("123 (app name (worker)) S {fields} 999 23");
        let reused = first.replace("999", "1000");
        assert_eq!(owner_start_from_stat(&first).expect("start time"), "999");
        assert_ne!(owner_start_from_stat(&first).expect("first"), owner_start_from_stat(&reused).expect("reused"));
        assert!(owner_start_from_stat(&first.replace(") S ", ") Z ")).is_err());
        assert!(owner_start_from_stat("broken").is_err());
    }

    #[test]
    fn reserved_options_and_abbreviations_are_rejected() {
        for option in [
            "--qnum=1",
            "--q=1",
            "--daemon",
            "--uid=0",
            "--pidfile=/tmp/p",
            "--fwmark=1",
            "--debug=1",
            "--intercept=0",
            "--dry-run",
            "--chdir=/",
            "--user=root",
            "--writable",
            "--",
            "argument",
        ] {
            assert!(
                validate_args(&[option.to_owned()], Path::new("/app/files"), Path::new("/app/files/nfqws2")).is_err(),
                "{option}"
            );
        }
        validate_args(
            &["--lua-desync=fake:blob=test:ttl=4".to_owned(), "--payload=tls_client_hello".to_owned()],
            Path::new("/app/files"),
            Path::new("/app/files/nfqws2"),
        )
        .expect("upstream options");
    }

    fn sleeping_child() -> Child {
        let program = if cfg!(target_os = "android") { "/system/bin/sleep" } else { "/bin/sleep" };
        Command::new(program).arg("10").spawn().expect("sleeping child")
    }

    fn backend_with_child(child: Child) -> Nfqws2Backend {
        let pidfile = std::env::temp_dir().join(format!("nfqws2-child-test-{}-{}.pid", std::process::id(), child.id()));
        Nfqws2Backend {
            files_dir: std::env::temp_dir(),
            uid: 10234,
            last_error: None,
            shutdown_requested: false,
            active: Some(ActiveBackend {
                config: ValidatedStart {
                    binary: PathBuf::new(),
                    directory: std::env::temp_dir(),
                    args: Vec::new(),
                    owner_pid: 0,
                    owner_start: String::new(),
                    protect_path: None,
                },
                child: Some(child),
                pidfile,
                undo: Vec::new(),
            }),
        }
    }

    #[test]
    fn stop_and_owner_death_reap_actual_owned_children_within_deadline() {
        for explicit_stop in [true, false] {
            let mut backend = backend_with_child(sleeping_child());
            let started = Instant::now();
            if explicit_stop {
                backend.stop();
            } else {
                backend.poll();
            }
            let status = backend.status();
            assert!(!status.running);
            assert!(!status.cleanup_pending);
            assert_eq!(status.pid, None);
            assert!(started.elapsed() < Duration::from_secs(3));
        }
    }

    #[test]
    fn firewall_deadline_kills_a_slow_command() {
        let program = if cfg!(target_os = "android") { "/system/bin/sleep" } else { "/bin/sleep" };
        let command = FirewallCommand { program: PathBuf::from(program), args: vec!["10".to_owned()] };
        let started = Instant::now();
        let error = run_firewall(&command, started + Duration::from_millis(25)).expect_err("deadline");
        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
        assert!(started.elapsed() < Duration::from_secs(1));
    }

    #[test]
    fn requested_shutdown_exits_only_after_owned_state_is_removed() {
        let mut backend = backend_with_child(sleeping_child());
        backend.request_shutdown();
        assert!(!backend.shutdown_complete());
        backend.stop();
        assert!(backend.shutdown_complete());
    }

    #[test]
    fn private_files_reject_traversal_absolute_escape_and_symlink_escape() {
        let dir = std::env::temp_dir().join(format!("nfqws2-path-test-{}", std::process::id()));
        fs::create_dir_all(dir.join("work")).expect("test dir");
        fs::write(dir.join("work/test.lua"), "return true").expect("script");
        std::os::unix::fs::symlink(std::env::temp_dir(), dir.join("work/outside")).expect("symlink");
        let base = fs::canonicalize(&dir).expect("base");
        let work = base.join("work");
        validate_args(&["--lua-init=@test.lua".to_owned()], &base, &work).expect("private script");
        validate_args(&["--hostlist-auto=new.txt".to_owned()], &base, &work).expect("new private hostlist");
        for file in ["/etc/passwd", "../../outside.txt", "outside/new.txt"] {
            assert!(validate_args(&[format!("--lua-init=@{file}")], &base, &work).is_err());
        }
        fs::remove_dir_all(dir).expect("remove test dir");
    }
}
