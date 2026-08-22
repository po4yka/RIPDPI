use std::thread;

/// Joins scoped worker handles, converting an individual worker panic into a
/// caller-provided fallback value instead of propagating through `expect`.
///
/// Sibling workers that already completed keep their results: joining continues
/// past a panicked handle, mirroring the containment contract of
/// `execution::lanes::tcp::domain_probe`.
pub(super) fn join_with_panic_fallback<T, F>(handles: Vec<thread::ScopedJoinHandle<'_, T>>, mut on_panic: F) -> Vec<T>
where
    F: FnMut(usize) -> T,
{
    handles
        .into_iter()
        .enumerate()
        .map(|(index, handle)| match handle.join() {
            Ok(value) => value,
            Err(_) => on_panic(index),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn panicking_worker_degrades_to_fallback_and_keeps_sibling_results() {
        thread::scope(|scope| {
            let handles =
                vec![scope.spawn(|| 1u32), scope.spawn(|| panic!("candidate worker exploded")), scope.spawn(|| 3u32)];
            let results = join_with_panic_fallback(handles, |index| 100 + index as u32);
            assert_eq!(results, vec![1, 101, 3]);
        });
    }

    #[test]
    fn completed_workers_are_returned_in_spawn_order() {
        thread::scope(|scope| {
            let handles: Vec<_> = (0..4u32).map(|value| scope.spawn(move || value * 2)).collect();
            let results = join_with_panic_fallback(handles, |_| panic!("fallback must not run"));
            assert_eq!(results, vec![0, 2, 4, 6]);
        });
    }
}
