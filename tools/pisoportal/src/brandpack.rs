//! The signed brand pack (feature 049, group 2): the only way a picture reaches a router's guest page.
//!
//! A shop owner may change texts and colours in the console, but a logo or a picture comes only from a pack the OWNER signed, on the
//! owner's own computer (scripts/make_brand_pack.py), with the same ECDSA P-256 key that signs firmware and router updates. This
//! program holds only the public key (owner_key.b64), so a shop owner cannot make a pack, with or without SSH, and a stolen website
//! account or a copied pack file can offer nothing but what the owner already signed. Fail-closed: with no key built in, no pack installs.
//!
//! The pack is a text file of exactly six lines (a Windows line ending is fine):
//!     pisophone-brand-v1
//!     logo <size in bytes> <sha256 of the logo>
//!     picture <size> <sha256>          or   picture 0 -   (no picture)
//!     sig <base64 DER signature>
//!     <the logo, base64, one line>
//!     <the picture, base64, one line, empty when there is none>
//! The signed text is   pisophone-brand-v1|<logo sha256>|<logo size>|<picture sha256 or ->|<picture size>   (a router-update or firmware
//! signature can never pass for it: the text starts differently). The pictures are data only: PNG or JPEG (never SVG, which can carry
//! scripts), at most 64 KB and 512 by 512 pixels each, checked by their own bytes, not by any name.
//!
//!   pisoportal brand-verify <pack>    OK ... | REFUSED <why>      (changes nothing)
//!   pisoportal brand-install <pack>   INSTALLED ... | REFUSED <why>
//!
//! Installed files live in DATA_DIR/brand/: `logo-<12 hex>.png|jpg`, `picture-<12 hex>.png|jpg` and `images.txt` (which names them and
//! their sha256; written last, so an install is all or nothing). A file whose bytes no longer match its sha256 is never served.
use crate::brand::BRAND_DIR;
use crate::util::{b64_decode, sha256_hex, write_atomic};
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;
use std::fs;
use std::io::Read;

const MAGIC: &str = "pisophone-brand-v1";
pub const MAX_FILE: usize = 300 * 1024;
pub const MAX_IMAGE: usize = 64 * 1024;
pub const MAX_TOTAL: usize = 192 * 1024;
pub const MAX_SIDE: u32 = 512;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Png,
    Jpeg,
}

impl Kind {
    pub fn ext(self) -> &'static str {
        match self {
            Kind::Png => "png",
            Kind::Jpeg => "jpg",
        }
    }

    pub fn mime(self) -> &'static str {
        match self {
            Kind::Png => "image/png",
            Kind::Jpeg => "image/jpeg",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Image {
    pub kind: Kind,
    pub bytes: Vec<u8>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Pack {
    pub logo: Image,
    pub picture: Option<Image>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Slot {
    Logo,
    Picture,
}

impl Slot {
    fn name(self) -> &'static str {
        match self {
            Slot::Logo => "logo",
            Slot::Picture => "picture",
        }
    }
}

/// An installed image: its file name in DATA_DIR/brand/ and the sha256 it must still have.
#[derive(Clone, Debug, PartialEq)]
pub struct Stored {
    pub file: String,
    pub sha256: String,
}

#[derive(Debug, PartialEq)]
pub struct Summary {
    pub logo_bytes: usize,
    pub picture_bytes: usize,
}

// ---- the pictures ----------------------------------------------------------------------------------------------------------------

fn be32(b: &[u8], at: usize) -> u32 {
    u32::from_be_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]])
}

fn be16(b: &[u8], at: usize) -> u32 {
    u16::from_be_bytes([b[at], b[at + 1]]) as u32
}

fn sides_ok(w: u32, h: u32) -> Result<(), String> {
    if w == 0 || h == 0 || w > MAX_SIDE || h > MAX_SIDE {
        return Err(format!("a picture may be at most {0} by {0} pixels (this one says {1} by {2})", MAX_SIDE, w, h));
    }
    Ok(())
}

