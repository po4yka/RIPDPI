//! FFI boundary validation pattern (issue #25).
//!
//! Demonstrates and tests the canonical null/length validation
//! that every FFI extern fn taking a `*const T` / `*mut T` + length
//! pair MUST perform before materialising a Rust slice. The
//! workspace currently has zero extern fn surfaces that accept
//! ptr+len pairs (all extern callbacks take opaque handles or
//! fixed-size buffers), so this file is documentation-by-test:
//! future contributors that add a ptr+len extern fn MUST use this
//! pattern.
//!
//! The pattern catches: (1) null pointer, (2) zero length, (3)
//! length overflow into pointer arithmetic. Without all three
//! checks the resulting `&[u8]` is UB.

/// Canonical FFI ptr+len validation. Returns `None` on any of:
/// - null pointer
/// - zero length (caller may treat as "empty input"; the returned
///   None signals "no buffer to read" so the caller doesn't pass an
///   empty slice into a downstream API that rejects empty inputs)
/// - length that would cause the resulting slice to wrap the address
///   space (`ptr + len > usize::MAX`)
///
/// # Safety
///
/// If the function returns `Some(slice)`, the caller MUST guarantee:
/// - `ptr` points to `len` initialised bytes
/// - the bytes outlive the returned slice's lifetime
/// - no other code mutates the bytes through any other path while
///   the slice is held (Rust aliasing rules)
unsafe fn validate_ffi_buffer<'a>(ptr: *const u8, len: usize) -> Option<&'a [u8]> {
    if ptr.is_null() {
        return None;
    }
    if len == 0 {
        return None;
    }
    // Bounds check: pointer arithmetic ptr + len must not wrap.
    let addr = ptr as usize;
    if addr.checked_add(len).is_none() {
        return None;
    }
    // SAFETY: caller's contract (documented in # Safety above) guarantees
    // `ptr` points to `len` initialised bytes that outlive the slice.
    Some(unsafe { std::slice::from_raw_parts(ptr, len) })
}

#[test]
fn validate_rejects_null_pointer() {
    let result = unsafe { validate_ffi_buffer(std::ptr::null(), 8) };
    assert!(result.is_none(), "null pointer must be rejected");
}

#[test]
fn validate_rejects_zero_length() {
    let buf = [0u8; 4];
    let result = unsafe { validate_ffi_buffer(buf.as_ptr(), 0) };
    assert!(result.is_none(), "zero length must be rejected");
}

#[test]
fn validate_rejects_length_overflow() {
    // Use a non-null pointer with a length that would wrap.
    let buf = [0u8; 4];
    let result = unsafe { validate_ffi_buffer(buf.as_ptr(), usize::MAX) };
    assert!(result.is_none(), "length overflow must be rejected");
}

#[test]
fn validate_accepts_valid_buffer() {
    let buf = [1u8, 2, 3, 4];
    let result = unsafe { validate_ffi_buffer(buf.as_ptr(), buf.len()) };
    let slice = result.expect("valid buffer should be accepted");
    assert_eq!(slice, &[1, 2, 3, 4]);
}
