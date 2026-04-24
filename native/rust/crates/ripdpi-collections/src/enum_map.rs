//! Fixed-size map keyed by enum variant with O(1) lookup.
//!
//! Inspired by protolens's `EnumMap` pattern. Uses a fixed `Vec<Option<V>>`
//! indexed by the enum's discriminant for guaranteed constant-time access.

use std::marker::PhantomData;

/// Trait for enum types usable as [`EnumMap`] keys.
///
/// Implementors must provide a stable mapping from variant to `usize` index
/// and declare the total number of variants.
pub trait EnumKey: Copy {
    /// Total number of enum variants.
    const COUNT: usize;
    /// Map this variant to a unique index in `0..COUNT`.
    fn index(self) -> usize;
}

/// Fixed-size map keyed by enum variant with O(1) insert, get, and contains.
pub struct EnumMap<K: EnumKey, V> {
    data: Vec<Option<V>>,
    _phantom: PhantomData<K>,
}

impl<K: EnumKey, V> EnumMap<K, V> {
    /// Create a new empty map with capacity for all variants.
    pub fn new() -> Self {
        let mut data = Vec::with_capacity(K::COUNT);
        data.resize_with(K::COUNT, || None);
        Self { data, _phantom: PhantomData }
    }

    /// Insert a value for the given key. Replaces any existing value.
    pub fn insert(&mut self, key: K, value: V) -> Option<V> {
        let idx = key.index();
        self.data[idx].replace(value)
    }

    /// Get a reference to the value for the given key.
    pub fn get(&self, key: K) -> Option<&V> {
        self.data[key.index()].as_ref()
    }

    /// Get a mutable reference to the value for the given key.
    pub fn get_mut(&mut self, key: K) -> Option<&mut V> {
        self.data[key.index()].as_mut()
    }

    /// Check whether a value is set for the given key.
    pub fn contains(&self, key: K) -> bool {
        self.data[key.index()].is_some()
    }

    /// Iterate over all set values.
    pub fn values(&self) -> impl Iterator<Item = &V> {
        self.data.iter().filter_map(Option::as_ref)
    }
}

impl<K: EnumKey, V> Default for EnumMap<K, V> {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    #[repr(u8)]
    enum Color {
        Red = 0,
        Green = 1,
        Blue = 2,
    }

    impl EnumKey for Color {
        const COUNT: usize = 3;
        fn index(self) -> usize {
            self as usize
        }
    }

    #[test]
    fn insert_and_get() {
        let mut map = EnumMap::<Color, &str>::new();
        map.insert(Color::Red, "red");
        map.insert(Color::Blue, "blue");

        assert_eq!(map.get(Color::Red), Some(&"red"));
        assert_eq!(map.get(Color::Blue), Some(&"blue"));
        assert_eq!(map.get(Color::Green), None);
    }

    #[test]
    fn contains() {
        let mut map = EnumMap::<Color, i32>::new();
        assert!(!map.contains(Color::Green));
        map.insert(Color::Green, 42);
        assert!(map.contains(Color::Green));
    }

    #[test]
    fn insert_replaces_existing() {
        let mut map = EnumMap::<Color, &str>::new();
        let old = map.insert(Color::Red, "first");
        assert_eq!(old, None);
        let old = map.insert(Color::Red, "second");
        assert_eq!(old, Some("first"));
        assert_eq!(map.get(Color::Red), Some(&"second"));
    }

    #[test]
    fn values_iterates_set_entries() {
        let mut map = EnumMap::<Color, i32>::new();
        map.insert(Color::Red, 1);
        map.insert(Color::Blue, 3);

        let mut vals: Vec<_> = map.values().copied().collect();
        vals.sort();
        assert_eq!(vals, vec![1, 3]);
    }

    #[test]
    fn default_is_empty() {
        let map = EnumMap::<Color, String>::default();
        assert!(!map.contains(Color::Red));
        assert!(!map.contains(Color::Green));
        assert!(!map.contains(Color::Blue));
        assert_eq!(map.values().count(), 0);
    }
}
