# EPC-1786264762917455: Epic - Protocol conformance and regression tests

## Objective

Epic - Protocol conformance and regression tests

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- EPC-1786264762918514 DROPPED: Every transport has at least eight upstream-pinned golden fixtures where a conformance bar applies, each round-tripped #epic @item:EPC-1786264762917455
- [x] EPC-1786264762918355 The cross-stack chain test covers single-stream and VLESS Reality mux/multi-stream behavior #epic @item:EPC-1786264762917455
- [x] EPC-1786264762918466 Throughput baselines for all seven transports are captured in scripts/ci/rust-bench-baseline.json and enforced. Done 2026-06-11 (reference-runner capture; nightly enforced lane armed) #epic @item:EPC-1786264762917455
- EPC-1786264762918868 DROPPED: A deliberate framing/behavior regression in any covered layer fails a named test. Partial: VLESS mux, QUIC PMTUD, and throughput gates contribute; full coverage still awaits Salamander fixtures #epic @item:EPC-1786264762917455

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
