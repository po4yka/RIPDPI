//! Loom concurrency tests for `EmbeddedProxyControl`'s shutdown flag ordering.
//!
//! Run with: cargo test -p ripdpi-runtime --features loom -- loom

#[cfg(feature = "loom")]
mod tests {
    use loom::sync::Arc;
    use ripdpi_runtime::EmbeddedProxyControl;

    #[test]
    fn loom_shutdown_visibility() {
        loom::model(|| {
            let control = Arc::new(EmbeddedProxyControl::default());

            let c1 = control.clone();
            let writer = loom::thread::spawn(move || {
                c1.request_shutdown();
            });

            let c2 = control.clone();
            let reader = loom::thread::spawn(move || c2.shutdown_requested());

            writer.join().unwrap();
            let _saw = reader.join().unwrap();

            // After the writer has completed, the final state must be true.
            assert!(control.shutdown_requested(), "shutdown must be true after request");
        });
    }

    #[test]
    fn loom_reset_then_request_ordering() {
        loom::model(|| {
            let control = Arc::new(EmbeddedProxyControl::default());

            let c1 = control.clone();
            let writer = loom::thread::spawn(move || {
                c1.reset_shutdown();
                c1.request_shutdown();
            });

            let c2 = control.clone();
            let reader = loom::thread::spawn(move || c2.shutdown_requested());

            writer.join().unwrap();
            let _saw = reader.join().unwrap();

            assert!(control.shutdown_requested(), "shutdown must be true after request");
        });
    }
}
