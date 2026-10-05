#[cfg(any(target_os = "android", target_os = "linux", test))]
use std::ffi::CString;

use jni::objects::JValue;
use jni::sys::{jint, jobject};
use jni::{EnvUnowned, Outcome, jni_sig, jni_str};

struct TunKernelIdentity {
    interface_name: String,
    interface_index: jint,
}

/// Borrow the live original descriptor; never duplicate, close or retain it.
pub(crate) fn tun_kernel_identity_entry(mut env: EnvUnowned<'_>, tun_fd: jint) -> jobject {
    tun_kernel_identity_entry_with(&mut env, || read_tun_kernel_identity(tun_fd))
}

fn tun_kernel_identity_entry_with(
    env: &mut EnvUnowned<'_>,
    read: impl FnOnce() -> Option<TunKernelIdentity>,
) -> jobject {
    match env
        .with_env(move |env| -> jni::errors::Result<jobject> {
            let Some(identity) = read() else { return Ok(std::ptr::null_mut()) };
            let name = env.new_string(identity.interface_name)?;
            let result = env.new_object(
                jni_str!("com/poyka/ripdpi/core/TunKernelIdentity"),
                jni_sig!("(Ljava/lang/String;I)V"),
                &[JValue::Object(&name), JValue::Int(identity.interface_index)],
            )?;
            Ok(result.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(identity) => identity,
        // Preserve a pending JVM exception for the managed reader to handle.
        // No JNI call or raw identity/error logging follows failure.
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(all(test, not(feature = "loom")))]
pub(crate) fn tun_kernel_identity_panic_entry_for_test(mut env: EnvUnowned<'_>) -> jobject {
    tun_kernel_identity_entry_with(&mut env, || panic!("injected TUN identity read panic"))
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn read_tun_kernel_identity(tun_fd: jint) -> Option<TunKernelIdentity> {
    use nix::libc;

    if tun_fd < 0 {
        return None;
    }
    // Construct the Linux/Android ABI directly. TUNGETIFF writes the output
    // name and flags; no inactive union member or padding is read here.
    let mut request =
        libc::ifreq { ifr_name: [0; libc::IFNAMSIZ], ifr_ifru: libc::__c_anonymous_ifr_ifru { ifru_flags: 0 } };
    // SAFETY: request is an aligned, initialized, writable ifreq for the entire
    // synchronous Linux TUNGETIFF ioctl. The JVM caller retains the live fd
    // until this method returns. ioctl neither owns nor closes the descriptor.
    let status = unsafe { libc::ioctl(tun_fd, libc::TUNGETIFF, &raw mut request) };
    if status != 0 {
        return None;
    }
    // SAFETY: successful TUNGETIFF wrote ifr_name and ifru_flags according to
    // the Linux TUN UAPI; only that initialized integer union member is read.
    let flags = unsafe { request.ifr_ifru.ifru_flags };
    let name = request.ifr_name.map(|byte| byte as u8);
    decode_tun_identity(&name, flags, |name| {
        // SAFETY: CString guarantees a live, NUL-terminated interface name for
        // this synchronous call. if_nametoindex reads it without retaining it.
        unsafe { libc::if_nametoindex(name.as_ptr()) }
    })
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn read_tun_kernel_identity(_tun_fd: jint) -> Option<TunKernelIdentity> {
    None
}

#[cfg(any(target_os = "android", target_os = "linux", test))]
fn decode_tun_identity(
    name: &[u8],
    flags: i16,
    interface_index: impl FnOnce(&CString) -> u32,
) -> Option<TunKernelIdentity> {
    // Linux include/uapi/linux/if_tun.h: IFF_TUN=1, IFF_TAP=2.
    if flags & 3 != 1 {
        return None;
    }
    let terminator = name.iter().position(|byte| *byte == 0)?;
    let bytes = name.get(..terminator)?.to_vec();
    if bytes.is_empty() {
        return None;
    }
    let interface_name = String::from_utf8(bytes.clone()).ok()?;
    let c_name = CString::new(bytes).ok()?;
    let interface_index = jint::try_from(interface_index(&c_name)).ok().filter(|index| *index > 0)?;
    Some(TunKernelIdentity { interface_name, interface_index })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decoded_identity_requires_tun_flag_terminated_name_and_nonzero_index() {
        let identity = decode_tun_identity(b"tun7\0ignored", 1 | 0x1000, |name| {
            assert_eq!(name.to_bytes(), b"tun7");
            17
        })
        .expect("valid TUN identity");
        assert_eq!(identity.interface_name, "tun7");
        assert_eq!(identity.interface_index, 17);
        for (name, flags, index) in [
            (b"tun7\0".as_slice(), 2, 17),
            (b"tun7\0".as_slice(), 3, 17),
            (b"tun7\0".as_slice(), 0, 17),
            (b"tun7".as_slice(), 1, 17),
            (b"\0".as_slice(), 1, 17),
            (b"\xff\0".as_slice(), 1, 17),
            (b"tun7\0".as_slice(), 1, 0),
            (b"tun7\0".as_slice(), 1, u32::MAX),
        ] {
            assert!(decode_tun_identity(name, flags, |_| index).is_none());
        }
    }

    #[test]
    fn invalid_descriptor_is_unavailable() {
        assert!(read_tun_kernel_identity(-1).is_none());
    }

    #[cfg(any(target_os = "android", target_os = "linux"))]
    #[test]
    fn non_tun_descriptor_is_unavailable_and_remains_open() {
        use std::os::fd::AsRawFd;
        let file = tempfile::tempfile().expect("temporary non-TUN descriptor");
        assert!(read_tun_kernel_identity(file.as_raw_fd()).is_none());
        assert!(file.metadata().is_ok(), "read must not close the borrowed fd");
    }
}