/// A PNG or a JPEG, whole and of a sensible size; anything else (SVG, GIF, HTML with a PNG's first bytes) is refused. Returns the kind and
/// its size in pixels. It does not decode the pixels: the owner signed the file, this only makes sure it is the kind of data it claims.
pub fn check_image(b: &[u8]) -> Result<(Kind, u32, u32), String> {
    if b.len() > MAX_IMAGE {
        return Err("a picture may be at most 64 KB".into());
    }
    if b.starts_with(b"\x89PNG\r\n\x1a\n") {
        // the chunks must follow each other from IHDR (first) to IEND (last, ending exactly at the end of the file)
        let mut at = 8usize;
        let (mut w, mut h) = (0, 0);
        let mut first = true;
        loop {
            if at + 12 > b.len() {
                return Err("the PNG is cut short".into());
            }
            let (len, kind) = (be32(b, at) as usize, &b[at + 4..at + 8]);
            if first {
                if kind != b"IHDR" || len != 13 || at + 12 + len > b.len() {
                    return Err("the PNG does not start with its header".into());
                }
                (w, h) = (be32(b, at + 8), be32(b, at + 12));
                first = false;
            }
            let next = at.saturating_add(12).saturating_add(len);
            if kind == b"IEND" {
                if len != 0 || next != b.len() {
                    return Err("the PNG has something after its end".into());
                }
                break;
            }
            if next > b.len() {
                return Err("the PNG is cut short".into());
            }
            at = next;
        }
        sides_ok(w, h)?;
        return Ok((Kind::Png, w, h));
    }
    if b.starts_with(&[0xff, 0xd8, 0xff]) {
        if !b.ends_with(&[0xff, 0xd9]) {
            return Err("the JPEG is cut short".into());
        }
        let mut at = 2usize;
        loop {
            if at + 4 > b.len() || b[at] != 0xff {
                return Err("the JPEG is damaged".into());
            }
            while at + 1 < b.len() && b[at + 1] == 0xff {
                at += 1; // fill bytes
            }
            let m = b[at + 1];
            match m {
                0xd9 => return Err("the JPEG has no frame header".into()),
                0x01 | 0xd0..=0xd8 => at += 2,
                0xc0..=0xc3 | 0xc5..=0xc7 | 0xc9..=0xcb | 0xcd..=0xcf => {
                    if at + 9 > b.len() {
                        return Err("the JPEG is cut short".into());
                    }
                    let (h, w) = (be16(b, at + 5), be16(b, at + 7));
                    sides_ok(w, h)?;
                    return Ok((Kind::Jpeg, w, h));
                }
                _ => {
                    let len = be16(b, at + 2) as usize;
                    if len < 2 {
                        return Err("the JPEG is damaged".into());
                    }
                    at += 2 + len;
                }
            }
        }
    }
    Err("only PNG or JPEG pictures are accepted".into())
}

// ---- the pack --------------------------------------------------------------------------------------------------------------------

fn bad(why: &str) -> String {
    format!("not a brand pack: {}", why)
}

fn is_sha(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}

/// "<word> <size> <sha256>" (or, for no picture, "picture 0 -").
fn size_and_sha<'a>(line: &'a str, word: &str) -> Result<(usize, &'a str), String> {
    let mut p = line.split(' ');
    let (Some(w), Some(size), Some(sha), None) = (p.next(), p.next(), p.next(), p.next()) else {
        return Err(bad(&format!("the {} line is not \"{} <size> <sha256>\"", word, word)));
    };
    let size = (w == word && !size.is_empty() && size.len() <= 7 && size.bytes().all(|c| c.is_ascii_digit()))
        .then(|| size.parse::<usize>().ok())
        .flatten()
        .ok_or_else(|| bad(&format!("the {} line is not \"{} <size> <sha256>\"", word, word)))?;
    Ok((size, sha))
}

