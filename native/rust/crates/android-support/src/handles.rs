use std::collections::HashMap;
use std::sync::{Arc, PoisonError};

use crate::sync::{AtomicU64, Mutex, Ordering, load_u64, store_u64};

const MAX_JLONG_HANDLE: u64 = i64::MAX as u64;

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
        let mut inner = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        let handle = self.next_free_handle(&inner);
        inner.insert(handle, Arc::new(value));
        handle
    }

    fn next_free_handle(&self, inner: &HashMap<u64, Arc<T>>) -> u64 {
        let mut handle = normalize_handle(load_u64(&self.next, Ordering::Relaxed));
        let probe_limit = inner.len().checked_add(1).expect("handle registry size overflow");
        for _ in 0..probe_limit {
            if !inner.contains_key(&handle) {
                store_u64(&self.next, next_handle_after(handle), Ordering::Relaxed);
                return handle;
            }
            handle = next_handle_after(handle);
        }
        panic!("handle registry exhausted");
    }

    pub fn get(&self, handle: u64) -> Option<Arc<T>> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner).get(&handle).cloned()
    }

    pub fn remove(&self, handle: u64) -> Option<Arc<T>> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner).remove(&handle)
    }
}

fn normalize_handle(handle: u64) -> u64 {
    if (1..=MAX_JLONG_HANDLE).contains(&handle) { handle } else { 1 }
}

fn next_handle_after(handle: u64) -> u64 {
    if handle >= MAX_JLONG_HANDLE { 1 } else { handle + 1 }
}

#[cfg(all(test, not(feature = "loom")))]
mod tests {
    use super::*;

    #[test]
    fn insert_wraps_without_colliding_with_live_handle() {
        let registry = HandleRegistry::<&'static str>::new();
        let first = registry.insert("first");
        assert_eq!(first, 1);

        store_u64(&registry.next, MAX_JLONG_HANDLE, Ordering::Relaxed);
        let near_max = registry.insert("near-max");
        assert_eq!(near_max, MAX_JLONG_HANDLE);

        let wrapped = registry.insert("wrapped");
        assert_eq!(wrapped, 2, "wrapped allocation must skip the live handle 1");

        assert_eq!(*registry.get(first).expect("first handle live"), "first");
        assert_eq!(*registry.get(near_max).expect("near-max handle live"), "near-max");
        assert_eq!(*registry.get(wrapped).expect("wrapped handle live"), "wrapped");
        assert_ne!(first, wrapped);
        assert!((1..=MAX_JLONG_HANDLE).contains(&wrapped));
    }
}
