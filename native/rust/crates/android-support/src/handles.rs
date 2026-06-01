use std::collections::HashMap;
use std::sync::{Arc, PoisonError};

use crate::sync::{AtomicU64, Mutex, Ordering, fetch_add_u64};

pub struct HandleRegistry<T> {
    next: AtomicU64,
    inner: Mutex<HashMap<u64, Arc<T>>>,
}

impl<T> Default for HandleRegistry<T> {
    fn default() -> Self {
        Self::new()
    }
}

impl<T> HandleRegistry<T> {
    pub fn new() -> Self {
        Self { next: AtomicU64::new(1), inner: Mutex::new(HashMap::new()) }
    }

    pub fn insert(&self, value: T) -> u64 {
        let handle = fetch_add_u64(&self.next, 1, Ordering::Relaxed);
        // Ensure handle stays in positive i64 range for JNI compatibility.
        let handle = if handle > i64::MAX as u64 {
            self.next.store(2, Ordering::Relaxed);
            1
        } else {
            handle
        };
        self.inner.lock().unwrap_or_else(PoisonError::into_inner).insert(handle, Arc::new(value));
        handle
    }

    pub fn get(&self, handle: u64) -> Option<Arc<T>> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner).get(&handle).cloned()
    }

    pub fn remove(&self, handle: u64) -> Option<Arc<T>> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner).remove(&handle)
    }
}
