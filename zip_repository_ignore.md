# Zip Repository Ignore

This file is produced by a project scan from the global `zip-repository` skill.

- The first block contains concrete exclusions detected in this project.
- The second block is for manual project-specific additions.
- Run `update` when the project layout changes and you want to rescan it.

## Auto-Detected Exclusions
<!-- zip-repository:generated:start -->
```ignore
# Always exclude repository internals and local helper files
.git/
.omc/
.claude/
.codex/
zip_repository_ignore.md
zip_context_ignore.md

# Found in this project: local metadata and editor noise
.claude/
.claude/skills/material-3/.omc/
.codex/
.idea/
.omc/
native/rust/.omc/
play-store-screenshots/.omc/
play-store-screenshots/public/screenshots/.omc/
.DS_Store
docs/.DS_Store
*.swp
*.swo

# Found in this project: .gitignore and lockfiles
.gitignore
.idea/.gitignore
app/.gitignore
native/rust/fuzz/.gitignore
play-store-screenshots/.gitignore
native/rust/Cargo.lock
native/rust/fuzz/Cargo.lock
native/rust/vendor/boring-sys/Cargo.lock

# Found in this project: build, cache, and generated directories
.claude/worktrees/agent-a443c637/.gradle/
.claude/worktrees/agent-a443c637/build-logic/.gradle/
.claude/worktrees/agent-a5f5bb86/.gradle/
.claude/worktrees/agent-a5f5bb86/build-logic/.gradle/
.claude/worktrees/agent-a659e602/.gradle/
.claude/worktrees/agent-a659e602/build-logic/.gradle/
.claude/worktrees/agent-a806aff9/.gradle/
.claude/worktrees/agent-a806aff9/build-logic/.gradle/
.claude/worktrees/agent-a9f21465/.gradle/
.claude/worktrees/agent-a9f21465/build-logic/.gradle/
.claude/worktrees/agent-aee89a7d/.gradle/
.claude/worktrees/agent-aee89a7d/build-logic/.gradle/
.gradle/
app/.cxx/Debug/5y4rd6n1/arm64-v8a/CMakeFiles/3.22.1-g37088a8/CompilerIdC/tmp/
app/.cxx/Debug/5y4rd6n1/arm64-v8a/CMakeFiles/3.22.1-g37088a8/CompilerIdCXX/tmp/
app/.cxx/Debug/5y4rd6n1/armeabi-v7a/CMakeFiles/3.22.1-g37088a8/CompilerIdC/tmp/
app/.cxx/Debug/5y4rd6n1/armeabi-v7a/CMakeFiles/3.22.1-g37088a8/CompilerIdCXX/tmp/
app/.cxx/Debug/5y4rd6n1/x86/CMakeFiles/3.22.1-g37088a8/CompilerIdC/tmp/
app/.cxx/Debug/5y4rd6n1/x86/CMakeFiles/3.22.1-g37088a8/CompilerIdCXX/tmp/
app/.cxx/Debug/5y4rd6n1/x86_64/CMakeFiles/3.22.1-g37088a8/CompilerIdC/tmp/
app/.cxx/Debug/5y4rd6n1/x86_64/CMakeFiles/3.22.1-g37088a8/CompilerIdCXX/tmp/
app/build/
baselineprofile/build/
build/
build-logic/.gradle/
build-logic/convention/build/
core/data/build/
core/detection/build/
core/diagnostics-data/build/
core/diagnostics/build/
core/engine/build/
core/service/build/
native/rust/crates/ripdpi-android/target/
native/rust/crates/ripdpi-desync/target/
native/rust/crates/ripdpi-monitor/target/
native/rust/crates/ripdpi-runtime/target/
native/rust/crates/ripdpi-tunnel-android/target/
native/rust/fuzz/target/
native/rust/target/
native/rust/vendor/boring-sys/build/
native/rust/vendor/boring-sys/deps/boringssl/util/build/
play-store-screenshots/.next/
play-store-screenshots/node_modules/
quality/detekt-rules/build/
scripts/__pycache__/
scripts/analytics/__pycache__/
scripts/ci/__pycache__/
scripts/guide/.venv/
scripts/guide/__pycache__/
scripts/tests/__pycache__/
xray-protos/build/

# Found in this project: generated files and local artifacts
play-store-screenshots/next-env.d.ts

# Detected binary/media/archive extensions in this project
*.bin
*.ico
*.jar
*.o
*.png
*.ttf
*.webp
```
<!-- zip-repository:generated:end -->

## Manual Additions
<!-- zip-repository:extra:start -->
```ignore
# add project-specific patterns here
```
<!-- zip-repository:extra:end -->
