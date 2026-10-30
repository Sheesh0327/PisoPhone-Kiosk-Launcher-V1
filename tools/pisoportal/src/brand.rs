//! Shop branding of the guest Wi-Fi page (feature 049, group 1): what a shop owner may set, and how it is kept.
//!
//! A `Brand` is configuration, never code: short texts, a palette id, one accent colour and a layout id. Nothing here is run or
//! trusted: every value is cleaned (control characters and text-direction tricks removed, limits counted in characters, unknown
//! ids and bad colours dropped) each time it is made, saved or loaded, so a hand-edited file cannot break a limit. The page inserts
//! the texts as plain text; this module never has to escape them for HTML.
//!
//! Two files per shop in `DATA_DIR/brand/`: `draft.txt` (what the owner is editing) and `published.txt` (what customers see). A
//! router with no `published.txt` shows today's page unchanged. Both are written with `util::write_atomic`.
//! The images (logo) are not here: they come only from a signed brand pack (group 2).
use crate::util::{json_esc, write_atomic};
use std::fs;
use std::io;

pub const NAME_MAX: usize = 32;
pub const LAYOUTS: [&str; 4] = ["classic", "banner", "compact", "poster"];
const DEFAULT_PALETTE: &str = "technet";
const DEFAULT_LAYOUT: &str = "classic";
pub(crate) const BRAND_DIR: &str = "brand";

/// The page's own colours, the same on every palette (they are what the palettes are checked against).
#[cfg(test)]
pub const TEXT: u32 = 0xeef3ff;
#[cfg(test)]
pub const MUTED: u32 = 0x97a6c7;
#[cfg(test)]
pub const GOLD: u32 = 0xffb81c;

/// An owner text; each exists once per language.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Field {
    Tagline,
    Welcome,
    Contact,
    Rules,
    Hours,
    Promo,
}

impl Field {
    pub const ALL: [Field; 6] = [Field::Tagline, Field::Welcome, Field::Contact, Field::Rules, Field::Hours, Field::Promo];

    pub fn key(self) -> &'static str {
        match self {
            Field::Tagline => "tagline",
            Field::Welcome => "welcome",
            Field::Contact => "contact",
            Field::Rules => "rules",
            Field::Hours => "hours",
            Field::Promo => "promo",
        }
    }

    /// The most characters (not bytes) the text may have.
    pub fn max(self) -> usize {
        match self {
            Field::Tagline => 60,
            Field::Welcome => 160,
            Field::Contact => 80,
            Field::Rules => 600,
            Field::Hours => 60,
            Field::Promo => 120,
        }
    }

    /// Only the house rules may run over several lines.
    fn multiline(self) -> bool {
        self == Field::Rules
    }

    fn label(self) -> &'static str {
        match self {
            Field::Tagline => "the tagline",
            Field::Welcome => "the welcome text",
            Field::Contact => "the contact line",
            Field::Rules => "the house rules",
            Field::Hours => "the opening hours",
            Field::Promo => "the promo banner",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Lang {
    En,
    Fil,
}

impl Lang {
    pub const ALL: [Lang; 2] = [Lang::En, Lang::Fil];

    pub fn key(self) -> &'static str {
        match self {
            Lang::En => "en",
            Lang::Fil => "fil",
        }
    }

    fn label(self) -> &'static str {
        match self {
            Lang::En => "English",
            Lang::Fil => "Filipino",
        }
    }
}

/// A built-in palette: the page colour, the header band, the colour of the cards and the palette's own accent (the same six names the
/// phones use). The cards of "technet" are today's page's card colour exactly; the others are the page colour moved 85% toward the band,
/// which keeps the page's grey and gold text at 4.5 to 1 or better on every palette (a test checks it). The layouts must use these.
pub struct Palette {
    pub id: &'static str,
    pub bg: u32,
    pub band: u32,
    pub panel: u32,
    pub accent: u32,
}

pub const PALETTES: [Palette; 6] = [
    Palette { id: "technet", bg: 0x050d22, band: 0x0a1b45, panel: 0x0c1a3a, accent: 0x16c9a4 },
    Palette { id: "dagat", bg: 0x061a2e, band: 0x0b3557, panel: 0x0a3151, accent: 0x38bdf8 },
    Palette { id: "tropiko", bg: 0x04161a, band: 0x063a3a, panel: 0x063535, accent: 0x16c9a4 },
    Palette { id: "ginto", bg: 0x0b1530, band: 0x2a2210, panel: 0x252015, accent: 0xffb81c },
    Palette { id: "rosas", bg: 0x2a0a1c, band: 0x4a1431, panel: 0x45122e, accent: 0xff6fb5 },
    Palette { id: "berde", bg: 0x04190f, band: 0x0b3a1e, panel: 0x0a351c, accent: 0x4ade80 },
];

pub fn palette(id: &str) -> Option<&'static Palette> {
    PALETTES.iter().find(|p| p.id == id)
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Brand {
    /// The shop's name (one for both languages).
    pub name: String,
    /// A built-in palette id, or "" for the default.
    pub palette: String,
    /// "#rrggbb", or "" for the palette's own accent.
    pub accent: String,
    /// A layout id, or "" for the default.
    pub layout: String,
    /// [language][field]
    texts: [[String; 6]; 2],
}

