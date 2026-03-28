#!/usr/bin/env bash
set -euo pipefail

# generate-audit-doc.sh -- Produces a single Markdown document containing the
# project codebase (Rust native + Android/Kotlin) for external security audit
# by an LLM with a token limit.

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
STANDARD_TOKEN_BUDGET=212000   # 272K minus prompt/response/structure headroom
EXTENDED_TOKEN_BUDGET=950000   # 1.05M minus headroom
CHARS_PER_TOKEN=3.5            # Conservative estimate for Rust code

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
    cat <<'EOF'
Usage: ./scripts/generate-audit-doc.sh [OPTIONS]

Options:
  --mode standard|extended   Token budget mode (default: standard, 272K)
  --output PATH              Output file path (default: build/audit-package/audit-YYYY-MM-DD.md)
  --estimate-only            Print token estimates without generating
  -h, --help                 Show this help

Standard mode (~212K code tokens): critical Rust code verbatim, rest summarized.
Extended mode (~950K code tokens): all Rust + Android/Kotlin source verbatim.
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
MODE="standard"
OUTPUT=""
ESTIMATE_ONLY=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --mode)
            MODE="$2"
            shift 2
            ;;
        --output)
            OUTPUT="$2"
            shift 2
            ;;
        --estimate-only)
            ESTIMATE_ONLY=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage
            exit 1
            ;;
    esac
done

case "$MODE" in
    standard) TOKEN_BUDGET=$STANDARD_TOKEN_BUDGET ;;
    extended) TOKEN_BUDGET=$EXTENDED_TOKEN_BUDGET ;;
    *)
        echo "Invalid mode: $MODE (use standard or extended)" >&2
        exit 1
        ;;
esac

# ---------------------------------------------------------------------------
# Repo root detection
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUST_ROOT="$REPO_ROOT/native/rust"

if [[ ! -f "$RUST_ROOT/Cargo.toml" ]]; then
    echo "Error: cannot find $RUST_ROOT/Cargo.toml" >&2
    exit 1
fi

GIT_SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
GIT_BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"
TIMESTAMP="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
DATE_SLUG="$(date -u '+%Y-%m-%d')"

if [[ -z "$OUTPUT" ]]; then
    OUTPUT="$REPO_ROOT/build/audit-package/ripdpi-security-audit-${MODE}-${GIT_SHA}-${DATE_SLUG}.md"
fi
OUTPUT_DIR="$(dirname "$OUTPUT")"

# ---------------------------------------------------------------------------
# Token tracking
# ---------------------------------------------------------------------------
TOTAL_CHARS=0
SECTION_CHARS=0

estimate_tokens() {
    # Returns estimated token count for a character count
    local chars="$1"
    awk "BEGIN { printf \"%d\", $chars / $CHARS_PER_TOKEN }"
}

add_chars() {
    local n="$1"
    TOTAL_CHARS=$((TOTAL_CHARS + n))
    SECTION_CHARS=$((SECTION_CHARS + n))
}

reset_section() {
    SECTION_CHARS=0
}

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------
OUT_BUF=""

emit() {
    local text="$1"
    local char_count=${#text}
    add_chars "$char_count"
    if [[ "$ESTIMATE_ONLY" == false ]]; then
        OUT_BUF+="$text"
    fi
}

emit_line() {
    emit "$1
"
}

flush_buf() {
    if [[ "$ESTIMATE_ONLY" == false && -n "$OUT_BUF" ]]; then
        printf '%s' "$OUT_BUF" >> "$OUTPUT"
        OUT_BUF=""
    fi
}

detect_lang() {
    local filepath="$1"
    case "$filepath" in
        *.rs)    echo "rust" ;;
        *.kt)    echo "kotlin" ;;
        *.proto) echo "protobuf" ;;
        *.xml)   echo "xml" ;;
        *.toml)  echo "toml" ;;
        *.yml|*.yaml) echo "yaml" ;;
        *.md)    echo "markdown" ;;
        *.properties) echo "properties" ;;
        *.kts)   echo "kotlin" ;;
        *)       echo "text" ;;
    esac
}

comment_prefix() {
    local lang="$1"
    case "$lang" in
        rust|kotlin) echo "//" ;;
        xml|markdown) echo "<!--" ;;
        protobuf)    echo "//" ;;
        *)           echo "#" ;;
    esac
}

