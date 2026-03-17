//! Loom concurrency tests for `HandleRegistry`.
//!
//! Run with: cargo test -p android-support --features loom -- loom
//! (The `loom` feature flag activates exhaustive interleaving exploration.)

#[cfg(feature = "loom")]
mod tests {
    use android_support::HandleRegistry;
    use loom::sync::Arc;

    #[test]
    fn loom_concurrent_insert_returns_unique_handles() {
        loom::model(|| {
            let registry = Arc::new(HandleRegistry::<u32>::new());

            let r1 = registry.clone();
            let t1 = loom::thread::spawn(move || r1.insert(1_u32));

            let r2 = registry.clone();
            let t2 = loom::thread::spawn(move || r2.insert(2_u32));

            let h1 = t1.join().unwrap();
            let h2 = t2.join().unwrap();

            assert_ne!(h1, h2, "concurrent inserts must produce unique handles");
        });
    }

    #[test]
    fn loom_insert_then_concurrent_get_and_remove() {
        loom::model(|| {
            let registry = Arc::new(HandleRegistry::<u32>::new());
            let handle = registry.insert(42_u32);

            let r1 = registry.clone();
            let tget = loom::thread::spawn(move || r1.get(handle));

            let r2 = registry.clone();
            let tremove = loom::thread::spawn(move || r2.remove(handle));

            let got = tget.join().unwrap();
            let removed = tremove.join().unwrap();

            // Either the get saw the value (before remove) or the remove got it.
            // In all interleavings: at least one must have seen Some, and after
            // remove the registry must not contain the handle.
            assert!(got.is_some() || removed.is_some(), "value must be observable before removal");
            assert!(registry.get(handle).is_none(), "handle must be gone after remove");
        });
    }

    #[test]
    fn loom_concurrent_insert_and_remove_different_handles() {
        loom::model(|| {
            let registry = Arc::new(HandleRegistry::<u32>::new());
            let h1 = registry.insert(10_u32);
            let h2 = registry.insert(20_u32);

            let r1 = registry.clone();
            let t1 = loom::thread::spawn(move || r1.remove(h2));

            let r2 = registry.clone();
            let t2 = loom::thread::spawn(move || r2.remove(h1));

            let removed_h2 = t1.join().unwrap();
            let removed_h1 = t2.join().unwrap();

            assert!(removed_h1.is_some(), "h1 must be removed");
            assert!(removed_h2.is_some(), "h2 must be removed");
            assert!(registry.get(h1).is_none());
            assert!(registry.get(h2).is_none());
        });
    }
}