impl Brand {
    pub fn text(&self, f: Field, l: Lang) -> &str {
        &self.texts[l as usize][f as usize]
    }

    pub fn set_text(&mut self, f: Field, l: Lang, v: &str) {
        self.texts[l as usize][f as usize] = v.to_string();
    }

    /// What the page shows in this language: the Filipino text, else the English one; English never falls back.
    pub fn shown(&self, f: Field, l: Lang) -> &str {
        let own = self.text(f, l);
        if own.is_empty() && l == Lang::Fil {
            self.text(f, Lang::En)
        } else {
            own
        }
    }

    /// Nothing set at all: the router looks exactly as it always did.
    pub fn is_empty(&self) -> bool {
        self.name.is_empty()
            && self.palette.is_empty()
            && self.accent.is_empty()
            && self.layout.is_empty()
            && self.texts.iter().all(|l| l.iter().all(|t| t.is_empty()))
    }

    pub fn palette_id(&self) -> &str {
        if palette(&self.palette).is_some() {
            &self.palette
        } else {
            DEFAULT_PALETTE
        }
    }

    pub fn layout_id(&self) -> &str {
        if LAYOUTS.contains(&self.layout.as_str()) {
            &self.layout
        } else {
            DEFAULT_LAYOUT
        }
    }

    /// The accent in use: the owner's (already made visible by `cleaned`), else the palette's own.
    pub fn accent_rgb(&self) -> u32 {
        parse_hex(&self.accent).unwrap_or_else(|| palette(self.palette_id()).map(|p| p.accent).unwrap_or(0x16c9a4))
    }

    /// A clean copy, and what had to be changed in the owner's words ("..., shortened to 60 characters").
    pub fn cleaned(&self) -> (Brand, Vec<String>) {
        let mut notes = Vec::new();
        let mut out = Brand::default();
        let (name, cut) = clean_text(&self.name, NAME_MAX, false);
        if cut {
            notes.push(format!("the shop name was shortened to {} characters", NAME_MAX));
        }
        out.name = name;
        out.palette = self.palette.trim().to_lowercase();
        if !out.palette.is_empty() && palette(&out.palette).is_none() {
            notes.push(format!("the palette \"{}\" is not one of the built-in ones and was ignored", clip(&out.palette)));
            out.palette.clear();
        }
        out.layout = self.layout.trim().to_lowercase();
        if !out.layout.is_empty() && !LAYOUTS.contains(&out.layout.as_str()) {
            notes.push(format!("the layout \"{}\" is not one of the built-in ones and was ignored", clip(&out.layout)));
            out.layout.clear();
        }
        let wanted = self.accent.trim();
        if !wanted.is_empty() {
            match parse_hex(wanted) {
                None => notes.push(format!("the accent colour \"{}\" must look like #ff7a1a and was ignored", clip(wanted))),
                Some(rgb) => {
                    let (fixed, changed) = fit_accent(rgb, out.palette_id());
                    if changed {
                        notes.push(format!(
                            "the accent colour {} is too close to the {} palette's background: it was made lighter ({}) so it stays visible",
                            hex(rgb),
                            out.palette_id(),
                            hex(fixed)
                        ));
                    }
                    out.accent = hex(fixed);
                }
            }
        }
        for l in Lang::ALL {
            for f in Field::ALL {
                let (t, cut) = clean_text(self.text(f, l), f.max(), f.multiline());
                if cut {
                    notes.push(format!("{} ({}) was shortened to {} characters", f.label(), l.label(), f.max()));
                }
                out.texts[l as usize][f as usize] = t;
            }
        }
        (out, notes)
    }

    /// The file form: `key=value` lines, a backslash and a line break written as `\\` and `\n`.
    pub fn to_text(&self) -> String {
        let mut s = String::new();
        let mut put = |k: &str, v: &str| {
            if !v.is_empty() {
                s.push_str(&format!("{}={}\n", k, v.replace('\\', "\\\\").replace('\n', "\\n")));
            }
        };
        put("name", &self.name);
        put("palette", &self.palette);
        put("accent", &self.accent);
        put("layout", &self.layout);
        for l in Lang::ALL {
            for f in Field::ALL {
                put(&format!("{}_{}", f.key(), l.key()), self.text(f, l));
            }
        }
        s
    }

    /// Reads the file form. Unknown keys and lines without `=` are skipped, and the result is cleaned.
    pub fn from_text(t: &str) -> Brand {
        let mut b = Brand::default();
        for line in t.lines() {
            let Some((k, v)) = line.split_once('=') else { continue };
            let v = unescape(v);
            match k {
                "name" => b.name = v,
                "palette" => b.palette = v,
                "accent" => b.accent = v,
                "layout" => b.layout = v,
                _ => {
                    for l in Lang::ALL {
                        for f in Field::ALL {
                            if k == format!("{}_{}", f.key(), l.key()) {
                                b.set_text(f, l, &v);
                            }
                        }
                    }
                }
            }
        }
        b.cleaned().0
    }
}

