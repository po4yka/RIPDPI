use ::pkcs8::{
    EncryptedPrivateKeyInfoRef,
    der::Decode,
    pkcs5::{EncryptionScheme, pbes2::Kdf},
};
use ssh_key::{Kdf as SshKdf, PrivateKey};

use super::{Format, read_pem};
use crate::keys::Error;

/// Maximum encoded private-key input size in bytes.
pub const MAX_SECRET_KEY_BYTES: usize = 64 * 1024;

/// Check private-key input and KDF costs without deriving a key.
///
/// The input limit is 64 KiB. bcrypt permits 1..=64 rounds; PBKDF2 permits
/// 1..=100,000 iterations. scrypt permits at most 32 MiB in its three buffers
/// and N*r*p <= 1,048,576. PPK v3 permits at most 32 MiB, 64 passes, 8 lanes,
/// and memory*passes <= 524,288 KiB per Argon2 call (the decoder calls it twice).
/// These work limits do not set a wall-clock deadline. Key validity and the
/// password must still be checked by `decode_secret_key`.
pub fn validate_secret_key_resources(secret: &str) -> Result<(), Error> {
    if secret.len() > MAX_SECRET_KEY_BYTES {
        return Err(Error::KeyResourceLimit);
    }
    if secret.trim().starts_with("PuTTY-User-Key-File-") {
        return validate_ppk_resources(secret);
    }
    let (format, secret) = read_pem(secret)?;
    match format {
        Some(Format::Openssh) => match PrivateKey::from_bytes(&secret)?.kdf() {
            SshKdf::None => Ok(()),
            SshKdf::Bcrypt { rounds, .. } if (1..=64).contains(rounds) => Ok(()),
            _ => Err(Error::KeyResourceLimit),
        },
        Some(Format::Pkcs8Encrypted) => validate_pkcs8_resources(EncryptedPrivateKeyInfoRef::from_der(&secret)?),
        Some(Format::Pkcs8) => {
            // The decoder also accepts encrypted DER under plain PEM armor.
            if let Ok(info) = EncryptedPrivateKeyInfoRef::from_der(&secret) {
                validate_pkcs8_resources(info)?;
            }
            Ok(())
        }
        _ => Ok(()),
    }
}

fn validate_pkcs8_resources(info: EncryptedPrivateKeyInfoRef<'_>) -> Result<(), Error> {
    let EncryptionScheme::Pbes2(params) = info.encryption_algorithm else {
        // The pinned decoder does not decrypt PBES1.
        return Ok(());
    };
    let allowed = match params.kdf {
        Kdf::Pbkdf2(params) => (1..=100_000).contains(&params.iteration_count),
        Kdf::Scrypt(params) => {
            let n = params.cost_parameter;
            let r = u64::from(params.block_size);
            let p = u64::from(params.parallelization);
            // Match the pinned sequential scrypt implementation's b, v and t buffers.
            let memory = n
                .checked_add(p)
                .and_then(|sum| sum.checked_add(1))
                .and_then(|sum| sum.checked_mul(r))
                .and_then(|sum| sum.checked_mul(128));
            let work = n.checked_mul(r).and_then(|nr| nr.checked_mul(p));
            n > 0
                && n.is_power_of_two()
                && r > 0
                && p > 0
                && memory.is_some_and(|bytes| bytes <= 32 * 1024 * 1024)
                && work.is_some_and(|work| work <= 1_048_576)
        }
        _ => false,
    };
    if allowed { Ok(()) } else { Err(Error::KeyResourceLimit) }
}

fn validate_ppk_resources(secret: &str) -> Result<(), Error> {
    let mut lines = secret.lines();
    let version = lines
        .next()
        .and_then(|header| header.strip_prefix("PuTTY-User-Key-File-"))
        .and_then(|header| header.split_once(": "))
        .and_then(|(version, _)| version.parse::<u32>().ok())
        .ok_or(Error::CouldNotReadKey)?;
    let names = ["Encryption", "Key-Derivation", "Argon2-Memory", "Argon2-Passes", "Argon2-Parallelism"];
    let mut values = [None; 5];
    while let Some(line) = lines.next() {
        if let Some((name, value)) = line.split_once(": ") {
            // Match the decoder's counted data blocks before reading more headers.
            if name.ends_with("-Lines") {
                let count = value.parse::<usize>().map_err(|_| Error::CouldNotReadKey)?;
                if lines.by_ref().take(count).count() != count {
                    return Err(Error::CouldNotReadKey);
                }
                continue;
            }
            if let Some(index) = names.iter().position(|field| *field == name) {
                if values[index].replace(value).is_some() {
                    return Err(Error::KeyResourceLimit);
                }
            }
        }
    }
    let mut costs = [None; 3];
    for (index, value) in values[2..].iter().enumerate() {
        if let Some(value) = value {
            let cost = value.parse::<u32>().map_err(|_| Error::KeyResourceLimit)?;
            if cost == 0 {
                return Err(Error::KeyResourceLimit);
            }
            costs[index] = Some(cost);
        }
    }
    if version == 3 && values[0] == Some("aes256-cbc") {
        if !matches!(values[1], Some("Argon2i" | "Argon2d" | "Argon2id")) {
            return Err(Error::CouldNotReadKey);
        }
        let memory = costs[0].ok_or(Error::KeyResourceLimit)?;
        let passes = costs[1].ok_or(Error::KeyResourceLimit)?;
        let lanes = costs[2].ok_or(Error::KeyResourceLimit)?;
        if memory > 32_768 || passes > 64 || lanes > 8 || memory.checked_mul(passes).is_none_or(|work| work > 524_288) {
            return Err(Error::KeyResourceLimit);
        }
    }
    Ok(())
}
