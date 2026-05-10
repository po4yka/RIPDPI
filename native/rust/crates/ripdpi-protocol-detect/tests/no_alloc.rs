use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicBool, Ordering};

use ripdpi_protocol_detect::classify_l7;

struct GuardedAllocator;

static PANIC_ON_ALLOC: AtomicBool = AtomicBool::new(false);

unsafe impl GlobalAlloc for GuardedAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        assert!(!PANIC_ON_ALLOC.load(Ordering::Relaxed), "classify_l7 allocated");
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe { System.dealloc(ptr, layout) }
    }
}

#[global_allocator]
static ALLOCATOR: GuardedAllocator = GuardedAllocator;

#[test]
fn classifier_does_not_allocate() {
    let samples: [&[u8]; 6] = [
        &[0x16, 0x03, 0x03, 0, 0x2A, 0x01],
        &[0xC3, 0, 0, 0, 1, 8, 1, 2, 3, 4, 5, 6, 7, 8],
        &[0x16, 0xFE, 0xFD, 0, 0, 0],
        &[0, 1, 0, 0, 0x21, 0x12, 0xA4, 0x42],
        b"d1:a",
        &[0_u8; 64],
    ];

    PANIC_ON_ALLOC.store(true, Ordering::Relaxed);
    for sample in samples {
        let _ = classify_l7(sample, 12345, 443, true);
    }
    PANIC_ON_ALLOC.store(false, Ordering::Relaxed);
}
