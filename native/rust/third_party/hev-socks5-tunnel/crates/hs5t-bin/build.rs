use std::process::Command;

fn main() {
    // Emit git commit hash (short) as CARGO_PKG_GIT_SHA for --version output.
    // Falls back to "unknown" if git is unavailable or repo has no commits.
    let sha = Command::new("git")
        .args(["describe", "--dirty", "--always", "--abbrev=7"])
        .output()
        .ok()
        .and_then(|o| {
            if o.status.success() {
                String::from_utf8(o.stdout)
                    .ok()
                    .map(|s| s.trim().to_owned())
            } else {
                None
            }
        })
        .unwrap_or_else(|| "unknown".to_owned());

    println!("cargo:rustc-env=CARGO_PKG_GIT_SHA={sha}");
    // Re-run only if HEAD changes.
    println!("cargo:rerun-if-changed=../../.git/HEAD");
}
