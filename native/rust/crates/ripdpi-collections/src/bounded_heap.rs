//! Fixed-capacity min-heap with eviction support.
//!
//! Inspired by protolens's `Heap<T>` but uses safe Rust (`Vec<Option<T>>`)
//! and adds [`push_or_evict`](BoundedHeap::push_or_evict) for bounded
//! collections that must accept new items by evicting the minimum.

use std::cmp::Ordering;

/// Fixed-capacity min-heap.
///
/// The smallest element (by `Ord`) is at the root and is evicted first
/// when capacity is exceeded via [`push_or_evict`](Self::push_or_evict).
pub struct BoundedHeap<T> {
    data: Vec<Option<T>>,
    len: usize,
}

impl<T: Ord> BoundedHeap<T> {
    /// Create a new heap with the given maximum capacity.
    pub fn new(capacity: usize) -> Self {
        let mut data = Vec::with_capacity(capacity);
        data.resize_with(capacity, || None);
        Self { data, len: 0 }
    }

    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    pub fn capacity(&self) -> usize {
        self.data.len()
    }

    pub fn is_full(&self) -> bool {
        self.len >= self.data.len()
    }

    /// Push an item. Returns `false` if the heap is full (item not inserted).
    pub fn push(&mut self, item: T) -> bool {
        if self.is_full() {
            return false;
        }
        self.data[self.len] = Some(item);
        self.len += 1;
        self.sift_up(self.len - 1);
        true
    }

    /// Push an item, evicting the minimum if full.
    ///
    /// Returns the evicted item when eviction occurs, `None` otherwise.
    pub fn push_or_evict(&mut self, item: T) -> Option<T> {
        if !self.is_full() {
            self.data[self.len] = Some(item);
            self.len += 1;
            self.sift_up(self.len - 1);
            return None;
        }
        // Evict the minimum (root), replace with new item.
        let evicted = self.data[0].take();
        self.data[0] = Some(item);
        self.sift_down(0);
        evicted
    }

    /// Remove and return the minimum item.
    pub fn pop(&mut self) -> Option<T> {
        if self.len == 0 {
            return None;
        }
        self.len -= 1;
        if self.len == 0 {
            return self.data[0].take();
        }
        let last = self.data[self.len].take();
        let min = std::mem::replace(&mut self.data[0], last);
        self.sift_down(0);
        min
    }

    /// View the minimum item without removing.
    pub fn peek(&self) -> Option<&T> {
        if self.len == 0 {
            None
        } else {
            self.data[0].as_ref()
        }
    }

    /// Remove the first item matching the predicate.
    ///
    /// O(n) scan + O(log n) heap repair.
    pub fn remove_by<F: FnMut(&T) -> bool>(&mut self, mut predicate: F) -> Option<T> {
        let index = (0..self.len).find(|&i| self.data[i].as_ref().is_some_and(&mut predicate))?;
        self.remove_at(index)
    }