fn unescape(v: &str) -> String {
    let mut o = String::with_capacity(v.len());
    let mut it = v.chars();
    while let Some(c) = it.next() {
        if c == '\\' {
            match it.next() {
                Some('n') => o.push('\n'),
                Some(x) => o.push(x),
                None => {}
            }
        } else {
            o.push(c);
        }
    }
    o
}

/// A bit of what the owner typed, for a message.
fn clip(s: &str) -> String {
    s.chars().take(20).collect()
}

/// Plain text only: control characters and text-direction tricks are dropped, runs of spaces and of blank lines are squeezed, the ends
/// are trimmed and the text is cut to `max` characters. The second value says the text had to be cut.
fn clean_text(s: &str, max: usize, multiline: bool) -> (String, bool) {
    let s = s.replace("\r\n", "\n").replace('\r', "\n");
    let mut o = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}' | '\u{200e}' | '\u{200f}' | '\u{061c}' | '\u{feff}' => {}
            '\n' if multiline => o.push('\n'),
            '\n' | '\t' => o.push(' '),
            c if c.is_control() => {}
            c => o.push(c),
        }
    }
    while o.contains("  ") {
        o = o.replace("  ", " ");
    }
    while o.contains("\n\n\n") {
        o = o.replace("\n\n\n", "\n\n");
    }
    let o = o.trim().to_string();
    if o.chars().count() > max {
        (o.chars().take(max).collect::<String>().trim_end().to_string(), true)
    } else {
        (o, false)
    }
}

// ---- colours ---------------------------------------------------------------------------------------------------------------------

/// "#rrggbb" (a leading and trailing space is tolerated by the caller); nothing else.
pub fn parse_hex(s: &str) -> Option<u32> {
    let s = s.trim();
    let h = s.strip_prefix('#')?;
    if h.len() != 6 || !h.bytes().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    u32::from_str_radix(h, 16).ok()
}

pub fn hex(c: u32) -> String {
    format!("#{:06x}", c & 0xffffff)
}

fn luminance(c: u32) -> f64 {
    let lin = |v: u32| {
        let v = v as f64 / 255.0;
        if v <= 0.03928 {
            v / 12.92
        } else {
            ((v + 0.055) / 1.055).powf(2.4)
        }
    };
    0.2126 * lin((c >> 16) & 255) + 0.7152 * lin((c >> 8) & 255) + 0.0722 * lin(c & 255)
}

/// The WCAG contrast ratio of two colours, 1 to 21.
pub fn contrast(a: u32, b: u32) -> f64 {
    let (x, y) = (luminance(a), luminance(b));
    (x.max(y) + 0.05) / (x.min(y) + 0.05)
}

/// `a` moved `t` (0 to 1) of the way to `b`.
fn mix(a: u32, b: u32, t: f64) -> u32 {
    let ch = |s: u32| {
        let (x, y) = (((a >> s) & 255) as f64, ((b >> s) & 255) as f64);
        (x + (y - x) * t).round().clamp(0.0, 255.0) as u32
    };
    (ch(16) << 16) | (ch(8) << 8) | ch(0)
}

/// The colour of the words on a button of this accent: black or white, whichever reads better. One of them always gives 4.5 to 1.
pub fn button_text(accent: u32) -> u32 {
    if contrast(0x000000, accent) >= contrast(0xffffff, accent) {
        0x000000
    } else {
        0xffffff
    }
}

/// An accent must show (3 to 1) against the page, the band and the cards (both kinds) of its palette. One that does not is lightened step by step
/// until it does. Returns the colour to use and whether it had to change.
pub fn fit_accent(accent: u32, palette_id: &str) -> (u32, bool) {
    let p = palette(palette_id).unwrap_or(&PALETTES[0]);
    let shows = |c: u32| [p.bg, p.band, p.panel, raised(p)].iter().all(|&bg| contrast(c, bg) >= 3.0);
    if shows(accent) {
        return (accent, false);
    }
    for step in 1..=25 {
        let c = mix(accent, 0xffffff, step as f64 * 0.04);
        if shows(c) {
            return (c, true);
        }
    }
    (0xffffff, true)
}

// ---- the files -------------------------------------------------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Which {
    Published,
    Draft,
}

fn path(dir: &str, w: Which) -> String {
    format!("{}/{}/{}.txt", dir, BRAND_DIR, if w == Which::Published { "published" } else { "draft" })
}

/// The saved branding, or None when there is none, it is empty, or the file is damaged (the page then looks as it always did).
pub fn load(dir: &str, w: Which) -> Option<Brand> {
    let bytes = fs::read(path(dir, w)).ok()?;
    let b = Brand::from_text(&String::from_utf8(bytes).ok()?);
    (!b.is_empty()).then_some(b)
}

/// What the console editor starts from: the draft when there is one (even an emptied one), else the live branding, else nothing.
pub fn editing(dir: &str) -> Brand {
    match fs::read(path(dir, Which::Draft)) {
        Ok(bytes) => Brand::from_text(&String::from_utf8_lossy(&bytes)),
        Err(_) => load(dir, Which::Published).unwrap_or_default(),
    }
}

