//! Acceptance benchmarks for the io_uring driver (io_uring architecture note).
//!
//! Measures end-to-end latency for two production paths:
//!
//! - **Park/unpark wakeup**: time to drive a single submission through
//!   `IoUringDriver` + `block_on_completion`. Captures the cost of the
//!   `thread::park`-based waker that replaced the prior CQE polling spin
//!   (`fe7b7cd4`). The CompletionFuture roundtrip implicitly exercises
//!   the registry register/complete handshake under the new waker.
//! - **Registered-buffer TX path**: time to acquire a `RegisteredBufferPool`
//!   slot, stage payload bytes, submit `WriteFixed`, and reap the
//!   completion. Compared against the plain `Write` path so the
//!   marginal cost of the registered-buffer machinery is visible.
//!
//! All benches use `/dev/null` as the target fd so the kernel side accepts
//! any payload size in O(1) and the measurement isolates io_uring driver
//! overhead, not disk or network latency.
//!
//! On platforms where io_uring is unavailable (CI runners without kernel
//! support, non-Linux hosts) the harness exits cleanly with a printed
//! notice — never a panic — so `cargo bench --workspace` stays green
//! everywhere.

#[cfg(any(target_os = "linux", target_os = "android"))]
use std::fs::OpenOptions;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::os::fd::AsRawFd;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::sync::Arc;
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::time::Duration;

#[cfg(any(target_os = "linux", target_os = "android"))]
use std::hint::black_box;

#[cfg(any(target_os = "linux", target_os = "android"))]
use criterion::{criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
#[cfg(any(target_os = "linux", target_os = "android"))]
use io_uring::IoUring;
#[cfg(any(target_os = "linux", target_os = "android"))]
use ripdpi_io_uring::{block_on_completion, io_uring_capabilities, IoUringDriver, RegisteredBufferPool};

#[cfg(any(target_os = "linux", target_os = "android"))]
const POOL_CAPACITY: u16 = 16;
#[cfg(any(target_os = "linux", target_os = "android"))]
const POOL_BUFFER_SIZE: usize = 4096;
#[cfg(any(target_os = "linux", target_os = "android"))]
const PAYLOAD_SIZES: [usize; 4] = [64, 512, 1500, 4096];

#[cfg(any(target_os = "linux", target_os = "android"))]
fn try_driver() -> Option<IoUringDriver> {
    let caps = io_uring_capabilities();
    if !caps.available {
        return None;
    }
    let probe_ring = IoUring::new(8).ok()?;
    let pool = Arc::new(RegisteredBufferPool::new(&probe_ring, POOL_CAPACITY, POOL_BUFFER_SIZE).ok()?);
    IoUringDriver::start(pool).ok()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn bench_park_unpark_roundtrip(c: &mut Criterion) {
    let driver = match try_driver() {
        Some(d) => d,
        None => {
            eprintln!("io_uring unavailable; skipping park/unpark benchmark");
            return;
        }
    };
    let dev_null = match OpenOptions::new().write(true).open("/dev/null") {
        Ok(f) => f,
        Err(err) => {
            eprintln!("could not open /dev/null: {err}; skipping bench");
            return;
        }
    };
    let fd = dev_null.as_raw_fd();

    let mut group = c.benchmark_group("io_uring/park_unpark/plain_write");
    group.measurement_time(Duration::from_secs(5));
    group.sample_size(50);
    for size in PAYLOAD_SIZES {
        let buffer = vec![0xa5_u8; size];
        group.throughput(Throughput::Bytes(size as u64));
        group.bench_with_input(BenchmarkId::from_parameter(size), &buffer, |b, payload| {
            b.iter(|| {
                let future = driver.write(fd, payload.clone());
                let result = block_on_completion(future);
                black_box(result);
            });
        });
    }
    group.finish();
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn bench_registered_buffer_tx(c: &mut Criterion) {
    let driver = match try_driver() {
        Some(d) => d,
        None => {
            eprintln!("io_uring unavailable; skipping registered-buffer TX benchmark");
            return;
        }
    };
    let dev_null = match OpenOptions::new().write(true).open("/dev/null") {
        Ok(f) => f,
        Err(err) => {
            eprintln!("could not open /dev/null: {err}; skipping bench");
            return;
        }
    };
    let fd = dev_null.as_raw_fd();
    let pool = driver.pool().clone();

    let mut group = c.benchmark_group("io_uring/park_unpark/write_fixed");
    group.measurement_time(Duration::from_secs(5));
    group.sample_size(50);
    for size in PAYLOAD_SIZES.into_iter().filter(|s| *s <= POOL_BUFFER_SIZE) {
        let payload = vec![0xa5_u8; size];
        group.throughput(Throughput::Bytes(size as u64));
        group.bench_with_input(BenchmarkId::from_parameter(size), &payload, |b, payload| {
            b.iter(|| {
                let mut handle = pool.acquire().expect("pool acquire");
                handle.as_mut_buf()[..payload.len()].copy_from_slice(payload);
                let buf_index = handle.buf_index();
                let pending = handle.into_pending();
                let future = driver.write_fixed(fd, buf_index, payload.len() as u32);
                let result = block_on_completion(future);
                pending.complete(&pool);
                black_box(result);
            });
        });
    }
    group.finish();
}

#[cfg(any(target_os = "linux", target_os = "android"))]
criterion_group! {
    name = io_uring_acceptance;
    config = Criterion::default()
        .sample_size(50)
        .measurement_time(Duration::from_secs(5));
    targets = bench_park_unpark_roundtrip, bench_registered_buffer_tx
}

#[cfg(any(target_os = "linux", target_os = "android"))]
criterion_main!(io_uring_acceptance);

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn main() {
    eprintln!("io_uring acceptance benchmarks are Linux/Android only; nothing to run on this platform.");
}