emit_file() {
    # Emit a single source file as a fenced code block with auto-detected language
    local filepath="$1"
    local display_path="$2"

    if [[ ! -f "$filepath" ]]; then
        echo "Warning: file not found: $filepath" >&2
        return
    fi

    local lang
    lang="$(detect_lang "$filepath")"
    local prefix
    prefix="$(comment_prefix "$lang")"

    local content
    content="$(cat "$filepath")"
    local header="
#### \`$display_path\`

\`\`\`\`$lang
$prefix File: $display_path
$content
\`\`\`\`
"
    emit "$header"
}

emit_android_file() {
    # Emit an Android/Kotlin file with a repo-relative display path
    local filepath="$1"
    local rel_path="${filepath#"$REPO_ROOT"/}"
    emit_file "$filepath" "$rel_path"
}

emit_android_dir() {
    # Emit all files of specified extension in a directory, sorted alphabetically
    local dir="$1"
    local heading="$2"
    local ext="${3:-.kt}"

    if [[ ! -d "$dir" ]]; then
        echo "Warning: directory not found: $dir" >&2
        return
    fi

    emit_line ""
    emit_line "### $heading"
    emit_line ""

    while IFS= read -r file; do
        emit_android_file "$file"
    done < <(find "$dir" -name "*$ext" -type f | sort)
}

emit_crate_src() {
    # Emit all src/**/*.rs files in a crate, sorted alphabetically
    local crate_dir="$1"
    local crate_name="$2"
    local src_dir="$crate_dir/src"

    if [[ ! -d "$src_dir" ]]; then
        echo "Warning: no src/ in $crate_dir" >&2
        return
    fi

    emit_line ""
    emit_line "### $crate_name"
    emit_line ""

    while IFS= read -r file; do
        local rel_path="${file#"$RUST_ROOT"/}"
        emit_file "$file" "$rel_path"
    done < <(find "$src_dir" -name '*.rs' -type f | sort)
}

emit_selective_crate() {
    # Emit only specified files/globs from a crate
    local crate_dir="$1"
    local crate_name="$2"
    shift 2
    local patterns=("$@")

    emit_line ""
    emit_line "### $crate_name (selected files)"
    emit_line ""

    for pattern in "${patterns[@]}"; do
        while IFS= read -r file; do
            local rel_path="${file#"$RUST_ROOT"/}"
            emit_file "$file" "$rel_path"
        done < <(find "$crate_dir/src" -path "$crate_dir/src/$pattern" -name '*.rs' -type f 2>/dev/null | sort)
    done
}

emit_crate_tests() {
    # Emit tests/ directory for a crate (extended mode)
    local crate_dir="$1"
    local crate_name="$2"
    local tests_dir="$crate_dir/tests"

    if [[ ! -d "$tests_dir" ]]; then
        return
    fi

    emit_line ""
    emit_line "### $crate_name (tests)"
    emit_line ""

    while IFS= read -r file; do
        local rel_path="${file#"$RUST_ROOT"/}"
        emit_file "$file" "$rel_path"
    done < <(find "$tests_dir" -name '*.rs' -type f | sort)
}

# ---------------------------------------------------------------------------
# Section: Architecture Overview
# ---------------------------------------------------------------------------
emit_architecture() {
    emit "
## 1. Architecture Overview

### Project

RIPDPI is an Android VPN/proxy application for network optimization. The native
layer is written in Rust and compiled to \`.so\` libraries loaded via JNI. It
implements a local SOCKS5 proxy with DPI (Deep Packet Inspection) desynchronization
and a TUN-based VPN tunnel.

### Crate Dependency DAG

\`\`\`
ripdpi-android (cdylib, JNI)
  +-- ripdpi-runtime
  |     +-- ripdpi-config
  |     |     +-- ripdpi-packets
  |     +-- ripdpi-desync
  |     |     +-- ripdpi-packets
  |     +-- ripdpi-session
  |     +-- ripdpi-proxy-config
  |     |     +-- ripdpi-config
  |     +-- ripdpi-dns-resolver
  |     +-- ripdpi-failure-classifier
  |     +-- ripdpi-telemetry
  |     +-- ripdpi-ws-tunnel
  |     +-- ripdpi-monitor
  +-- android-support (JNI utilities)

ripdpi-tunnel-android (cdylib, JNI)
  +-- ripdpi-tunnel-core
  |     +-- ripdpi-tun-driver
  |     +-- ripdpi-tunnel-config
  |     +-- smoltcp (TCP/IP stack)
  +-- android-support
\`\`\`

### Data Flow: Proxy Path

\`\`\`
Android App
  |  (Kotlin, Jetpack Compose UI)
  v
VpnService / ProxyService     [core:service module]
  |  (Android foreground service)
  v
JNI Bridge                     [ripdpi-android: lib.rs]
  |  Java_com_poyka_ripdpi_*() entry points
  v
Proxy Runtime                  [ripdpi-runtime]
  |  SOCKS5 listener -> accept -> handshake -> relay
  |  Applies desync strategies per connection
  v
ripdpi-desync                  Manipulates TCP segments (fake packets, TTL tricks,
  |                            split, disorder, out-of-band) to vary handshakes
  v
ripdpi-packets                 Parses TLS ClientHello (SNI), HTTP Host, QUIC Initial
  |                            from UNTRUSTED network data
  v
Platform (linux.rs)            Raw socket options: IP_PKTINFO, SO_MARK, splice()
  |
  v
Network
\`\`\`

### Data Flow: Tunnel Path

\`\`\`
Android App
  v
TunnelService                  [core:service module]
  v
JNI Bridge                     [ripdpi-tunnel-android: lib.rs]
  |  Receives TUN file descriptor from Android
  v
ripdpi-tunnel-core             Reads raw IP packets from TUN fd
  |  +-- ripdpi-tun-driver     Linux TUN ioctl (IFF_TUN, IFF_NO_PI)
  |  +-- smoltcp               Pure-Rust TCP/IP stack for packet reassembly
  |  +-- etherparse            Zero-copy L3/L4 header parsing
  v
SOCKS5 Upstream                Reassembled TCP/UDP forwarded to local SOCKS5 proxy
\`\`\`

### Trust Boundaries

\`\`\`
+--------------------------------------+
| TRUSTED: Android app (Kotlin)        |
|   Protobuf config, UI controls       |
+--------------------------------------+
          | JNI call (raw pointers)     | <-- BOUNDARY 1: JNI/FFI
+--------------------------------------+
| SEMI-TRUSTED: Rust native code       |
|   Config parsing, runtime logic      |
+--------------------------------------+
          | Socket I/O                  | <-- BOUNDARY 2: Network
+--------------------------------------+
| UNTRUSTED: Network data              |
|   TLS records, HTTP responses,       |
|   DNS replies, QUIC packets,         |
|   TUN raw IP packets                 |
+--------------------------------------+
          | libc syscalls               | <-- BOUNDARY 3: OS/Kernel
+--------------------------------------+
| KERNEL: Linux, Android               |
|   TUN device, socket options         |
+--------------------------------------+
\`\`\`

### Threat Model Summary

1. **JNI boundary**: Incorrect lifetime management of JNI references can cause
   use-after-free or null dereference. \`env.unsafe_clone()\` patterns require
   careful audit -- the cloned env must not outlive the JNI call frame.

2. **Parser attacks**: \`ripdpi-packets\` parses untrusted TLS/HTTP/QUIC/DNS data.
   Buffer overreads, panics on malformed input, or integer overflows in length
   fields are the primary risks. Nom-based parsers (\`tls_nom.rs\`) mitigate some
   classes but introduce combinator complexity.

3. **Unsafe platform code**: \`platform/linux.rs\` and \`ripdpi-tun-driver\` use raw
   syscalls (\`ioctl\`, \`setsockopt\`, \`splice\`, \`mmap\`). Incorrect buffer sizes,
   missing error checks, or fd confusion can lead to memory corruption.

4. **Desync strategies**: Fake packet injection, TTL manipulation, and segment
   reordering operate on raw TCP. Logic errors could cause data corruption or
   information leaks to the network.

5. **Crypto misuse**: DNSCrypt (\`crypto_box\`), TLS (\`rustls\`/\`boring\`), and
   Telegram obfuscation (\`aes\`/\`ctr\`) must use correct nonces, key derivation,
   and certificate validation.

6. **File descriptor ownership**: TUN fd is received from Android and must be
   managed correctly -- double-close or use-after-close can crash the process
   or affect unrelated fds.

7. **Concurrency**: The runtime uses \`tokio\` with shared state (\`Arc\`, \`ArcSwap\`,
   atomics). Data races in policy updates or session tracking could cause
   incorrect routing decisions.
"
}

# ---------------------------------------------------------------------------
# Section: Audit Focus Areas (auto-generated unsafe inventory)
# ---------------------------------------------------------------------------
emit_audit_focus() {
    emit_line ""
    emit_line "## 2. Audit Focus Areas"
    emit_line ""
    emit_line "### Unsafe Code Inventory"
    emit_line ""
    emit_line "| File | Line | Context |"
    emit_line "|------|------|---------|"

    # Grep for unsafe blocks, excluding target/ and test support crates
    while IFS=: read -r file line content; do
        local rel="${file#"$RUST_ROOT"/}"
        # Skip target directory and test support crates
        case "$rel" in
            target/*|crates/golden-test-support/*|crates/native-soak-support/*|crates/local-network-fixture/*) continue ;;
        esac
        # Trim whitespace from content
        content="$(echo "$content" | sed 's/^ *//;s/ *$//' | sed 's/|/\\|/g')"
        emit_line "| \`$rel\` | $line | \`$content\` |"
    done < <(grep -rn 'unsafe' "$RUST_ROOT/crates" --include='*.rs' \
        | grep -v '/target/' \
        | grep -v '#\[cfg_attr' \
        | grep -v '// safe:' \
        | grep -v 'unsafe_op_in_unsafe_fn' \
        | grep -v 'forbid(unsafe_code)' \
        | grep -v 'missing_safety_doc' \
        | grep -v 'not_unsafe_ptr_arg_deref' \
        | sort)

    emit_line ""
    emit_line "### Key Audit Questions by Area"
    emit_line ""
    emit "
**JNI Boundary Safety**
- Do all \`env.unsafe_clone()\` usages ensure the cloned env does not outlive the JNI call frame?
- Are JNI string/array references properly released (no leaks)?
- Is panic unwinding correctly caught at the JNI boundary (\`catch_unwind\`)?
- Are null checks performed on all JNI object references before use?

**Parser Security (ripdpi-packets)**
- Can malformed TLS ClientHello cause panics or buffer overreads?
- Are all length fields validated before indexing into slices?
- Do nom parsers handle incomplete input gracefully (no panic)?
- Can crafted QUIC Initial packets cause excessive memory allocation?

**Memory & FD Safety**
- Is the TUN file descriptor correctly owned (no double-close)?
- Are \`mmap\`/\`munmap\` calls balanced with correct sizes?
- Do \`splice()\` calls validate pipe and socket fd validity?
- Are \`from_raw_fd()\` calls wrapped in proper ownership transfer?

**Cryptographic Usage**
- Is \`rustls\` configured with safe defaults (no weak ciphers, proper cert validation)?
- Does DNSCrypt nonce generation use a CSPRNG?
- Is the Telegram AES-CTR obfuscation key derived correctly?
- Are TLS certificate verification callbacks sound?

**Concurrency Safety**
- Can \`ArcSwap\` config updates race with active connections?
- Are atomics used with correct memory orderings?
- Do loom tests cover all critical concurrent paths?
"
}

# ---------------------------------------------------------------------------
# Section: Dependency Manifest
# ---------------------------------------------------------------------------
emit_dependencies() {
    emit_line ""
    emit_line "## 3. Dependency Manifest"
    emit_line ""
    emit_line "### Workspace Cargo.toml"
    emit_line ""
    emit_line '````toml'
    emit "$(cat "$RUST_ROOT/Cargo.toml")"
    emit_line ""
    emit_line '````'
    emit_line ""
    emit_line "### cargo-deny Configuration"
    emit_line ""
    emit_line '````toml'
    emit "$(cat "$RUST_ROOT/deny.toml")"
    emit_line ""
    emit_line '````'
    emit_line ""

    emit "
### Security-Relevant Dependencies

| Dependency | Version | Purpose | Risk Notes |
|------------|---------|---------|------------|
| rustls | 0.23.37 | TLS implementation | ring backend, no unsafe in rustls itself |
| ring | 0.17 | Cryptographic primitives | Audited, widely used |
| boring / tokio-boring | 5 | BoringSSL (Chrome TLS fingerprint) | C FFI, large attack surface |
| crypto_box | 0.9 | ChaCha20 encryption (DNSCrypt) | NaCl-compatible, review nonce handling |
| quinn | 0.11 | QUIC protocol | Uses rustls, review connection handling |
| hickory-resolver | 0.25 | DNS resolution | DoH/DoT backends |
| smoltcp | 0.12 | Pure-Rust TCP/IP stack | No-std capable, used in tunnel |
| etherparse | 0.19 | Zero-copy packet parsing | Audited in-repo |
| jni | 0.21 | JNI bindings | Extensive unsafe, review wrapper safety |
| nix | 0.29 | Unix syscalls | Thin libc wrapper, review ioctl usage |
| fast-socks5 | 0.9 | SOCKS5 protocol | Review auth handling |
| tungstenite | 0.24 | WebSocket (via ripdpi-ws-tunnel) | Review frame parsing |
| aes / ctr | 0.8 | AES-CTR (Telegram obfuscation) | Review key/nonce management |
"
}

# ---------------------------------------------------------------------------
# Section: Crate Summaries (T3/T4 -- no code)
# ---------------------------------------------------------------------------
emit_summaries() {
    emit "
## 7. Crate Summaries (No Code Included)

These crates are not included verbatim due to token budget constraints. Their
public APIs and security-relevant characteristics are summarized below.

### 7.1 ripdpi-monitor (11,437 lines)

**Purpose**: Diagnostics engine for network censorship detection. Probes
HTTP/HTTPS/QUIC/DNS endpoints and Telegram DCs. Executes strategy-based probe
recommendations and reports observations with failure classification.

**Public API**: \`MonitorSession\`, 30+ observation/probe types (\`ProbeTask\`,
\`ProbeResult\`, \`ScanRequest\`, \`ScanReport\`, \`Diagnosis\`, etc.), wire-format
types for JNI serialization.

**Dependencies**: rustls, hickory-resolver (optional), tokio, ripdpi-runtime,
ripdpi-dns-resolver, ripdpi-failure-classifier.

**Security notes**: Uses rustls for TLS verification during probes. No
\`forbid(unsafe_code)\`. Network I/O is delegated to ripdpi-runtime. The main
risk is information leakage through probe traffic patterns.

### 7.2 ripdpi-dns-resolver (3,728 lines)

**Purpose**: Encrypted DNS resolver pool implementing DoH, DoT, and DNSCrypt.
Manages connection health, response caching, and failover between endpoints.

**Public API**: \`EncryptedDnsResolver\`, \`ResolverPool\`, \`ResolverPoolBuilder\`,
\`HealthRegistry\`, \`HealthScoreSnapshot\`, endpoint/transport types.

**Dependencies**: crypto_box (Noise for DNSCrypt), ring, tokio-rustls, rustls,
hickory-proto, hickory-resolver, reqwest.

**Security notes**: Heavy crypto usage. DNSCrypt uses \`crypto_box\` (X25519 +
XSalsa20-Poly1305). Review: nonce generation, key exchange, certificate
validation for DoH/DoT, cache poisoning resistance.

### 7.3 ripdpi-config (2,277 lines)

**Purpose**: Parses and validates core proxy configuration: CLI arguments,
desync rules, TCP/UDP chain actions, offset expressions, CIDR matching, host
filtering, protocol profiles.

**Public API**: Glob re-exports from cache, constants, error, model, parse modules.

**Safety**: \`#![forbid(unsafe_code)]\`. Zero unsafe. Minimal dependencies
(only ripdpi-packets).

### 7.4 ripdpi-proxy-config (2,148 lines)

**Purpose**: High-level proxy configuration for Android UI and CLI. Converts
Protobuf/JSON config payloads into runtime config. Manages network snapshots
(WiFi/cellular) and encrypted DNS context.

**Public API**: \`ProxyConfigPayload\` (CommandLine/Ui), \`ProxyRuntimeContext\`,
\`ProxyUiConfig\`, \`NetworkSnapshot\`, parse functions.

**Dependencies**: ripdpi-config, ripdpi-packets. No crypto, no unsafe.

### 7.5 ripdpi-ws-tunnel (1,226 lines)

**Purpose**: WebSocket tunnel for Telegram connections. Detects Telegram DC IPs,
upgrades connections to WSS, relays MTProto obfuscated2 init packets.

**Public API**: \`WsTunnelConfig\`, \`WsTunnelDecision\`, \`classify_target()\`,
\`is_telegram_ip()\`, \`dc_from_ip()\`.

**Dependencies**: tungstenite, aes/ctr/cipher (AES-CTR), boring (optional),
nix, socket2.

**Security notes**: AES-CTR for Telegram obfuscation. Review key derivation
from init packet, WebSocket frame handling, DC IP detection accuracy.

### 7.6 ripdpi-session (553 lines)

**Purpose**: SOCKS5 session type definitions and protocol constants.

**Public API**: \`SocketType\`, \`TargetAddr\`, 16 SOCKS5 constants.

**Safety**: \`#![forbid(unsafe_code)]\`. Minimal, data-only crate.

### 7.7 ripdpi-telemetry (503 lines)

**Purpose**: Thread-safe latency histograms (HdrHistogram) for DNS, TCP connect,
and TLS handshake metrics.

**Public API**: \`LatencyHistogram\`, \`LatencyPercentiles\`, \`LatencyDistributions\`.

**Dependencies**: hdrhistogram, metrics. No crypto, no unsafe.

### 7.8 ripdpi-failure-classifier (636 lines)

**Purpose**: Classifies censorship and network failures into categories.

**Public API**: \`FailureClass\` (11 variants), \`FailureStage\` (8 variants).

**Safety**: \`#![forbid(unsafe_code)]\`. Pure enum definitions.

### 7.9 ripdpi-tunnel-config (547 lines)

**Purpose**: YAML configuration loader for desktop CLI tunnel settings.

**Public API**: \`Config\`, \`ConfigError\`, \`TunnelConfig\`, \`Socks5Config\`,
\`MapDnsConfig\`, \`MiscConfig\`.

**Dependencies**: serde, serde_yaml. No crypto, no unsafe.

### 7.10 ripdpi-tunnel-core -- Excluded Files

The following tunnel-core files are NOT included in Section 5 due to budget:

- \`session/socks5.rs\` (21K chars) -- SOCKS5 upstream connection from reassembled TCP
- \`session/tcp.rs\` (24K chars) -- TCP session state machine
- \`session/udp.rs\` (18K chars) -- UDP association handling
- \`sessions.rs\` (7K chars) -- Session registry
- \`io_loop/tcp_accept.rs\` (18K chars) -- TCP accept from smoltcp
- \`io_loop/udp_assoc.rs\` (7K chars) -- UDP association from smoltcp
- \`dns_cache/mod.rs\` (13K chars) -- DNS response cache
- \`stats.rs\` (10K chars) -- Tunnel statistics

**Security notes for excluded files**: The session modules handle SOCKS5
upstream connections and could have resource exhaustion or fd leak issues.
\`dns_cache\` could be susceptible to cache poisoning. Available in extended mode.

### 7.11 ripdpi-runtime -- Excluded Files

The following runtime files are NOT included in Section 6 due to budget:

- \`adaptive_fake_ttl.rs\` (289 lines) -- Adaptive TTL learning
- \`adaptive_tuning.rs\` (664 lines) -- Runtime parameter auto-tuning
- \`retry_stealth.rs\` (370 lines) -- Stealth retry strategies
- \`ws_bootstrap.rs\` (189 lines) -- WebSocket tunnel bootstrap
- \`runtime/desync.rs\` (23K chars) -- Desync strategy application
- \`runtime/handshake/*.rs\` (4 files, ~22K chars) -- TLS/WS handshake setup
- \`runtime/routing.rs\` (20K chars) -- Connection routing decisions
- \`runtime/state.rs\` (2.7K chars) -- Proxy state management
- \`runtime/udp.rs\` (19K chars) -- UDP relay
- \`runtime_policy/\` (6 files, ~1.9K lines) -- Autolearn, caching, strategy selection

**Security notes for excluded files**: \`runtime/desync.rs\` applies DPI evasion
to live connections -- logic errors could corrupt traffic. \`runtime/handshake/\`
sets up TLS connections and is relevant for cert validation. \`runtime/routing.rs\`
makes destination decisions. All available in extended mode.
"
}

# ---------------------------------------------------------------------------
# Section: Audit Questions
# ---------------------------------------------------------------------------
emit_audit_questions() {
    emit "
## 8. Audit Questions

Please address the following in your audit findings:

### Critical (must answer)

1. **JNI lifetime safety**: Are there any paths where a JNI reference
   (\`JString\`, \`JObject\`, \`JLongArray\`) or \`unsafe_clone()\`d env outlives
   its valid scope? Identify specific file:line locations.

2. **Parser robustness**: Can any input to \`ripdpi-packets\` (TLS, HTTP, QUIC,
   DNS parsers) cause a panic, buffer overread, or unbounded allocation?
   List each parser entry point and its failure mode.

3. **Unsafe soundness**: For each \`unsafe\` block in the inventory (Section 2),
   assess whether the safety invariants are upheld. Flag any that are unsound
   or insufficiently documented.

4. **FD ownership**: Trace the TUN file descriptor from JNI receipt through
   \`ripdpi-tunnel-core\` to close. Are there double-close or use-after-close
   paths? What about error/panic paths?

### High (should answer)

5. **Crypto configuration**: Is rustls configured with safe defaults? Are there
   any paths that disable certificate verification or allow weak cipher suites?

6. **DNSCrypt nonce handling**: Does \`ripdpi-dns-resolver\` correctly generate
   unique nonces for \`crypto_box\`? Is there a nonce-reuse risk?

7. **Desync side effects**: Can the fake-packet injection in \`ripdpi-desync\`
   cause data corruption, information leakage, or denial of service to the
   local device?

8. **Concurrency bugs**: Are there TOCTOU races in config updates via
   \`ArcSwap\`? Can policy changes during active relay cause panics?

### Medium (nice to have)

9. **Error handling**: Are there \`unwrap()\`/\`expect()\` calls on fallible
   operations that could panic in production (non-test code)?

10. **Integer overflow**: Are there arithmetic operations on untrusted lengths
    or offsets that could wrap on 32-bit targets (armeabi-v7a)?

11. **Resource exhaustion**: Can a malicious SOCKS5 client exhaust memory or
    file descriptors by opening many connections?

12. **Supply chain**: Are all dependencies in \`deny.toml\` audited? Are there
    any with known advisories?

### Android-Specific (if Android sections are included)

13. **VPN Service lifecycle**: Does \`RipDpiVpnService\` correctly handle
    \`onRevoke()\`, process death, and service restart? Can a race between
    \`stopSelf()\` and \`onStartCommand()\` leave the VPN in a bad state?

14. **Protobuf deserialization**: Can a corrupted DataStore file crash the app
    on startup? Is there a recovery path?

15. **Permission model**: Does \`PermissionOrchestration\` correctly handle all
    Android 14+ permission states? Can the app be tricked into running without
    required permissions?

16. **Quick Tile / Automation**: Can \`AutomationLaunchContract\` or
    \`QuickTileService\` be triggered by a malicious app via implicit intents
    or exported components?

17. **Network fingerprinting**: Does \`NetworkFingerprintProvider\` leak
    sensitive network identifiers (BSSID, SSID) to the Rust layer or logs?

18. **Config injection**: Can a crafted JSON payload sent through
    \`RipDpiProxyJsonCodec\` cause the Rust runtime to behave unexpectedly
    (e.g., connect to arbitrary endpoints, disable desync)?
"
}

# ---------------------------------------------------------------------------
# Main: Assemble the document
# ---------------------------------------------------------------------------
main() {
    if [[ "$ESTIMATE_ONLY" == false ]]; then
        mkdir -p "$OUTPUT_DIR"
        : > "$OUTPUT"  # Truncate
    fi

    # --- Header ---
    emit_line "# RIPDPI Security Audit Package"
    emit_line ""
    emit_line "**Generated**: $TIMESTAMP"
    emit_line "**Git**: \`$GIT_SHA\` on \`$GIT_BRANCH\`"
    emit_line "**Mode**: $MODE (code token budget: ~$TOKEN_BUDGET)"
    emit_line ""
    emit_line "---"
    emit_line ""
    flush_buf

    # --- 1. Architecture ---
    reset_section
    emit_architecture
    flush_buf
    local arch_tokens
    arch_tokens=$(estimate_tokens "$SECTION_CHARS")
    echo "Section 1 (Architecture): ~${arch_tokens} tokens" >&2

    # --- 2. Audit Focus ---
    reset_section
    emit_audit_focus
    flush_buf
    local focus_tokens
    focus_tokens=$(estimate_tokens "$SECTION_CHARS")
    echo "Section 2 (Audit Focus): ~${focus_tokens} tokens" >&2

    # --- 3. Dependencies ---
    reset_section
    emit_dependencies
    flush_buf
    local deps_tokens
    deps_tokens=$(estimate_tokens "$SECTION_CHARS")
    echo "Section 3 (Dependencies): ~${deps_tokens} tokens" >&2

    # --- 4. Tier 1: JNI/FFI Boundary (verbatim) ---
    reset_section
    emit_line ""
    emit_line "## 4. Source Code -- Tier 1: JNI/FFI Boundary"
    emit_line ""
    emit_line "All code at the JNI/FFI boundary is included verbatim. Every \`unsafe\` block"
    emit_line "touching Java or libc syscalls is in this section."

    emit_crate_src "$RUST_ROOT/crates/android-support" "android-support"
    emit_crate_src "$RUST_ROOT/crates/ripdpi-android" "ripdpi-android"
    emit_crate_src "$RUST_ROOT/crates/ripdpi-tunnel-android" "ripdpi-tunnel-android"
    emit_crate_src "$RUST_ROOT/crates/ripdpi-tun-driver" "ripdpi-tun-driver"

    # platform/linux.rs from ripdpi-runtime
    emit_line ""
    emit_line "### ripdpi-runtime/platform (unsafe syscalls)"
    emit_line ""
    local platform_dir="$RUST_ROOT/crates/ripdpi-runtime/src/platform"
    for f in "$platform_dir"/mod.rs "$platform_dir"/linux.rs; do
        if [[ -f "$f" ]]; then
            emit_file "$f" "${f#"$RUST_ROOT"/}"
        fi
    done

    flush_buf
    local t1_tokens
    t1_tokens=$(estimate_tokens "$SECTION_CHARS")
    echo "Section 4 (Tier 1 JNI/FFI): ~${t1_tokens} tokens" >&2

    # --- 5. Tier 2: Parsers & Protocol (verbatim) ---
    reset_section
    emit_line ""
    emit_line "## 5. Source Code -- Tier 2: Parsers & Protocol Handling"
    emit_line ""
    emit_line "These crates process untrusted network input. Included verbatim."

    emit_crate_src "$RUST_ROOT/crates/ripdpi-packets" "ripdpi-packets"
    emit_crate_src "$RUST_ROOT/crates/ripdpi-desync" "ripdpi-desync"

    # ripdpi-tunnel-core: selective -- fd ownership, device, io loop, packet
    # processing, ring buffer, classify. Session/stats/dns_cache summarized.
    emit_selective_crate "$RUST_ROOT/crates/ripdpi-tunnel-core" "ripdpi-tunnel-core" \
        "lib.rs" \
        "device.rs" \
        "tunnel_api.rs" \
        "classify.rs" \
        "ring_buffer/mod.rs" \
        "io_loop.rs" \
        "io_loop/bridge.rs" \
        "io_loop/dns_intercept.rs" \
        "io_loop/packet.rs"

    flush_buf
    local t2_tokens
    t2_tokens=$(estimate_tokens "$SECTION_CHARS")
    echo "Section 5 (Tier 2 Parsers): ~${t2_tokens} tokens" >&2

    # --- 6. Tier 2: Core Runtime (selective) ---
    reset_section
    emit_line ""
    emit_line "## 6. Source Code -- Core Runtime (Selected Files)"
    emit_line ""
    emit_line "Selected security-critical files from ripdpi-runtime. Policy/tuning"
    emit_line "modules are omitted to stay within token budget."

    # Core relay path: listener -> handshake -> relay. Omit policy/tuning/adaptive.
    emit_selective_crate "$RUST_ROOT/crates/ripdpi-runtime" "ripdpi-runtime" \
        "lib.rs" \
        "runtime.rs" \
        "sync.rs" \
        "process.rs" \
        "runtime/listeners.rs" \
        "runtime/relay.rs" \
        "runtime/relay/*.rs"

    flush_buf
    local rt_tokens
    rt_tokens=$(estimate_tokens "$SECTION_CHARS")
    echo "Section 6 (Runtime selective): ~${rt_tokens} tokens" >&2

    # --- 7. Summaries ---
    reset_section
    emit_summaries
    flush_buf
    local sum_tokens
    sum_tokens=$(estimate_tokens "$SECTION_CHARS")
    echo "Section 7 (Summaries): ~${sum_tokens} tokens" >&2

    # --- Extended mode: include remaining crates verbatim ---
    if [[ "$MODE" == "extended" ]]; then
        reset_section
        emit_line ""
        emit_line "## E1. Extended: Remaining Crates (Verbatim)"
        emit_line ""

        for crate_dir in \
            "$RUST_ROOT/crates/ripdpi-monitor" \
            "$RUST_ROOT/crates/ripdpi-dns-resolver" \
            "$RUST_ROOT/crates/ripdpi-config" \
            "$RUST_ROOT/crates/ripdpi-proxy-config" \
            "$RUST_ROOT/crates/ripdpi-ws-tunnel" \
            "$RUST_ROOT/crates/ripdpi-session" \
            "$RUST_ROOT/crates/ripdpi-telemetry" \
            "$RUST_ROOT/crates/ripdpi-failure-classifier" \
            "$RUST_ROOT/crates/ripdpi-tunnel-config" \
            "$RUST_ROOT/crates/ripdpi-cli"; do
            local name
            name="$(basename "$crate_dir")"
            emit_crate_src "$crate_dir" "$name"
        done

        # Remaining runtime files not in selective list
        emit_line ""
        emit_line "### ripdpi-runtime (remaining files)"
        emit_line ""
        for f in \
            "$RUST_ROOT/crates/ripdpi-runtime/src/adaptive_fake_ttl.rs" \
            "$RUST_ROOT/crates/ripdpi-runtime/src/adaptive_tuning.rs" \
            "$RUST_ROOT/crates/ripdpi-runtime/src/retry_stealth.rs" \
            "$RUST_ROOT/crates/ripdpi-runtime/src/ws_bootstrap.rs"; do
            if [[ -f "$f" ]]; then
                emit_file "$f" "${f#"$RUST_ROOT"/}"
            fi
        done
        for f in $(find "$RUST_ROOT/crates/ripdpi-runtime/src/runtime_policy" -name '*.rs' -type f | sort); do
            emit_file "$f" "${f#"$RUST_ROOT"/}"
        done
        # runtime/adaptive.rs and runtime/retry.rs
        for f in \
            "$RUST_ROOT/crates/ripdpi-runtime/src/runtime/adaptive.rs" \
            "$RUST_ROOT/crates/ripdpi-runtime/src/runtime/retry.rs"; do
            if [[ -f "$f" ]]; then
                emit_file "$f" "${f#"$RUST_ROOT"/}"
            fi
        done

        flush_buf
        local ext_tokens
        ext_tokens=$(estimate_tokens "$SECTION_CHARS")
        echo "Section E1 (Extended remaining): ~${ext_tokens} tokens" >&2

        # T1/T2 test files
        reset_section
        emit_line ""
        emit_line "## E2. Extended: Test Files (T1/T2 Crates)"
        emit_line ""

        for crate_dir in \
            "$RUST_ROOT/crates/ripdpi-android" \
            "$RUST_ROOT/crates/ripdpi-tunnel-android" \
            "$RUST_ROOT/crates/ripdpi-runtime" \
            "$RUST_ROOT/crates/ripdpi-packets" \
            "$RUST_ROOT/crates/ripdpi-tunnel-core"; do
            local name
            name="$(basename "$crate_dir")"
            emit_crate_tests "$crate_dir" "$name"
        done

        flush_buf
        local test_tokens
        test_tokens=$(estimate_tokens "$SECTION_CHARS")
        echo "Section E2 (Extended tests): ~${test_tokens} tokens" >&2

        # --- E3. Android Architecture: Proto, Manifests, Config ---
        reset_section
        emit_line ""
        emit_line "## E3. Android Architecture: Proto, Manifests, Config"
        emit_line ""
        emit_line "Protobuf schemas, Android manifests, and build configuration."
        emit_line ""

        emit_android_file "$REPO_ROOT/core/data/src/main/proto/app_settings.proto"
        emit_android_file "$REPO_ROOT/core/data/src/main/proto/geosite.proto"
        emit_android_file "$REPO_ROOT/app/src/main/AndroidManifest.xml"
        emit_android_file "$REPO_ROOT/core/service/src/main/AndroidManifest.xml"
        emit_android_file "$REPO_ROOT/core/engine/src/main/AndroidManifest.xml"
        emit_android_file "$REPO_ROOT/core/data/src/main/AndroidManifest.xml"
        emit_android_file "$REPO_ROOT/gradle.properties"
        emit_android_file "$REPO_ROOT/gradle/libs.versions.toml"

        # Architecture docs
        emit_android_file "$REPO_ROOT/docs/native/proxy-engine.md"
        emit_android_file "$REPO_ROOT/docs/native/tunnel.md"

        flush_buf
        local e3_tokens
        e3_tokens=$(estimate_tokens "$SECTION_CHARS")
        echo "Section E3 (Android Architecture): ~${e3_tokens} tokens" >&2

        # --- E4. Kotlin JNI Bridge: core/engine ---
        reset_section
        emit_line ""
        emit_line "## E4. Kotlin JNI Bridge: core/engine"
        emit_line ""
        emit_line "All Kotlin files in the engine module -- the managed-side JNI bridge."
        emit_line "This is BOUNDARY 1 from the Kotlin perspective."

        emit_android_dir \
            "$REPO_ROOT/core/engine/src/main/kotlin" \
            "core:engine (main source)" \
            ".kt"

        flush_buf
        local e4_tokens
        e4_tokens=$(estimate_tokens "$SECTION_CHARS")
        echo "Section E4 (Kotlin JNI Bridge): ~${e4_tokens} tokens" >&2

        # --- E5. Kotlin VPN Service Layer: core/service ---
        reset_section
        emit_line ""
        emit_line "## E5. Kotlin VPN Service Layer: core/service"
        emit_line ""
        emit_line "Android VPN and proxy foreground services, network policy,"
        emit_line "connection lifecycle, fingerprinting, DNS failover."

        emit_android_dir \
            "$REPO_ROOT/core/service/src/main/kotlin" \
            "core:service (main source)" \
            ".kt"

        flush_buf
        local e5_tokens
        e5_tokens=$(estimate_tokens "$SECTION_CHARS")
        echo "Section E5 (Kotlin Service Layer): ~${e5_tokens} tokens" >&2

        # --- E6. Kotlin Data & Config: core/data ---
        reset_section
        emit_line ""
        emit_line "## E6. Kotlin Data & Config: core/data"
        emit_line ""
        emit_line "Configuration models, strategy chains, settings serialization,"
        emit_line "DNS resolver config, activation filters, and data persistence."

        emit_android_dir \
            "$REPO_ROOT/core/data/src/main/kotlin" \
            "core:data (main source)" \
            ".kt"

        flush_buf
        local e6_tokens
        e6_tokens=$(estimate_tokens "$SECTION_CHARS")
        echo "Section E6 (Kotlin Data/Config): ~${e6_tokens} tokens" >&2

        # --- E7. Selected App-Layer Code ---
        reset_section
        emit_line ""
        emit_line "## E7. Selected App-Layer Code"
        emit_line ""
        emit_line "Security-relevant app-layer files: ViewModels that control proxy"
        emit_line "lifecycle, permission orchestration, automation, quick tile service,"
        emit_line "host filtering. UI/Compose presentation code is excluded."
        emit_line ""

        emit_line "### ViewModels & Actions"
        emit_line ""
        for f in \
            "$REPO_ROOT/app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt" \
            "$REPO_ROOT/app/src/main/kotlin/com/poyka/ripdpi/activities/ConfigViewModel.kt" \
            "$REPO_ROOT/app/src/main/kotlin/com/poyka/ripdpi/activities/MainConnectionActions.kt" \
            "$REPO_ROOT/app/src/main/kotlin/com/poyka/ripdpi/activities/MainPermissionActions.kt"; do
            emit_android_file "$f"
        done

        emit_line ""
        emit_line "### Permissions & Automation"
        emit_line ""
        for f in \
            "$REPO_ROOT/app/src/main/kotlin/com/poyka/ripdpi/permissions/PermissionOrchestration.kt" \
            "$REPO_ROOT/app/src/main/kotlin/com/poyka/ripdpi/automation/AutomationLaunchContract.kt"; do
            emit_android_file "$f"
        done

        emit_line ""
        emit_line "### Services & Host Filtering"
        emit_line ""
        for f in \
            "$REPO_ROOT/app/src/main/kotlin/com/poyka/ripdpi/services/QuickTileService.kt" \
            "$REPO_ROOT/app/src/main/kotlin/com/poyka/ripdpi/services/QuickTileController.kt" \
            "$REPO_ROOT/app/src/main/kotlin/com/poyka/ripdpi/hosts/HostPackCatalogRepository.kt"; do
            emit_android_file "$f"
        done

        flush_buf
        local e7_tokens
        e7_tokens=$(estimate_tokens "$SECTION_CHARS")
        echo "Section E7 (App-Layer): ~${e7_tokens} tokens" >&2

        # --- E8. Selected Kotlin Tests ---
        reset_section
        emit_line ""
        emit_line "## E8. Selected Kotlin Tests"
        emit_line ""
        emit_line "JNI contract tests, native bridge tests, fault injection tests,"
        emit_line "VPN service coordinator tests, connection policy tests."
        emit_line ""

        emit_android_dir \
            "$REPO_ROOT/core/engine/src/test/kotlin" \
            "core:engine (tests)" \
            ".kt"

        emit_line ""
        emit_line "### core:service (selected tests)"
        emit_line ""
        for f in \
            "$REPO_ROOT/core/service/src/test/kotlin/com/poyka/ripdpi/services/VpnServiceRuntimeCoordinatorTest.kt" \
            "$REPO_ROOT/core/service/src/test/kotlin/com/poyka/ripdpi/services/ConnectionPolicyResolverTest.kt"; do
            emit_android_file "$f"
        done

        flush_buf
        local e8_tokens
        e8_tokens=$(estimate_tokens "$SECTION_CHARS")
        echo "Section E8 (Kotlin Tests): ~${e8_tokens} tokens" >&2

        # --- E9. App-Layer Summary (excluded code) ---
        reset_section
        emit "
## E9. App-Layer Summary (Excluded Code)

The following app-layer code is NOT included verbatim. Brief summaries are
provided for auditor context.

### UI Screens (~18,700 lines excluded)

Jetpack Compose screens for Home, Settings, Diagnostics, DNS, History, Config,
Onboarding, Permissions, Logs, and About. Pure presentation code using Material 3
components. No direct network or native code interaction -- all logic flows
through ViewModels.

### UI Components (~5,700 lines excluded)

Custom Material 3 components: buttons, cards, dialogs, bottom sheets, snackbars,
text fields, dropdowns, switches, chips, top app bars, scaffolds, navigation.
No security surface.

### Theme (~900 lines excluded)

Color definitions, typography, spacing, shapes, motion/animation tokens.
No runtime behavior.

### Diagnostics UI (~3,500 lines excluded)

ViewModel and UI state factory for the diagnostics screen. Transforms diagnostic
engine results into UI models. No direct network access.

### Settings Screens (~7,500 lines excluded)

Complex forms for advanced proxy settings (desync parameters, HTTPS, HTTP parser,
QUIC, hosts, DNS). These emit config changes via ViewModels which are persisted
to Protobuf DataStore and forwarded to the native layer via JNI. The config
pipeline is auditable via core:data + core:engine sections.

### Diagnostics Engine (core:diagnostics, ~9,200 lines excluded)

Orchestrates network diagnostic scans, generates reports, manages scan sessions.
Uses the Rust ripdpi-monitor crate via JNI. Exports diagnostic archives as ZIP
files -- potential data leakage vector but no direct network manipulation.

### Diagnostics Data (core:diagnostics-data, ~1,580 lines excluded)

Room database for diagnostic session persistence. Stores scan results, network
policy memory, DNS path preferences. Standard Android Room patterns.
"
        flush_buf
        local e9_tokens
        e9_tokens=$(estimate_tokens "$SECTION_CHARS")
        echo "Section E9 (App Summary): ~${e9_tokens} tokens" >&2
    fi

    # --- 8. Audit Questions ---
    reset_section
    emit_audit_questions
    flush_buf
    local q_tokens
    q_tokens=$(estimate_tokens "$SECTION_CHARS")
    echo "Section 8 (Audit Questions): ~${q_tokens} tokens" >&2

    # --- Final budget report ---
    local total_tokens
    total_tokens=$(estimate_tokens "$TOTAL_CHARS")
    echo "" >&2
    echo "============================================" >&2
    echo "Total characters: $TOTAL_CHARS" >&2
    echo "Estimated tokens (chars/$CHARS_PER_TOKEN): ~$total_tokens" >&2
    echo "Token budget ($MODE): $TOKEN_BUDGET" >&2

    if [[ "$total_tokens" -gt "$TOKEN_BUDGET" ]]; then
        local over=$((total_tokens - TOKEN_BUDGET))
        echo "OVER BUDGET by ~$over tokens" >&2
        echo "============================================" >&2
        exit 1
    else
        local remaining=$((TOKEN_BUDGET - total_tokens))
        echo "Under budget by ~$remaining tokens" >&2
        echo "============================================" >&2
    fi

    if [[ "$ESTIMATE_ONLY" == false ]]; then
        echo "" >&2
        echo "Output written to: $OUTPUT" >&2
        echo "File size: $(wc -c < "$OUTPUT") bytes" >&2
    fi
}

main