/// Saves a cleaned copy (never what was typed, unchecked).
pub fn save(dir: &str, w: Which, b: &Brand) -> io::Result<()> {
    write_atomic(&path(dir, w), b.cleaned().0.to_text().as_bytes())
}

/// Makes the draft live, all at once. Nothing is published from a missing or empty draft.
pub fn publish(dir: &str) -> Result<(), String> {
    let Some(draft) = load(dir, Which::Draft) else {
        return Err(if fs::metadata(path(dir, Which::Draft)).is_ok() {
            "the draft is empty: use Restore default look to show the standard page".to_string()
        } else {
            "there is no draft to publish".to_string()
        });
    };
    save(dir, Which::Published, &draft).map_err(|e| format!("could not save the branding: {}", e))
}

/// Back to today's page: the published branding is removed, the draft is kept.
pub fn restore_default(dir: &str) -> io::Result<()> {
    match fs::remove_file(path(dir, Which::Published)) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

/// The colours the page is drawn with. Everything on it is one of these, so the contrast tests cover the whole page.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Theme {
    pub bg: u32,
    pub band: u32,
    pub s1: u32,
    pub s2: u32,
    pub ln: u32,
    pub ln2: u32,
    pub accent: u32,
    pub accent_text: u32,
    pub accent_down: u32,
}

/// Today's page, in the names above: the default palette with no accent chosen draws exactly these (the blue button, not the teal accent).
const TODAY: Theme = Theme {
    bg: 0x050d22,
    band: 0x0a1b45,
    s1: 0x0c1a3a,
    s2: 0x132552,
    ln: 0x1f3361,
    ln2: 0x5a70a3,
    accent: 0x1d63d6,
    accent_text: 0xffffff,
    accent_down: 0x164fa8,
};

/// The raised card (the page's `--s2`) of a palette: its card colour a little lighter. "technet" keeps today's.
fn raised(p: &Palette) -> u32 {
    if p.id == "technet" {
        TODAY.s2
    } else {
        mix(p.panel, 0xffffff, 0.04)
    }
}

impl Brand {
    /// The palette and accent as the page uses them. The accent is the one `cleaned` made visible; every colour is derived here so a
    /// layout cannot pick one that was not tested.
    pub fn theme(&self) -> Theme {
        let p = palette(self.palette_id()).unwrap_or(&PALETTES[0]);
        let (s2, ln, ln2) = if p.id == "technet" {
            (TODAY.s2, TODAY.ln, TODAY.ln2)
        } else {
            (raised(p), mix(p.band, 0xffffff, 0.18), mix(p.band, 0xffffff, 0.40))
        };
        let mut t = Theme { bg: p.bg, band: p.band, s1: p.panel, s2, ln, ln2, ..TODAY };
        if p.id == "technet" && self.accent.is_empty() {
            return t;
        }
        t.accent = fit_accent(self.accent_rgb(), p.id).0;
        t.accent_text = button_text(t.accent);
        t.accent_down = mix(t.accent, if t.accent_text == 0 { 0xffffff } else { 0 }, 0.18);
        t
    }
}

/// Changes whenever the published branding or its set of pictures changes (the page is built again then), and is the same for the same
/// files. The draft is not part of it. Nothing published and no pictures give 0.
pub fn stamp(dir: &str) -> u64 {
    let mut h: u64 = 0xcbf29ce484222325;
    let mut any = false;
    for f in ["published.txt", "images.txt"] {
        if let Ok(bytes) = fs::read(format!("{}/{}/{}", dir, BRAND_DIR, f)) {
            any = true;
            for b in f.bytes().chain(bytes) {
                h = (h ^ b as u64).wrapping_mul(0x100000001b3);
            }
        }
    }
    if any {
        h
    } else {
        0
    }
}

// ---- what guests (and the owner's print tools) may read ----------------------------------------------------------------------------

