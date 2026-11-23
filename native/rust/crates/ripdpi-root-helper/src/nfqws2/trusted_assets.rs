//! Check the exact bytes supplied to the backend, before starting a child.

use std::fs::{File, OpenOptions};
use std::io::{self, Read};
use std::os::fd::AsRawFd;
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;
use std::process::Command;

use sha2::{Digest, Sha256};

use super::invalid;

/// Bounded even if the app replaces an extracted file with a larger file.
const MAX_EXECUTABLE_BYTES: usize = 8 * 1024 * 1024;
/// Less than Linux's per-argument limit on 4 KiB page systems, including the option prefix.
const MAX_SCRIPT_BYTES: usize = 120 * 1024;

// Scripts supplied by the app cannot be loaded later through standard Lua loaders or
// the dynamic code helpers in the shipped scripts. The shipped scripts use no loaders.
const RESTRICTED_LUA: &str =
    "--lua-init=load=nil;loadfile=nil;dofile=nil;require=nil;package=nil;luaexec=nil;cond_lua=nil";

pub(super) struct TrustedAssets<'a> {
    binary_hash: &'a str,
    scripts: &'a str,
}

impl<'a> TrustedAssets<'a> {
    pub(super) fn shipped() -> io::Result<Self> {
        Self::from_manifest(option_env!("RIPDPI_NFQWS2_TRUST"))
    }

    fn from_manifest(manifest: Option<&'a str>) -> io::Result<Self> {
        let (binary_hash, scripts) = manifest
            .and_then(|manifest| manifest.split_once('\n'))
            .ok_or_else(|| invalid("nfqws2 trusted asset manifest is missing"))?;
        if binary_hash.len() != 64 || scripts.is_empty() {
            return Err(invalid("nfqws2 trusted asset manifest is invalid"));
        }
        Ok(Self { binary_hash, scripts })
    }

    pub(super) fn prepare_args(&self, args: &[String], directory: &Path) -> io::Result<Vec<String>> {
        let mut scripts_seen = Vec::new();
        let mut prepared = Vec::with_capacity(args.len() + 1);
        for argument in args {
            let Some(value) = argument.strip_prefix("--lua-init=") else {
                prepared.push(argument.clone());
                continue;
            };
            let (name, digest) = value
                .strip_prefix('@')
                .and_then(|name| {
                    self.scripts.lines().filter_map(|line| line.split_once('=')).find(|(key, _)| *key == name)
                })
                .ok_or_else(|| invalid("nfqws2 only loads shipped Lua scripts"))?;
            if scripts_seen.contains(&name) {
                return Err(invalid("nfqws2 Lua script is repeated"));
            }
            scripts_seen.push(name);
            let bytes = read_verified(&directory.join(name), digest, MAX_SCRIPT_BYTES)?;
            let source = std::str::from_utf8(&bytes).map_err(|_| invalid("nfqws2 Lua script must be UTF-8"))?;
            if source.starts_with('@') || source.contains('\0') {
                return Err(invalid("nfqws2 Lua script cannot be passed as source"));
            }
            prepared.push(format!("--lua-init={source}"));
        }
        prepared.push(RESTRICTED_LUA.to_owned());
        Ok(prepared)
    }

    pub(super) fn executable(
        &self,
        path: &Path,
        staging_directory: &Path,
        owner_uid: u32,
    ) -> io::Result<VerifiedExecutable> {
        let bytes = read_verified(path, self.binary_hash, MAX_EXECUTABLE_BYTES)?;
        VerifiedExecutable::new(&bytes, staging_directory, owner_uid)
    }
}

fn read_verified(path: &Path, expected: &str, max_bytes: usize) -> io::Result<Vec<u8>> {
    let file = OpenOptions::new().read(true).custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK).open(path)?;
    if !file.metadata()?.is_file() {
        return Err(invalid("nfqws2 asset is not a regular file"));
    }
    let mut bytes = Vec::new();
    file.take((max_bytes + 1) as u64).read_to_end(&mut bytes)?;
    if bytes.len() > max_bytes || sha256(&bytes) != expected {
        return Err(invalid("nfqws2 asset does not match the shipped bytes"));
    }
    Ok(bytes)
}

fn sha256(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut encoded = String::with_capacity(64);
    for byte in Sha256::digest(bytes) {
        encoded.push(char::from(HEX[usize::from(byte >> 4)]));
        encoded.push(char::from(HEX[usize::from(byte & 0x0f)]));
    }
    encoded
}

pub(super) struct VerifiedExecutable {
    file: File,
}

fn descriptor_path(file: &File) -> String {
    format!("/proc/self/fd/{}", file.as_raw_fd())
}

impl VerifiedExecutable {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn new(bytes: &[u8], directory: &Path, owner_uid: u32) -> io::Result<Self> {
        use std::io::Write;
        use std::os::unix::fs::{MetadataExt, PermissionsExt};

        // An unnamed on-disk inode avoids the Android SELinux restrictions on executing
        // anonymous memory. O_EXCL prevents linking it into the app's writable namespace.
        let mut writer = OpenOptions::new()
            .read(true)
            .write(true)
            .mode(0o700)
            .custom_flags(libc::O_TMPFILE | libc::O_EXCL | libc::O_NOFOLLOW)
            .open(directory)?;
        let metadata = writer.metadata()?;
        if metadata.uid() != owner_uid || metadata.nlink() != 0 {
            return Err(invalid("nfqws2 executable copy must be private to the helper"));
        }
        writer.write_all(bytes)?;
        writer.set_permissions(std::fs::Permissions::from_mode(0o500))?;