fn check_signature(key_b64: &str, canonical: &str, sig_b64: &str) -> Result<(), String> {
    if key_b64.is_empty() {
        return Err("no owner key is built into this router program, so no brand pack can be verified".into());
    }
    let der = b64_decode(key_b64).ok_or("the built-in owner key is damaged")?;
    let key = VerifyingKey::from_public_key_der(&der).map_err(|_| "the built-in owner key is damaged")?;
    let sig = b64_decode(sig_b64).and_then(|s| Signature::from_der(&s).ok()).ok_or("the pack's signature is not readable")?;
    key.verify(canonical.as_bytes(), &sig).map_err(|_| "the signature is not the owner's".to_string())
}

/// Reads and checks a pack: its form, the owner's signature over its checksums, that the pictures are those checksums, and that each
/// picture is a whole, small PNG or JPEG. Nothing is written.
pub fn verify(key_b64: &str, pack: &[u8]) -> Result<Pack, String> {
    if pack.len() > MAX_FILE {
        return Err("the pack file is too large (the limit is 300 KB)".into());
    }
    let text = std::str::from_utf8(pack).map_err(|_| bad("it is not text"))?.replace("\r\n", "\n");
    let mut lines: Vec<&str> = text.split('\n').collect();
    if lines.len() == 7 && lines[6].is_empty() {
        lines.pop();
    }
    if lines.len() != 6 || lines[0] != MAGIC {
        return Err(bad("it does not have the six lines of a pack"));
    }
    let (logo_size, logo_sha) = size_and_sha(lines[1], "logo")?;
    let (pic_size, pic_sha) = size_and_sha(lines[2], "picture")?;
    if logo_size == 0 {
        return Err("the pack has no logo".into());
    }
    if !is_sha(logo_sha) || (pic_size == 0 && pic_sha != "-") || (pic_size > 0 && !is_sha(pic_sha)) {
        return Err(bad("a checksum is not 64 hex digits"));
    }
    if logo_size > MAX_IMAGE || pic_size > MAX_IMAGE {
        return Err("a picture may be at most 64 KB".into());
    }
    if logo_size + pic_size > MAX_TOTAL {
        return Err("a pack may carry at most 192 KB of pictures".into());
    }
    let sig = lines[3].strip_prefix("sig ").ok_or_else(|| bad("the sig line is missing"))?;
    check_signature(key_b64, &format!("{}|{}|{}|{}|{}", MAGIC, logo_sha, logo_size, pic_sha, pic_size), sig)?;
    // signed: now the pictures themselves
    let logo = b64_decode(lines[4]).ok_or_else(|| bad("the logo is not base64"))?;
    let picture = if pic_size == 0 {
        if !lines[5].is_empty() {
            return Err(bad("there is picture data but no picture was signed"));
        }
        None
    } else {
        Some(b64_decode(lines[5]).ok_or_else(|| bad("the picture is not base64"))?)
    };
    for (what, data, size, sha) in [("logo", Some(&logo), logo_size, logo_sha), ("picture", picture.as_ref(), pic_size, pic_sha)] {
        if let Some(d) = data {
            if d.len() != size || sha256_hex(d) != sha {
                return Err(format!("the {} is not the one that was signed (its size or checksum differs)", what));
            }
        }
    }
    let (kind, ..) = check_image(&logo).map_err(|e| format!("the logo: {}", e))?;
    let picture = match picture {
        Some(p) => {
            let (k, ..) = check_image(&p).map_err(|e| format!("the picture: {}", e))?;
            Some(Image { kind: k, bytes: p })
        }
        None => None,
    };
    Ok(Pack { logo: Image { kind, bytes: logo }, picture })
}

// ---- installed images --------------------------------------------------------------------------------------------------------------

fn brand_dir(dir: &str) -> String {
    format!("{}/{}", dir, BRAND_DIR)
}

/// A file name this program made itself: logo-<12 hex>.png|jpg or picture-<12 hex>.png|jpg. Nothing else is ever opened from images.txt.
fn own_name(slot: Slot, name: &str) -> bool {
    let Some(rest) = name.strip_prefix(slot.name()).and_then(|r| r.strip_prefix('-')) else { return false };
    let Some((h, ext)) = rest.split_once('.') else { return false };
    h.len() == 12 && h.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)) && (ext == "png" || ext == "jpg")
}

