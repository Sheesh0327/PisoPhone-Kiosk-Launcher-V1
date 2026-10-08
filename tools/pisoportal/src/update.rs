//! Router updates from the website: only what the owner signed is ever installed.
//!
//! The feed is two files next to the box firmware's (website/update/): router.json, a small signed manifest, and
//! router-setup.sh, the one-file setup the router already installs from. The owner signs, on their own computer
//! (scripts/sign_router.py), the text
//!     pisophone-router-v1|<version>|<sha256 of router-setup.sh>|<size>|<rollout percent>
//! with the same ECDSA P-256 key that signs the box's firmware. This program holds the PUBLIC key (owner_key.b64,
//! built in), so a stolen website account or a man in the middle can offer nothing a router will install. The program is
//! fail-closed: with no key built in, every update is refused.
//!
//!   pisoportal update-check <router.json> <installed version> <router id>
//!       AVAILABLE <version> | CURRENT | WAIT <version> (this router's turn has not come in the staged rollout) | REFUSED <why>
//!   pisoportal update-verify <router.json> <router-setup.sh>
//!       OK <version> | REFUSED <why>   (signature, size and sha256 of the downloaded file)
use crate::util::{b64_decode, jget, sha256_hex};
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;

/// The largest setup file a router will take (the real one is about 1 MB, mostly the portal program).
pub const MAX_SIZE: u64 = 4 * 1024 * 1024;

#[derive(Debug, PartialEq)]
pub struct Manifest {
    pub version: String,
    pub sha256: String,
    pub size: u64,
    pub rollout: u64,
    pub sig: String,
}

#[derive(Debug, PartialEq)]
pub enum Verdict {
    Available(String),
    Current,
    Wait(String),
}

/// The owner's public key (base64 of the DER SubjectPublicKeyInfo). Empty until the owner builds theirs in.
pub fn owner_key() -> String {
    #[cfg(feature = "test-owner-key")]
    if let Ok(p) = std::env::var("PISOPORTAL_TEST_OWNER_KEY") {
        return std::fs::read_to_string(p).unwrap_or_default().trim().to_string();
    }
    include_str!("../owner_key.b64").trim().to_string()
}

pub fn parse(manifest: &str) -> Result<Manifest, String> {
    let m = Manifest {
        version: jget(manifest, "version"),
        sha256: jget(manifest, "sha256"),
        size: jget(manifest, "size").parse().map_err(|_| "the manifest has no size")?,
        rollout: jget(manifest, "rollout").parse().map_err(|_| "the manifest has no rollout percentage")?,
        sig: jget(manifest, "sig"),
    };
    if version_tuple(&m.version).is_none() {
        return Err("the manifest's version is not like 1.2.3".into());
    }
    if m.sha256.len() != 64 || !m.sha256.bytes().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()) {
        return Err("the manifest's sha256 is not 64 lowercase hex digits".into());
    }
    if m.size == 0 || m.size > MAX_SIZE {
        return Err("the manifest's size is out of range".into());
    }
    if m.rollout > 100 {
        return Err("the manifest's rollout is not a percentage".into());
    }
    Ok(m)
}

pub fn version_tuple(v: &str) -> Option<(u32, u32, u32)> {
    let mut p = v.split('.');
    let n =
        |s: Option<&str>| s.filter(|s| !s.is_empty() && s.len() <= 5 && s.bytes().all(|c| c.is_ascii_digit())).and_then(|s| s.parse().ok());
    let t = (n(p.next())?, n(p.next())?, n(p.next())?);
    p.next().is_none().then_some(t)
}

pub fn canonical(m: &Manifest) -> String {
    format!("pisophone-router-v1|{}|{}|{}|{}", m.version, m.sha256, m.size, m.rollout)
}

pub fn check_signature(key_b64: &str, m: &Manifest) -> Result<(), String> {
    if key_b64.is_empty() {
        return Err("no owner key is built into this router program, so no update can be verified".into());
    }
    let der = b64_decode(key_b64).ok_or("the built-in owner key is damaged")?;
    let key = VerifyingKey::from_public_key_der(&der).map_err(|_| "the built-in owner key is damaged")?;
    let sig = b64_decode(&m.sig).and_then(|s| Signature::from_der(&s).ok()).ok_or("the manifest's signature is not readable")?;
    key.verify(canonical(m).as_bytes(), &sig).map_err(|_| "the signature is not the owner's".to_string())
}