    /// Drain all items in arbitrary order.
    pub fn drain(&mut self) -> impl Iterator<Item = T> + '_ {
        let count = self.len;
        self.len = 0;
        self.data[..count].iter_mut().filter_map(Option::take)
    }

    fn remove_at(&mut self, index: usize) -> Option<T> {
        if index >= self.len {
            return None;
        }
        self.len -= 1;
        if index == self.len {
            return self.data[index].take();
        }
        let last = self.data[self.len].take();
        let removed = std::mem::replace(&mut self.data[index], last);
        // Repair: the replacement may need to go up or down.
        if index > 0 && self.cmp_at(index, (index - 1) / 2) == Ordering::Less {
            self.sift_up(index);
        } else {
            self.sift_down(index);
        }
        removed
    }

    fn sift_up(&mut self, mut index: usize) {
        while index > 0 {
            let parent = (index - 1) / 2;
            if self.cmp_at(index, parent) != Ordering::Less {
                break;
            }
            self.data.swap(index, parent);
            index = parent;
        }
    }

    fn sift_down(&mut self, mut index: usize) {
        loop {
            let left = 2 * index + 1;
            let right = 2 * index + 2;
            let mut smallest = index;

            if left < self.len && self.cmp_at(left, smallest) == Ordering::Less {
                smallest = left;
            }
            if right < self.len && self.cmp_at(right, smallest) == Ordering::Less {
                smallest = right;
            }
            if smallest == index {
                break;
            }
            self.data.swap(index, smallest);
            index = smallest;
        }
    }

    fn cmp_at(&self, a: usize, b: usize) -> Ordering {
        match (self.data[a].as_ref(), self.data[b].as_ref()) {
            (Some(a), Some(b)) => a.cmp(b),
            _ => Ordering::Equal,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_pop_maintains_min_ordering() {
        let mut heap = BoundedHeap::new(10);
        for &v in &[5, 3, 7, 1, 4, 6, 2] {
            assert!(heap.push(v));
        }
        let mut sorted = Vec::new();
        while let Some(v) = heap.pop() {
            sorted.push(v);
        }
        assert_eq!(sorted, vec![1, 2, 3, 4, 5, 6, 7]);
    }

    #[test]
    fn peek_returns_minimum() {
        let mut heap = BoundedHeap::new(5);
        heap.push(10);
        heap.push(3);
        heap.push(7);
        assert_eq!(heap.peek(), Some(&3));
    }

    #[test]
    fn push_returns_false_when_full() {
        let mut heap = BoundedHeap::new(2);
        assert!(heap.push(1));
        assert!(heap.push(2));
        assert!(!heap.push(3));
        assert_eq!(heap.len(), 2);
    }

    #[test]
    fn push_or_evict_evicts_minimum() {
        let mut heap = BoundedHeap::new(3);
        heap.push(5);
        heap.push(3);
        heap.push(7);
        // Full — evict minimum (3).
        let evicted = heap.push_or_evict(1);
        assert_eq!(evicted, Some(3));
        assert_eq!(heap.len(), 3);
        // New minimum should be 1.
        assert_eq!(heap.peek(), Some(&1));
    }

    #[test]
    fn push_or_evict_no_eviction_when_not_full() {
        let mut heap = BoundedHeap::new(5);
        heap.push(10);
        let evicted = heap.push_or_evict(20);
        assert_eq!(evicted, None);
        assert_eq!(heap.len(), 2);
    }

    #[test]
    fn remove_by_finds_and_removes() {
        let mut heap = BoundedHeap::new(5);
        heap.push(10);
        heap.push(20);
        heap.push(30);
        let removed = heap.remove_by(|&v| v == 20);
        assert_eq!(removed, Some(20));
        assert_eq!(heap.len(), 2);
        // Remaining: 10, 30 in heap order.
        assert_eq!(heap.pop(), Some(10));
        assert_eq!(heap.pop(), Some(30));
    }

    #[test]
    fn remove_by_returns_none_when_not_found() {
        let mut heap = BoundedHeap::new(3);
        heap.push(1);
        heap.push(2);
        assert_eq!(heap.remove_by(|&v| v == 99), None);
        assert_eq!(heap.len(), 2);
    }

    #[test]
    fn drain_returns_all_items() {
        let mut heap = BoundedHeap::new(5);
        heap.push(3);
        heap.push(1);
        heap.push(2);
        let mut items: Vec<_> = heap.drain().collect();
        items.sort();
        assert_eq!(items, vec![1, 2, 3]);
        assert!(heap.is_empty());
    }

    #[test]
    fn empty_heap_operations() {
        let mut heap: BoundedHeap<i32> = BoundedHeap::new(5);
        assert!(heap.is_empty());
        assert_eq!(heap.pop(), None);
        assert_eq!(heap.peek(), None);
        assert_eq!(heap.remove_by(|_| true), None);
    }

    #[test]
    fn capacity_zero_rejects_all() {
        let mut heap = BoundedHeap::new(0);
        assert!(!heap.push(1));
        assert!(heap.is_full());
    }

    #[test]
    fn struct_ordering() {
        #[derive(Debug, Eq, PartialEq)]
        struct Session {
            id: u32,
            created_at: u64,
        }
        impl Ord for Session {
            fn cmp(&self, other: &Self) -> Ordering {
                self.created_at.cmp(&other.created_at)
            }
        }
        impl PartialOrd for Session {
            fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
                Some(self.cmp(other))
            }
        }

        let mut heap = BoundedHeap::new(3);
        heap.push(Session { id: 1, created_at: 100 });
        heap.push(Session { id: 2, created_at: 50 });
        heap.push(Session { id: 3, created_at: 200 });

        // Evict oldest (created_at=50).
        let evicted = heap.push_or_evict(Session { id: 4, created_at: 150 });
        assert_eq!(evicted.unwrap().id, 2);

        // Remove by id.
        let removed = heap.remove_by(|s| s.id == 1);
        assert_eq!(removed.unwrap().created_at, 100);
    }
}