/// What `images.txt` names: the logo and the picture, each with its sha256. Lines that are not exactly that are ignored.
pub fn installed(dir: &str) -> (Option<Stored>, Option<Stored>) {
    let text = fs::read_to_string(format!("{}/images.txt", brand_dir(dir))).unwrap_or_default();
    let find = |slot: Slot| {
        text.lines().find_map(|l| {
            let p: Vec<&str> = l.split(' ').collect();
            (p.len() == 3 && p[0] == slot.name() && own_name(slot, p[1]) && is_sha(p[2]))
                .then(|| Stored { file: p[1].to_string(), sha256: p[2].to_string() })
        })
    };
    (find(Slot::Logo), find(Slot::Picture))
}

/// The installed picture, only if its bytes are still exactly what was installed and still pass the picture check.
pub fn read_image(dir: &str, slot: Slot) -> Option<(Kind, Vec<u8>)> {
    let (logo, picture) = installed(dir);
    let stored = if slot == Slot::Logo { logo } else { picture }?;
    let bytes = fs::read(format!("{}/{}", brand_dir(dir), stored.file)).ok()?;
    if sha256_hex(&bytes) != stored.sha256 {
        return None;
    }
    let (kind, ..) = check_image(&bytes).ok()?;
    Some((kind, bytes))
}

/// Verifies the pack, then keeps its pictures: the files first, `images.txt` last (the moment the new pictures count), then the files of
/// the earlier pack are removed. A refused pack changes nothing, not even by creating the folder.
pub fn install(dir: &str, key_b64: &str, pack: &[u8]) -> Result<Summary, String> {
    let p = verify(key_b64, pack)?;
    let base = brand_dir(dir);
    let mut keep = Vec::new();
    let mut index = String::new();
    for (slot, img) in [(Slot::Logo, Some(&p.logo)), (Slot::Picture, p.picture.as_ref())] {
        let Some(img) = img else { continue };
        let sha = sha256_hex(&img.bytes);
        let name = format!("{}-{}.{}", slot.name(), &sha[..12], img.kind.ext());
        write_atomic(&format!("{}/{}", base, name), &img.bytes).map_err(|e| format!("could not save the {}: {}", slot.name(), e))?;
        index.push_str(&format!("{} {} {}\n", slot.name(), name, sha));
        keep.push(name);
    }
    write_atomic(&format!("{}/images.txt", base), index.as_bytes()).map_err(|e| format!("could not save the pictures' list: {}", e))?;
    if let Ok(rd) = fs::read_dir(&base) {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().into_owned();
            if (own_name(Slot::Logo, &n) || own_name(Slot::Picture, &n)) && !keep.contains(&n) {
                let _ = fs::remove_file(e.path());
            }
        }
    }
    Ok(Summary { logo_bytes: p.logo.bytes.len(), picture_bytes: p.picture.map(|i| i.bytes.len()).unwrap_or(0) })
}

// ---- the commands ----------------------------------------------------------------------------------------------------------------

fn read_pack(path: &str) -> Result<Vec<u8>, String> {
    let f = fs::File::open(path).map_err(|e| format!("cannot read {}: {}", path, e))?;
    let mut v = Vec::new();
    f.take(MAX_FILE as u64 + 1).read_to_end(&mut v).map_err(|e| format!("cannot read {}: {}", path, e))?;
    Ok(v)
}

fn describe(p: &Pack) -> String {
    let side = |i: &Image| check_image(&i.bytes).map(|(_, w, h)| format!("{} bytes, {} by {}", i.bytes.len(), w, h)).unwrap_or_default();
    match &p.picture {
        Some(pic) => format!("logo {} ({}), picture {} ({})", side(&p.logo), p.logo.kind.ext(), side(pic), pic.kind.ext()),
        None => format!("logo {} ({}), no picture", side(&p.logo), p.logo.kind.ext()),
    }
}