/// 0..99, stable for one router: its place in a staged rollout. (Only a hash of the router's id is used.)
pub fn bucket(router_id: &str) -> u64 {
    let h = sha256_hex(format!("pisophone-rollout|{}", router_id).as_bytes());
    u64::from_str_radix(&h[..8], 16).unwrap_or(0) % 100
}

/// Is there a verified update this router should take now? Never a downgrade, never the same version.
pub fn evaluate(key_b64: &str, manifest: &str, installed: &str, router_id: &str) -> Result<Verdict, String> {
    let m = parse(manifest)?;
    check_signature(key_b64, &m)?;
    let have = version_tuple(installed).ok_or("the installed version is not like 1.2.3")?;
    if version_tuple(&m.version) <= Some(have) {
        return Ok(Verdict::Current);
    }
    Ok(if bucket(router_id) < m.rollout { Verdict::Available(m.version) } else { Verdict::Wait(m.version) })
}

/// The downloaded file is exactly what was signed.
pub fn verify_file(key_b64: &str, manifest: &str, file: &[u8]) -> Result<String, String> {
    let m = parse(manifest)?;
    check_signature(key_b64, &m)?;
    if file.len() as u64 != m.size {
        return Err("the downloaded file is not the signed size".into());
    }
    if sha256_hex(file) != m.sha256 {
        return Err("the downloaded file is not the signed file (checksum differs)".into());
    }
    Ok(m.version)
}

/// Exit codes: 0 available / ok, 10 current, 11 not this router's turn yet, 1 refused.
pub fn cmd_check(args: &[String]) -> i32 {
    let (Some(path), Some(installed), Some(id)) = (args.first(), args.get(1), args.get(2)) else {
        eprintln!("usage: pisoportal update-check <router.json> <installed version> <router id>");
        return 2;
    };
    let text = match std::fs::read_to_string(path) {
        Ok(t) => t,
        Err(e) => {
            println!("REFUSED cannot read {}: {}", path, e);
            return 1;
        }
    };
    match evaluate(&owner_key(), &text, installed, id) {
        Ok(Verdict::Available(v)) => {
            println!("AVAILABLE {}", v);
            0
        }
        Ok(Verdict::Current) => {
            println!("CURRENT");
            10
        }
        Ok(Verdict::Wait(v)) => {
            println!("WAIT {}", v);
            11
        }
        Err(e) => {
            println!("REFUSED {}", e);
            1
        }
    }
}