/// What the portal publishes at /brand.json: no sign-in, only what any guest already sees on the page (never the draft, never a file
/// path). With nothing published it is exactly `{"v":1,"branded":false}`. The shape is fixed: the owner's print tools read it (feature 050).
/// `logo` and `picture` say whether that picture can be fetched at /brand/logo and /brand/picture.
pub fn public_json(dir: &str) -> String {
    use crate::brandpack::{read_image, Slot};
    let Some(b) = load(dir, Which::Published) else { return r#"{"v":1,"branded":false}"#.to_string() };
    let mut s = format!(
        r#"{{"v":1,"branded":true,"name":"{}","palette":"{}","accent":"{}","layout":"{}","logo":{},"picture":{},"texts":{{"#,
        json_esc(&b.name),
        b.palette_id(),
        hex(b.accent_rgb()),
        b.layout_id(),
        read_image(dir, Slot::Logo).is_some(),
        read_image(dir, Slot::Picture).is_some()
    );
    for (i, f) in Field::ALL.iter().enumerate() {
        if i > 0 {
            s.push(',');
        }
        s.push_str(&format!(r#""{}":{{"en":"{}","fil":"{}"}}"#, f.key(), json_esc(b.text(*f, Lang::En)), json_esc(b.text(*f, Lang::Fil))));
    }
    s.push_str("}}");
    s
}

/// An installed picture for a guest: only while branding is published (an unbranded router serves none), and only if its file still
/// matches its checksum.
pub fn served_image(dir: &str, slot: crate::brandpack::Slot) -> Option<(crate::brandpack::Kind, Vec<u8>)> {
    load(dir, Which::Published)?;
    crate::brandpack::read_image(dir, slot)
}

#[cfg(test)]
#[allow(clippy::field_reassign_with_default)] // the tests build a Brand field by field, as the console will
mod tests {
    use super::*;

    fn tmp(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("brand-test-{}-{}", std::process::id(), name));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d.to_string_lossy().into_owned()
    }

    #[test]
    fn f049_a_new_brand_is_empty_and_means_todays_look() {
        let b = Brand::default();
        assert!(b.is_empty());
        assert_eq!(b.palette_id(), "technet");
        assert_eq!(b.layout_id(), "classic");
        let mut named = Brand::default();
        named.name = "Aling Nena".into();
        assert!(!named.is_empty());
        let mut coloured = Brand::default();
        coloured.palette = "dagat".into();
        assert!(!coloured.is_empty());
    }

    #[test]
    fn f049_every_text_has_its_limit_counted_in_characters_not_bytes() {
        let mut b = Brand::default();
        b.name = "ñ".repeat(NAME_MAX + 5);
        for f in Field::ALL {
            b.set_text(f, Lang::Fil, &"ñ".repeat(f.max() + 7));
        }
        let (c, notes) = b.cleaned();
        assert_eq!(c.name.chars().count(), NAME_MAX);
        for f in Field::ALL {
            assert_eq!(c.text(f, Lang::Fil).chars().count(), f.max(), "{:?}", f);
        }
        assert_eq!(notes.len(), 1 + Field::ALL.len(), "one note for each shortened text: {:?}", notes);
        let limits: Vec<usize> = Field::ALL.iter().map(|f| f.max()).collect();
        assert_eq!((NAME_MAX, limits), (32, vec![60, 160, 80, 600, 60, 120]), "the limits the owner approved");
    }

    #[test]
    fn f049_control_and_direction_characters_are_removed_and_only_the_rules_may_have_lines() {
        let mut b = Brand::default();
        b.name = "Nena\u{0}\u{7}'s\ttea\r\nshop\u{202e}\u{2066}\u{feff}".into();
        b.set_text(Field::Rules, Lang::En, "No downloads\r\nBe kind\u{0}\n\n\n\nThanks");
        b.set_text(Field::Promo, Lang::En, "Happy\nhour");
        let (c, _) = b.cleaned();
        assert_eq!(c.name, "Nena's tea shop");
        assert_eq!(c.text(Field::Rules, Lang::En), "No downloads\nBe kind\n\nThanks");
        assert_eq!(c.text(Field::Promo, Lang::En), "Happy hour");
    }

    #[test]
    fn f049_html_and_script_text_stays_plain_text_for_the_page_to_show_as_it_is() {
        let evil = "<script>alert(1)</script><img src=x onerror=alert(2)> \" ' & `";
        let mut b = Brand::default();
        b.set_text(Field::Welcome, Lang::En, evil);
        let (c, notes) = b.cleaned();
        assert_eq!(c.text(Field::Welcome, Lang::En), evil, "nothing is rewritten: the page inserts text with textContent");
        assert!(notes.is_empty());
        let again = Brand::from_text(&c.to_text());
        assert_eq!(again.text(Field::Welcome, Lang::En), evil);
    }

    #[test]
    fn f049_an_unknown_palette_or_layout_is_ignored_with_a_note() {
        let mut b = Brand::default();
        b.palette = "neon".into();
        b.layout = "wild".into();
        let (c, notes) = b.cleaned();
        assert_eq!((c.palette.as_str(), c.layout.as_str()), ("", ""));
        assert_eq!(notes.len(), 2, "{:?}", notes);
        for p in PALETTES {
            let mut ok = Brand::default();
            ok.palette = p.id.into();
            assert_eq!(ok.cleaned().0.palette, p.id);
        }
        for l in LAYOUTS {
            let mut ok = Brand::default();
            ok.layout = l.into();
            assert_eq!(ok.cleaned().0.layout, l);
        }
    }

    #[test]
    fn f049_the_accent_must_be_rrggbb_and_is_kept_in_lower_case() {
        for bad in ["#12", "red", "#12345g", "ff7a1a", "#ff7a1a00", "rgb(1,2,3)", "#ff7a1a; background:url(x)"] {
            let mut b = Brand::default();
            b.accent = bad.into();
            let (c, notes) = b.cleaned();
            assert_eq!(c.accent, "", "{}", bad);
            assert_eq!(notes.len(), 1, "{}: {:?}", bad, notes);
        }
        let mut b = Brand::default();
        b.accent = " #FF7A1A ".into();
        assert_eq!(b.cleaned().0.accent, "#ff7a1a");
    }

    #[test]
    fn f049_every_palette_keeps_all_the_page_text_readable() {
        for p in PALETTES {
            for (name, fg) in [("text", TEXT), ("muted", MUTED), ("gold", GOLD)] {
                for (what, bg) in [("page", p.bg), ("band", p.band), ("panel", p.panel)] {
                    let c = contrast(fg, bg);
                    assert!(c >= 4.5, "{} {} on the {} of {} is only {:.2}:1", name, hex(fg), what, p.id, c);
                }
            }
            for (what, bg) in [("page", p.bg), ("band", p.band), ("panel", p.panel)] {
                assert!(contrast(p.accent, bg) >= 3.0, "the accent of {} on its {}", p.id, what);
            }
        }
    }

    #[test]
    fn f049_text_on_a_button_is_readable_whatever_accent_is_chosen() {
        let mut worst = 99.0f64;
        for r in (0..=255u32).step_by(15) {
            for g in (0..=255u32).step_by(15) {
                for b in (0..=255u32).step_by(15) {
                    let accent = (r << 16) | (g << 8) | b;
                    let c = contrast(button_text(accent), accent);
                    worst = worst.min(c);
                    assert!(c >= 4.5, "{} gives only {:.2}:1", hex(accent), c);
                }
            }
        }
        assert!(worst >= 4.5);
    }

    #[test]
    fn f049_an_accent_too_close_to_the_background_is_lightened_until_it_shows() {
        let p = palette("technet").unwrap();
        let (kept, changed) = fit_accent(0xff7a1a, "technet");
        assert_eq!((kept, changed), (0xff7a1a, false), "a colour that already shows is not touched");
        let (fixed, changed) = fit_accent(0x0a1b45, "technet");
        assert!(changed && fixed != 0x0a1b45);
        for bg in [p.bg, p.band, p.panel] {
            assert!(contrast(fixed, bg) >= 3.0, "{} on {}", hex(fixed), hex(bg));
        }
        let mut b = Brand::default();
        b.palette = "technet".into();
        b.accent = "#0a1b45".into();
        let (c, notes) = b.cleaned();
        assert_eq!(c.accent, hex(fixed));
        assert!(notes.iter().any(|n| n.contains("lighter")), "{:?}", notes);
        let mut black = Brand::default();
        black.accent = "#000000".into();
        let (c2, _) = black.cleaned();
        assert_ne!(c2.accent, "#000000", "with no palette chosen the default palette is the reference");
    }

    #[test]
    fn f049_a_text_without_a_filipino_version_shows_in_english() {
        let mut b = Brand::default();
        b.set_text(Field::Promo, Lang::En, "Happy hour");
        assert_eq!(b.shown(Field::Promo, Lang::Fil), "Happy hour");
        b.set_text(Field::Promo, Lang::Fil, "Happy hour na!");
        assert_eq!(b.shown(Field::Promo, Lang::Fil), "Happy hour na!");
        assert_eq!(b.shown(Field::Promo, Lang::En), "Happy hour");
        let mut only_fil = Brand::default();
        only_fil.set_text(Field::Hours, Lang::Fil, "Bukas 8am-10pm");
        assert_eq!(only_fil.shown(Field::Hours, Lang::En), "", "English never falls back to Filipino");
        assert_eq!(only_fil.shown(Field::Hours, Lang::Fil), "Bukas 8am-10pm");
    }

    #[test]
    fn f049_a_brand_survives_a_trip_through_its_file() {
        let mut b = Brand::default();
        b.name = "Aling Nena's = Net \\ Cafe".into();
        b.palette = "dagat".into();
        b.accent = "#ff7a1a".into();
        b.layout = "poster".into();
        b.set_text(Field::Rules, Lang::En, "Line one\nLine \\n two = three\n\\");
        b.set_text(Field::Contact, Lang::Fil, "0917 123 4567");
        let (c, _) = b.cleaned();
        let back = Brand::from_text(&c.to_text());
        assert_eq!(back, c);
    }

    #[test]
    fn f049_a_hand_edited_file_cannot_break_the_limits() {
        let long = "x".repeat(500);
        let text =
            format!("name={}\nwelcome_en={}\nbogus=1\nno equals sign\n=\npalette=neon\naccent=#zzzzzz\nrules_fil=\\n\\n\\n\n", long, long);
        let b = Brand::from_text(&text);
        assert_eq!(b.name.chars().count(), NAME_MAX);
        assert_eq!(b.text(Field::Welcome, Lang::En).chars().count(), 160);
        assert_eq!((b.palette.as_str(), b.accent.as_str()), ("", ""));
        assert_eq!(b.text(Field::Rules, Lang::Fil), "");
    }

    #[test]
    fn f049_the_draft_and_the_published_branding_are_separate_files() {
        let dir = tmp("draft");
        assert!(load(&dir, Which::Published).is_none() && load(&dir, Which::Draft).is_none());
        let mut d = Brand::default();
        d.name = "Nena".into();
        save(&dir, Which::Draft, &d).unwrap();
        assert!(load(&dir, Which::Published).is_none(), "saving a draft changes nothing live");
        publish(&dir).unwrap();
        assert_eq!(load(&dir, Which::Published).unwrap().name, "Nena");
        d.name = "Nena 2".into();
        save(&dir, Which::Draft, &d).unwrap();
        assert_eq!(load(&dir, Which::Published).unwrap().name, "Nena", "editing the draft again does not touch the live branding");
        assert_eq!(load(&dir, Which::Draft).unwrap().name, "Nena 2");
        restore_default(&dir).unwrap();
        assert!(load(&dir, Which::Published).is_none(), "restore default looks like no branding was ever published");
        assert_eq!(load(&dir, Which::Draft).unwrap().name, "Nena 2", "the draft is kept");
        restore_default(&dir).unwrap(); // twice is fine
    }

    #[test]
    fn f049_publishing_needs_a_draft_with_something_in_it() {
        let dir = tmp("empty");
        assert!(publish(&dir).is_err(), "no draft at all");
        save(&dir, Which::Draft, &Brand::default()).unwrap();
        assert!(publish(&dir).is_err(), "an empty draft is not published (use restore default)");
        assert!(load(&dir, Which::Published).is_none());
    }

    #[test]
    fn f049_a_damaged_published_file_means_no_branding_not_a_crash() {
        let dir = tmp("damaged");
        std::fs::create_dir_all(format!("{}/brand", dir)).unwrap();
        std::fs::write(format!("{}/brand/published.txt", dir), [0xff, 0xfe, 0x00, 0x80, b'n', b'a']).unwrap();
        assert!(load(&dir, Which::Published).is_none());
        std::fs::write(format!("{}/brand/published.txt", dir), b"").unwrap();
        assert!(load(&dir, Which::Published).is_none(), "an empty file is no branding");
    }
    // a 1 by 1 PNG made by Python (the same one the pack tests use)
    const TINY_PNG: &str = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC";

    fn put_logo(dir: &str) {
        let bytes = crate::util::b64_decode(TINY_PNG).unwrap();
        let sha = crate::util::sha256_hex(&bytes);
        let name = format!("logo-{}.png", &sha[..12]);
        std::fs::create_dir_all(format!("{}/brand", dir)).unwrap();
        std::fs::write(format!("{}/brand/{}", dir, name), &bytes).unwrap();
        std::fs::write(format!("{}/brand/images.txt", dir), format!("logo {} {}\n", name, sha)).unwrap();
    }

    fn publish_name(dir: &str, name: &str) {
        let mut b = Brand::default();
        b.name = name.into();
        b.palette = "dagat".into();
        b.layout = "banner".into();
        b.set_text(Field::Contact, Lang::En, "0917 123 4567");
        b.set_text(Field::Rules, Lang::En, "No downloads\nBe \"kind\" \\ ok");
        b.set_text(Field::Promo, Lang::Fil, "Happy hour na!");
        save(dir, Which::Draft, &b).unwrap();
        publish(dir).unwrap();
    }

    #[test]
    fn f049_the_public_page_data_says_unbranded_until_something_is_published() {
        let dir = tmp("pub-none");
        assert_eq!(public_json(&dir), r#"{"v":1,"branded":false}"#);
        put_logo(&dir);
        assert_eq!(public_json(&dir), r#"{"v":1,"branded":false}"#, "an installed logo alone changes nothing");
        let mut d = Brand::default();
        d.name = "Draft only".into();
        save(&dir, Which::Draft, &d).unwrap();
        assert_eq!(public_json(&dir), r#"{"v":1,"branded":false}"#, "a draft is never public");
    }

    #[test]
    fn f049_the_public_page_data_has_a_fixed_shape_and_escapes_every_text() {
        let dir = tmp("pub-shape");
        publish_name(&dir, "Aling \"Nena\" <b>");
        let j = public_json(&dir);
        assert!(j.starts_with(r##"{"v":1,"branded":true,"name":"Aling \"Nena\" <b>","palette":"dagat","accent":"#38bdf8","layout":"banner","logo":false,"picture":false,"texts":{"##), "{}", j);
        assert!(j.contains(r#""contact":{"en":"0917 123 4567","fil":""}"#), "{}", j);
        assert!(j.contains(r#""rules":{"en":"No downloads\nBe \"kind\" \\ ok","fil":""}"#), "{}", j);
        assert!(j.contains(r#""promo":{"en":"","fil":"Happy hour na!"}"#), "{}", j);
        assert!(j.ends_with("}}"), "{}", j);
        assert!(!j.chars().any(|c| (c as u32) < 0x20), "no raw control character or line break in the JSON");
        let order: Vec<usize> = ["tagline", "welcome", "contact", "rules", "hours", "promo"]
            .iter()
            .map(|k| j.find(&format!("\"{}\":{{", k)).unwrap())
            .collect();
        assert!(order.windows(2).all(|w| w[0] < w[1]), "the texts always come in the same order");
    }

    #[test]
    fn f049_the_logo_is_offered_only_when_branded_and_really_installed() {
        let dir = tmp("pub-logo");
        publish_name(&dir, "Nena");
        assert!(public_json(&dir).contains(r#""logo":false"#));
        put_logo(&dir);
        assert!(public_json(&dir).contains(r#""logo":true,"picture":false"#));
        assert!(served_image(&dir, crate::brandpack::Slot::Logo).is_some());
        assert!(served_image(&dir, crate::brandpack::Slot::Picture).is_none());
        // a damaged image file is not offered
        let stored = crate::brandpack::installed(&dir).0.unwrap();
        std::fs::write(format!("{}/brand/{}", dir, stored.file), b"changed").unwrap();
        assert!(public_json(&dir).contains(r#""logo":false"#));
        assert!(served_image(&dir, crate::brandpack::Slot::Logo).is_none());
        // restoring the default look hides the logo again, though it stays installed
        let dir2 = tmp("pub-restore");
        publish_name(&dir2, "Nena");
        put_logo(&dir2);
        restore_default(&dir2).unwrap();
        assert_eq!(public_json(&dir2), r#"{"v":1,"branded":false}"#);
        assert!(served_image(&dir2, crate::brandpack::Slot::Logo).is_none(), "no logo is served on an unbranded router");
        assert!(crate::brandpack::installed(&dir2).0.is_some(), "but it stays installed");
    }

    #[test]
    fn f049_the_theme_of_every_palette_and_accent_keeps_every_text_readable() {
        for p in PALETTES {
            for accent in ["", "#ff7a1a", "#101010", "#ffffff", "#0b3557", "#ff0000"] {
                let mut b = Brand::default();
                b.palette = p.id.into();
                b.accent = accent.into();
                let t = b.cleaned().0.theme();
                for (name, fg) in [("text", TEXT), ("muted", MUTED), ("gold", GOLD)] {
                    for (what, bg) in [("page", t.bg), ("band", t.band), ("card", t.s1), ("raised card", t.s2)] {
                        let c = contrast(fg, bg);
                        assert!(c >= 4.5, "{} on the {} of {} with accent {:?} is only {:.2}:1", name, what, p.id, accent, c);
                    }
                }
                if !(p.id == "technet" && accent.is_empty()) {
                    // (today's blue button is the one colour that stays as it is; it is below 3:1 on the raised card)
                    for (what, bg) in [("page", t.bg), ("band", t.band), ("card", t.s1), ("raised card", t.s2)] {
                        assert!(contrast(t.accent, bg) >= 3.0, "accent {} on the {} of {}", hex(t.accent), what, p.id);
                    }
                }
                assert!(contrast(t.accent_text, t.accent) >= 4.5, "button text on {}", hex(t.accent));
                assert!(contrast(t.accent_text, t.accent_down) >= 4.5, "button text on the pressed {}", hex(t.accent_down));
                assert!(contrast(t.ln2, t.bg) >= 3.0, "control borders show on the page of {}", p.id);
            }
        }
    }

    #[test]
    fn f049_a_shop_that_only_sets_a_name_keeps_todays_colours_exactly() {
        let t = Brand::default().theme();
        assert_eq!((t.bg, t.band, t.s1, t.s2, t.ln, t.ln2), (0x050d22, 0x0a1b45, 0x0c1a3a, 0x132552, 0x1f3361, 0x5a70a3));
        assert_eq!((t.accent, t.accent_text, t.accent_down), (0x1d63d6, 0xffffff, 0x164fa8), "today's blue button");
        let mut teal = Brand::default();
        teal.accent = "#16c9a4".into();
        assert_eq!(teal.theme().accent, 0x16c9a4, "an accent the owner picked is used, even on the default palette");
        let mut other = Brand::default();
        other.palette = "ginto".into();
        assert_eq!(other.theme().accent, 0xffb81c, "another palette brings its own accent");
    }

    #[test]
    fn f049_the_stamp_changes_with_the_published_branding_and_its_pictures_and_nothing_else() {
        let dir = tmp("stamp");
        let none = stamp(&dir);
        assert_eq!(none, stamp(&dir), "same files, same stamp");
        let mut b = Brand::default();
        b.name = "Nena".into();
        save(&dir, Which::Draft, &b).unwrap();
        assert_eq!(stamp(&dir), none, "a draft is not the page");
        publish(&dir).unwrap();
        let one = stamp(&dir);
        assert_ne!(one, none);
        b.name = "Nena 2".into();
        save(&dir, Which::Draft, &b).unwrap();
        assert_eq!(stamp(&dir), one, "editing the draft changes nothing yet");
        publish(&dir).unwrap();
        let two = stamp(&dir);
        assert_ne!(two, one);
        std::fs::write(format!("{}/{}/images.txt", dir, BRAND_DIR), "logo-abcdef012345.png\n").unwrap();
        assert_ne!(stamp(&dir), two, "a new picture set shows at once");
        restore_default(&dir).unwrap();
        std::fs::remove_file(format!("{}/{}/images.txt", dir, BRAND_DIR)).unwrap();
        assert_eq!(stamp(&dir), none, "back to today's look is back to the unbranded stamp");
    }
}