/// `pisoportal brand-verify <pack>`: exit 0 and "OK ..." for a good pack, 1 and "REFUSED <why>" otherwise. Changes nothing.
pub fn cmd_verify(args: &[String]) -> i32 {
    let Some(path) = args.first() else {
        eprintln!("usage: pisoportal brand-verify <pack>");
        return 2;
    };
    match read_pack(path).and_then(|b| verify(&crate::update::owner_key(), &b)) {
        Ok(p) => {
            println!("OK {}", describe(&p));
            0
        }
        Err(e) => {
            println!("REFUSED {}", e);
            1
        }
    }
}

/// `pisoportal brand-install <pack>`: verifies, then keeps the pictures in DATA_DIR/brand/.
pub fn cmd_install(args: &[String], dir: &str) -> i32 {
    let Some(path) = args.first() else {
        eprintln!("usage: pisoportal brand-install <pack>");
        return 2;
    };
    match read_pack(path).and_then(|b| install(dir, &crate::update::owner_key(), &b)) {
        Ok(s) => {
            println!("INSTALLED logo {} bytes, picture {} bytes", s.logo_bytes, s.picture_bytes);
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
    use crate::util::{b64_encode, sha256_hex};
    use p256::ecdsa::signature::Signer;
    use p256::ecdsa::SigningKey;

    // A throwaway key made for these tests (it signs nothing real): the private scalar, and the public key as the router program holds it.
    const SCALAR: &str = "e57c7e10b9e5d42cb2e49226d5b4ea70226c2c14bb6a49461972b042422d31a9";
    const PUB: &str =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEREKxfm+PDwNnt176PdtXU4cR713d/7jmn+P04dSKeOpshBroFxTsH8/ElkC4TZ4p2HLjsWovbeLsMmRauttW0g==";
    // Made by scripts/make_brand_pack.py's algorithm (Python's cryptography) with the same key: the Rust check accepts what the owner's tool signs.
    const PYTHON_PACK: &str = "pisophone-brand-v1\nlogo 69 b1ff9c8ea3a780bad09b346c423d2d0e46815926879b18e841d928376a946640\npicture 0 -\nsig MEYCIQDqrxBtPDd6qqiVq4PScnSx4SRK2rjSZscbeLU+dwxb3wIhAKP9/eAU7/vYK0gkQjtCAF9uHqDp5h6vQAujRjaDW88M\niVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC\n\n";
    // The key of another owner (also throwaway).
    const OTHER_PUB: &str =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9+IgfGrGqT7HoG2NOLdVoB0ZK+WXxSEWPM96m0CEkkPCdZgHW4AbFQL9xbEc2vrzPZj5COAgD9kkm+YHLAmxxQ==";

    fn tmp(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("brandpack-test-{}-{}", std::process::id(), name));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d.to_string_lossy().into_owned()
    }

    fn signing_key() -> SigningKey {
        let bytes: Vec<u8> = (0..32).map(|i| u8::from_str_radix(&SCALAR[i * 2..i * 2 + 2], 16).unwrap()).collect();
        SigningKey::from_slice(&bytes).unwrap()
    }

    fn chunk(kind: &[u8; 4], data: &[u8]) -> Vec<u8> {
        let mut c = (data.len() as u32).to_be_bytes().to_vec();
        c.extend_from_slice(kind);
        c.extend_from_slice(data);
        c.extend_from_slice(&[0, 0, 0, 0]); // (the check does not decode pixels, so the CRC is not needed)
        c
    }

    /// A structurally valid PNG of this size in pixels, padded with a text chunk to at least `total` bytes (0 = no padding).
    fn png(w: u32, h: u32, total: usize) -> Vec<u8> {
        let mut ihdr = w.to_be_bytes().to_vec();
        ihdr.extend_from_slice(&h.to_be_bytes());
        ihdr.extend_from_slice(&[8, 2, 0, 0, 0]);
        let mut p = b"\x89PNG\r\n\x1a\n".to_vec();
        p.extend(chunk(b"IHDR", &ihdr));
        let end = chunk(b"IEND", &[]);
        if total > p.len() + end.len() + 12 {
            p.extend(chunk(b"tEXt", &vec![b'x'; total - p.len() - end.len() - 12]));
        }
        p.extend(end);
        p
    }

    fn jpeg(w: u16, h: u16) -> Vec<u8> {
        let mut j = vec![0xff, 0xd8, 0xff, 0xe0, 0x00, 0x04, 0x4a, 0x46];
        j.extend_from_slice(&[0xff, 0xc0, 0x00, 0x0b, 0x08]);
        j.extend_from_slice(&h.to_be_bytes());
        j.extend_from_slice(&w.to_be_bytes());
        j.extend_from_slice(&[0x01, 0x01, 0x11, 0x00, 0xff, 0xd9]);
        j
    }

    /// A pack text; `sign` overrides the signed text (to make a forged or foreign signature).
    fn pack(logo: &[u8], picture: Option<&[u8]>, sign: Option<String>) -> String {
        let (psha, plen) = picture.map(|p| (sha256_hex(p), p.len())).unwrap_or(("-".into(), 0));
        let canon = sign.unwrap_or(format!("pisophone-brand-v1|{}|{}|{}|{}", sha256_hex(logo), logo.len(), psha, plen));
        let sig: p256::ecdsa::Signature = signing_key().sign(canon.as_bytes());
        format!(
            "pisophone-brand-v1\nlogo {} {}\npicture {} {}\nsig {}\n{}\n{}\n",
            logo.len(),
            sha256_hex(logo),
            plen,
            psha,
            b64_encode(sig.to_der().as_bytes()),
            b64_encode(logo),
            picture.map(b64_encode).unwrap_or_default()
        )
    }

    fn refused(r: Result<Pack, String>, word: &str) {
        let e = r.err().unwrap_or_else(|| panic!("a pack that should be refused ({}) was accepted", word));
        assert!(e.contains(word), "the reason \"{}\" does not mention \"{}\"", e, word);
    }

    #[test]
    fn f049_a_pack_signed_by_the_owner_is_accepted_with_or_without_a_picture() {
        let logo = png(64, 64, 0);
        let p = verify(PUB, pack(&logo, None, None).as_bytes()).unwrap();
        assert_eq!((p.logo.kind, p.logo.bytes.clone()), (Kind::Png, logo.clone()));
        assert!(p.picture.is_none());
        let pic = jpeg(300, 200);
        let p = verify(PUB, pack(&logo, Some(&pic), None).as_bytes()).unwrap();
        assert_eq!(p.picture.unwrap().kind, Kind::Jpeg);
    }

    #[test]
    fn f049_a_pack_signed_by_pythons_cryptography_is_accepted_too() {
        let p = verify(PUB, PYTHON_PACK.as_bytes()).unwrap();
        assert_eq!(p.logo.bytes.len(), 69);
        assert_eq!(check_image(&p.logo.bytes).unwrap(), (Kind::Png, 1, 1));
    }

    #[test]
    fn f049_a_changed_byte_a_wrong_key_or_a_missing_key_is_refused() {
        let logo = png(64, 64, 0);
        let good = pack(&logo, None, None);
        refused(verify(OTHER_PUB, good.as_bytes()), "not the owner's");
        refused(verify("", good.as_bytes()), "no owner key");
        refused(verify("AAAA", good.as_bytes()), "damaged");
        // the picture data changed after signing
        let mut data = good.clone().into_bytes();
        let at = good.find("\n\n").map(|i| i.saturating_sub(10)).unwrap_or(good.len() - 20);
        data[at] = if data[at] == b'A' { b'B' } else { b'A' };
        assert!(verify(PUB, &data).is_err(), "a changed byte in the image data");
        // a header field changed after signing (size and checksum both consistent with a different logo)
        let other = png(65, 64, 0);
        let swapped = good.replacen(&b64_encode(&logo), &b64_encode(&other), 1);
        assert!(verify(PUB, swapped.as_bytes()).is_err(), "another image under the old signature");
        let resized = good.replacen(&format!("logo {} ", logo.len()), &format!("logo {} ", logo.len() + 1), 1);
        assert!(verify(PUB, resized.as_bytes()).is_err());
        // a picture added after signing
        let added = good.replacen("picture 0 -", &format!("picture {} {}", jpeg(1, 1).len(), sha256_hex(&jpeg(1, 1))), 1).replacen(
            "\n\n",
            &format!("\n{}\n", b64_encode(&jpeg(1, 1))),
            1,
        );
        assert!(verify(PUB, added.as_bytes()).is_err());
        refused(verify(PUB, good.replacen("sig ", "sig X", 1).as_bytes()), "signature");
    }

    #[test]
    fn f049_a_router_update_signature_is_not_a_brand_signature() {
        let logo = png(64, 64, 0);
        let foreign = format!("pisophone-router-v1|{}|{}|{}|{}", sha256_hex(&logo), logo.len(), "-", 0);
        refused(verify(PUB, pack(&logo, None, Some(foreign)).as_bytes()), "not the owner's");
        let other_text = format!("pisophone-brand-v2|{}|{}|-|0", sha256_hex(&logo), logo.len());
        refused(verify(PUB, pack(&logo, None, Some(other_text)).as_bytes()), "not the owner's");
    }

    #[test]
    fn f049_only_png_and_jpeg_pass_never_svg_even_when_the_owner_signed_it() {
        let svg = b"<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'><script>alert(1)</script></svg>";
        refused(verify(PUB, pack(svg, None, None).as_bytes()), "PNG or JPEG");
        refused(verify(PUB, pack(b"GIF89a....", None, None).as_bytes()), "PNG or JPEG");
        refused(verify(PUB, pack(&png(64, 64, 0), Some(svg), None).as_bytes()), "PNG or JPEG");
        let html_with_png_start = [b"\x89PNG\r\n\x1a\n".as_slice(), b"<html><script>alert(1)</script>"].concat();
        assert!(check_image(&html_with_png_start).is_err());
    }

    #[test]
    fn f049_pictures_are_limited_in_pixels_and_bytes() {
        assert!(check_image(&png(512, 512, 0)).is_ok(), "512 by 512 is the largest");
        assert!(check_image(&png(513, 10, 0)).is_err());
        assert!(check_image(&png(10, 513, 0)).is_err());
        assert!(check_image(&png(0, 10, 0)).is_err());
        assert!(check_image(&png(10, 0, 0)).is_err());
        assert!(check_image(&jpeg(512, 512)).is_ok());
        assert!(check_image(&jpeg(513, 512)).is_err());
        assert!(check_image(&jpeg(0, 5)).is_err());
        let exact = png(100, 100, MAX_IMAGE);
        assert_eq!(exact.len(), MAX_IMAGE);
        assert!(check_image(&exact).is_ok(), "64 KB exactly is allowed");
        let over = png(100, 100, MAX_IMAGE + 1);
        assert!(over.len() > MAX_IMAGE);
        refused(verify(PUB, pack(&over, None, None).as_bytes()), "64 KB");
        refused(verify(PUB, pack(&png(64, 64, 0), Some(&over), None).as_bytes()), "64 KB");
    }

    #[test]
    fn f049_a_picture_must_be_whole_not_cut_short_and_start_as_it_claims() {
        let mut cut = png(64, 64, 0);
        cut.truncate(cut.len() - 5);
        assert!(check_image(&cut).is_err(), "a PNG without its end");
        let mut no_ihdr = b"\x89PNG\r\n\x1a\n".to_vec();
        no_ihdr.extend(chunk(b"tEXt", b"first"));
        no_ihdr.extend(chunk(b"IEND", &[]));
        assert!(check_image(&no_ihdr).is_err(), "IHDR must come first");
        let mut j = jpeg(10, 10);
        j.truncate(j.len() - 2);
        assert!(check_image(&j).is_err(), "a JPEG without its end");
        assert!(check_image(&[0xff, 0xd8, 0xff, 0xd9]).is_err(), "a JPEG with no frame header");
        assert!(check_image(&[]).is_err());
        assert!(check_image(&[0x89, b'P']).is_err());
    }

    #[test]
    fn f049_a_damaged_or_oversize_pack_file_is_refused_before_anything_else() {
        let logo = png(64, 64, 0);
        let good = pack(&logo, None, None);
        refused(verify(PUB, b""), "not a brand pack");
        refused(verify(PUB, b"pisophone-brand-v9\nlogo 1 x\n"), "not a brand pack");
        refused(verify(PUB, good.replacen("pisophone-brand-v1", "pisophone-brand-v1 ", 1).as_bytes()), "not a brand pack");
        refused(verify(PUB, format!("{}extra line\n", good).as_bytes()), "not a brand pack");
        refused(verify(PUB, good.replacen("logo ", "logos ", 1).as_bytes()), "not a brand pack");
        refused(verify(PUB, good.replacen(&b64_encode(&logo), "!!!not base64!!!", 1).as_bytes()), "not a brand pack");
        refused(verify(PUB, good.replacen("picture 0 -", "picture 0 abc", 1).as_bytes()), "not a brand pack");
        refused(verify(PUB, &vec![b'a'; MAX_FILE + 1]), "too large");
        assert!(verify(PUB, good.replace('\n', "\r\n").as_bytes()).is_ok(), "a file that went through Windows is still fine");
        let no_logo = good.replacen(&format!("logo {} {}", logo.len(), sha256_hex(&logo)), "logo 0 -", 1);
        refused(verify(PUB, no_logo.as_bytes()), "logo");
    }

    #[test]
    fn f049_installing_keeps_the_images_under_their_checksums_and_replaces_the_old_ones() {
        let dir = tmp("install");
        assert!(installed(&dir).0.is_none() && read_image(&dir, Slot::Logo).is_none());
        let (logo1, pic) = (png(64, 64, 0), jpeg(300, 200));
        let s = install(&dir, PUB, pack(&logo1, Some(&pic), None).as_bytes()).unwrap();
        assert_eq!((s.logo_bytes, s.picture_bytes), (logo1.len(), pic.len()));
        let (kind, bytes) = read_image(&dir, Slot::Logo).unwrap();
        assert_eq!((kind, bytes), (Kind::Png, logo1.clone()));
        assert_eq!(read_image(&dir, Slot::Picture).unwrap().0, Kind::Jpeg);
        let first_files: Vec<String> =
            std::fs::read_dir(format!("{}/brand", dir)).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
        // a new pack with a logo only: the old logo and the old picture are gone, the new logo is there
        let logo2 = png(80, 80, 0);
        install(&dir, PUB, pack(&logo2, None, None).as_bytes()).unwrap();
        assert_eq!(read_image(&dir, Slot::Logo).unwrap().1, logo2);
        assert!(read_image(&dir, Slot::Picture).is_none(), "a pack without a picture removes the old picture");
        let files: Vec<String> =
            std::fs::read_dir(format!("{}/brand", dir)).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
        assert!(
            files.iter().filter(|f| f.starts_with("logo-")).count() == 1 && !files.iter().any(|f| f.starts_with("picture-")),
            "{:?} (was {:?})",
            files,
            first_files
        );
        // a damaged image file is not served
        let stored = installed(&dir).0.unwrap();
        std::fs::write(format!("{}/brand/{}", dir, stored.file), b"changed").unwrap();
        assert!(read_image(&dir, Slot::Logo).is_none(), "the checksum no longer matches");
    }

    #[test]
    fn f049_a_refused_pack_installs_nothing_and_leaves_what_was_there() {
        let dir = tmp("refused");
        let good = png(64, 64, 0);
        install(&dir, PUB, pack(&good, None, None).as_bytes()).unwrap();
        let before = installed(&dir);
        let bad = pack(b"<svg/>", None, None);
        assert!(install(&dir, PUB, bad.as_bytes()).is_err());
        assert!(install(&dir, OTHER_PUB, pack(&png(70, 70, 0), None, None).as_bytes()).is_err());
        assert_eq!(installed(&dir), before);
        assert_eq!(read_image(&dir, Slot::Logo).unwrap().1, good);
        let empty = tmp("refused-empty");
        assert!(install(&empty, PUB, bad.as_bytes()).is_err());
        assert!(!std::path::Path::new(&format!("{}/brand", empty)).exists(), "nothing was written");
    }
}