pub fn cmd_verify(args: &[String]) -> i32 {
    let (Some(mpath), Some(fpath)) = (args.first(), args.get(1)) else {
        eprintln!("usage: pisoportal update-verify <router.json> <router-setup.sh>");
        return 2;
    };
    let (Ok(text), Ok(file)) = (std::fs::read_to_string(mpath), std::fs::read(fpath)) else {
        println!("REFUSED cannot read the manifest or the file");
        return 1;
    };
    match verify_file(&owner_key(), &text, &file) {
        Ok(v) => {
            println!("OK {}", v);
            0
        }
        Err(e) => {
            println!("REFUSED {}", e);
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Made once with scripts/sign_router.py's algorithm (Python's cryptography) by a throwaway key that was then discarded:
    // the Rust verifier accepts what the owner's tool signs.
    const KEY: &str =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9+IgfGrGqT7HoG2NOLdVoB0ZK+WXxSEWPM96m0CEkkPCdZgHW4AbFQL9xbEc2vrzPZj5COAgD9kkm+YHLAmxxQ==";
    const SIG: &str = "MEYCIQDHnAv5hcUnIYBdRbXbQj4vuspyLZDrGbE2mJqQUuRlswIhAIku+kww7U4wNkkkaRl1/vc6QrJdnD9EfLRL9gm/LFEo";
    const SIG_ALL: &str = "MEYCIQDmgMBrnm2YK4AUZvLlVHBe+9J7k8aoatfr6KoROT8KsgIhANqGjXxGSMH+5FwAXnyWSmUov8mIByEkMTEt8MR1esfF";
    const FILE: &[u8] = b"router setup file";

    fn manifest(version: &str, rollout: u64, sig: &str) -> String {
        format!(
            r#"{{"version":"{}","sha256":"9fd12846ae5f382578f8a59710de4738a420621b412632bde9606daff9d39861","size":17,"rollout":{},"changelog":"x","sig":"{}"}}"#,
            version, rollout, sig
        )
    }

    #[test]
    fn the_owners_signature_is_accepted_and_nothing_else() {
        let good = manifest("1.2.0", 25, SIG);
        assert_eq!(verify_file(KEY, &good, FILE), Ok("1.2.0".to_string()));
        // every signed field is protected: version, rollout, size and the file itself
        assert!(verify_file(KEY, &manifest("1.2.1", 25, SIG), FILE).is_err());
        assert!(verify_file(KEY, &manifest("1.2.0", 100, SIG), FILE).is_err()); // the 25% signature does not make it 100%
        assert!(verify_file(KEY, &good.replace("\"size\":17", "\"size\":18"), FILE).is_err());
        assert!(verify_file(KEY, &good, b"router setup filE").is_err());
        assert!(verify_file(KEY, &good, b"router setup file!").is_err());
        // a damaged or missing signature, a damaged or missing key
        assert!(verify_file(KEY, &manifest("1.2.0", 25, "AAAA"), FILE).is_err());
        assert!(verify_file(KEY, &manifest("1.2.0", 25, ""), FILE).is_err());
        assert!(verify_file("", &good, FILE).unwrap_err().contains("no owner key"));
        assert!(verify_file("AAAA", &good, FILE).is_err());
    }

    #[test]
    fn the_built_in_key_is_the_owners_until_a_real_one_replaces_the_placeholder() {
        // the repository ships without a key: updates stay off (fail closed) until the owner commits owner_key.b64
        let k = owner_key();
        assert!(k.is_empty() || b64_decode(&k).is_some_and(|d| VerifyingKey::from_public_key_der(&d).is_ok()));
    }

    #[test]
    fn only_a_newer_version_is_offered_and_never_a_downgrade() {
        let m = manifest("1.2.0", 100, SIG_ALL);
        assert_eq!(evaluate(KEY, &m, "1.1.9", "r"), Ok(Verdict::Available("1.2.0".into())));
        assert_eq!(evaluate(KEY, &m, "1.2.0", "r"), Ok(Verdict::Current));
        assert_eq!(evaluate(KEY, &m, "1.10.0", "r"), Ok(Verdict::Current));
        assert_eq!(evaluate(KEY, &m, "2.0.0", "r"), Ok(Verdict::Current));
        assert!(evaluate(KEY, &m, "dev", "r").is_err());
        // an unsigned or forged manifest is refused before its version matters
        assert!(evaluate(KEY, &manifest("9.9.9", 100, SIG), "1.0.0", "r").is_err());
    }

    #[test]
    fn a_staged_rollout_reaches_each_router_in_its_turn() {
        assert_eq!(bucket("router-a"), bucket("router-a"));
        let buckets: Vec<u64> = (0..400).map(|i| bucket(&format!("router-{}", i))).collect();
        assert!(buckets.iter().all(|b| *b < 100));
        // roughly even: a 25% rollout reaches about a quarter of the routers
        let n = buckets.iter().filter(|b| **b < 25).count();
        assert!((60..140).contains(&n), "{} of 400", n);
        let m = manifest("1.2.0", 25, SIG);
        let (mut go, mut wait) = (0, 0);
        for i in 0..200 {
            match evaluate(KEY, &m, "1.0.0", &format!("router-{}", i)) {
                Ok(Verdict::Available(_)) => go += 1,
                Ok(Verdict::Wait(_)) => wait += 1,
                other => panic!("{:?}", other),
            }
        }
        assert!(go > 20 && wait > 100, "{} {}", go, wait);
    }

    #[test]
    fn manifests_are_read_strictly() {
        assert_eq!(version_tuple("1.2.3"), Some((1, 2, 3)));
        assert_eq!(version_tuple("1.2"), None);
        assert_eq!(version_tuple("1.2.3.4"), None);
        assert_eq!(version_tuple("1..3"), None);
        assert_eq!(version_tuple("a.b.c"), None);
        assert_eq!(version_tuple("-1.2.3"), None);
        assert_eq!(version_tuple("100000.0.0"), None);
        assert!(parse("{}").is_err());
        assert!(parse(&manifest("1.2.0", 101, SIG)).is_err());
        assert!(parse(&manifest("1.2.0", 25, SIG).replace("9fd1", "9FD1")).is_err());
        assert!(parse(&manifest("1.2.0", 25, SIG).replace("\"size\":17", "\"size\":99999999")).is_err());
        assert!(parse("not json at all").is_err());
        assert!(parse(&manifest("1.2.0", 25, SIG)).is_ok());
    }
}