        // An app-UID process cannot open the root helper's descriptors in /proc. Keep
        // only the read-only descriptor across spawn; the kernel rejects exec with a writer open.
        let file = File::open(descriptor_path(&writer))?;
        drop(writer);
        Ok(Self { file })
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    fn new(_bytes: &[u8], _directory: &Path, _owner_uid: u32) -> io::Result<Self> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "nfqws2 needs unnamed Linux executable files"))
    }

    pub(super) fn command(&self) -> Command {
        use std::os::unix::process::CommandExt;

        // The descriptor stays alive through spawn. CLOEXEC closes it in the child only after the
        // kernel opens the executable. argv[0] carries the backend name for process diagnostics.
        let mut command = Command::new(descriptor_path(&self.file));
        command.arg0("nfqws2");
        command
    }
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::*;

    const SCRIPT: &str = "return true\n";
    const SHA256: &str = "ea247e820cda9e68c4b2268fe9ff4f376a32f82a422a8c0cdd274ce08048cc34";

    #[test]
    fn scripts_are_checked_once_and_passed_without_an_app_path() {
        let directory = std::env::temp_dir().join(format!("nfqws2-script-{}", std::process::id()));
        fs::create_dir_all(directory.join("lua")).expect("script directory");
        let script = directory.join("lua/shipped.lua");
        fs::write(&script, SCRIPT).expect("shipped script");
        let manifest = format!("{SHA256}\nlua/shipped.lua={SHA256}\n");
        let trusted = TrustedAssets::from_manifest(Some(&manifest)).expect("manifest");
        let args = ["--filter-tcp=443".to_owned(), "--lua-init=@lua/shipped.lua".to_owned()];

        let prepared = trusted.prepare_args(&args, &directory).expect("verified scripts");
        fs::write(&script, "error('changed script')").expect("changed script");
        assert_eq!(prepared, ["--filter-tcp=443", "--lua-init=return true\n", RESTRICTED_LUA]);
        assert!(trusted.prepare_args(&args, &directory).is_err());

        fs::write(script.with_extension("lua.gz"), SCRIPT).expect("compressed replacement");
        fs::remove_file(&script).expect("remove script");
        assert!(trusted.prepare_args(&args, &directory).is_err());

        let replacement = directory.join("replacement.lua");
        fs::write(&replacement, SCRIPT).expect("replacement");
        std::os::unix::fs::symlink(&replacement, &script).expect("same-byte symlink");
        assert!(trusted.prepare_args(&args, &directory).is_err());
        fs::remove_dir_all(directory).expect("remove script directory");
    }

    #[test]
    fn missing_manifest_and_unapproved_script_sources_are_denied() {
        for manifest in [None, Some(""), Some("invalid\n")] {
            assert!(TrustedAssets::from_manifest(manifest).is_err());
        }
        let manifest = format!("{SHA256}\nlua/shipped.lua={SHA256}\n");
        let trusted = TrustedAssets::from_manifest(Some(&manifest)).expect("manifest");
        for arg in [
            "--lua-init=return true",
            "--lua-init=@lua/custom.lua",
            "--lua-init=@lua/shipped.lua.gz",
            "--lua-init=@lua/../lua/shipped.lua",
        ] {
            let result = trusted.prepare_args(&[arg.to_owned()], Path::new("/"));
            assert_eq!(result.expect_err(arg).to_string(), "nfqws2 only loads shipped Lua scripts");
        }
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn verified_elf_uses_private_bytes_after_source_replacement() {
        use std::io::Write;
        use std::os::unix::fs::MetadataExt;

        #[cfg(target_os = "android")]
        let original = "/system/bin/toybox";
        #[cfg(target_os = "linux")]
        let original = "/bin/true";
        let directory = std::env::temp_dir().join(format!("nfqws2-executable-{}", std::process::id()));
        fs::create_dir_all(&directory).expect("executable directory");
        let path = directory.join("binary");
        let original = fs::read(original).expect("ELF fixture");
        fs::write(&path, &original).expect("copy ELF fixture");
        let manifest = format!("{}\nlua/shipped.lua={SHA256}\n", sha256(&original));
        let trusted = TrustedAssets::from_manifest(Some(&manifest)).expect("manifest");
        let uid = fs::metadata(&directory).expect("directory metadata").uid();
        let executable = trusted.executable(&path, &directory, uid).expect("verified executable");

        fs::write(&path, "changed executable").expect("in-place replacement");
        assert!(trusted.executable(&path, &directory, uid).is_err());
        assert_eq!(executable.file.metadata().expect("executable metadata").nlink(), 0);
        assert!((&executable.file).write_all(b"changed private file").is_err());
        let mut command = executable.command();
        #[cfg(target_os = "android")]
        {
            use std::os::unix::process::CommandExt;

            command.arg0("toybox").arg("true");
        }
        assert!(command.status().expect("execute private ELF").success());
        fs::remove_dir_all(directory).expect("remove executable directory");
    }
}
