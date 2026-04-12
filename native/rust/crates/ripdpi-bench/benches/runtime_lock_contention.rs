use std::sync::{Arc, RwLock};
use std::thread;
use std::time::Duration;

use criterion::{criterion_group, criterion_main, Criterion, Throughput};
use ripdpi_runtime::bench_support::{RetryLane, RetryPacer, RetrySignature, StrategyEvolver};

const THREADS: usize = 4;
const READS_PER_THREAD: usize = 2_048;

fn run_parallel_reads<F>(f: &F)
where
    F: Fn() + Sync,
{
    thread::scope(|scope| {
        for _ in 0..THREADS {
            scope.spawn(|| {
                for _ in 0..READS_PER_THREAD {
                    f();
                }
            });
        }
    });
}

fn sample_retry_signature() -> RetrySignature {
    RetrySignature::new("bench-scope", RetryLane::TcpTls, "video.example.test", 0, 0xDEADBEEF)
}

fn bench_strategy_evolver_contention(c: &mut Criterion) {
    let mut group = c.benchmark_group("runtime-lock-contention/strategy-evolver");
    group.throughput(Throughput::Elements((THREADS * READS_PER_THREAD) as u64));

    group.bench_function("disabled-fast-path", |b| {
        b.iter(|| {
            for _ in 0..(THREADS * READS_PER_THREAD) {
                std::hint::black_box(None::<()>);
            }
        });
    });

    let mut pending = StrategyEvolver::new(true, 0.0);
    let _ = pending.suggest_hints();
    let pending = Arc::new(RwLock::new(pending));
    group.bench_function("pending-experiment/read-4threads", |b| {
        b.iter(|| {
            run_parallel_reads(&|| {
                let guard = pending.read().expect("read evolver");
                std::hint::black_box(guard.peek_hints());
            });
        });
    });

    group.finish();
}

fn bench_retry_pacer_contention(c: &mut Criterion) {
    let mut group = c.benchmark_group("runtime-lock-contention/retry-pacer");
    group.throughput(Throughput::Elements((THREADS * READS_PER_THREAD) as u64));

    let signature = sample_retry_signature();
    let mut pacer = RetryPacer::default();
    let _ = pacer.record_failure(&signature, 5_000);
    let pacer = Arc::new(RwLock::new(pacer));
    group.bench_function("penalty-for/read-4threads", |b| {
        b.iter(|| {
            run_parallel_reads(&|| {
                let guard = pacer.read().expect("read pacer");
                std::hint::black_box(guard.penalty_for(&signature, 5_500));
            });
        });
    });

    group.finish();
}

criterion_group! {
    name = runtime_lock_contention;
    config = Criterion::default()
        .sample_size(20)
        .measurement_time(Duration::from_secs(10));
    targets = bench_strategy_evolver_contention, bench_retry_pacer_contention
}

criterion_main!(runtime_lock_contention);
