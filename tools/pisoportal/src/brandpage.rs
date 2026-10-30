//! The guest page in the shop's own branding. `decorate` takes the page the program always builds and changes only the places listed in
//! `ANCHORS`: the title, the icons, the header, the welcome, and (new) a promo, the house rules, contact and hours and the credit. Every
//! owner text goes in escaped; colours are only ever ones `Brand::theme` made, so nothing the owner typed can become markup or style.
//! A page that has lost one of its places (someone edited `page.html`) is not decorated at all: guests get the plain page, never half of one.
use crate::brand::{hex, Brand, Field, Lang, Theme};

const TITLE: &str = "<title>TechNet PisoWifi</title>";
const ICON: &str = r#"<link rel="icon" href="/brand.png">"#;
const TOUCH: &str = r#"<link rel="apple-touch-icon" href="/brand.png">"#;
const HEADER: &str = r#"<header><img src="/brand.png" alt="" width="28" height="28"><b>TechNet</b><span>PisoWifi</span></header>"#;
const WELCOME: &str = r#"<div class="hd" id="hd"><img src="/mascot.png" alt="" width="72" height="74"><p>Buy Wi-Fi time<span class="mut" style="display:block;font-weight:400">Pick a plan, insert a coin and you are online.</span></p></div>"#;
const GOS: &str = r#"<p class="sub" id="gos">Coins add up: every coin adds more time</p>"#;
const FOOT: &str = r#"<p class="foot">TechNet Solutions &middot; Connect &middot; Play &middot; More Possibilities</p>"#;
const HEAD_END: &str = "</head>";

/// Every place the decorated page is found by. Each must occur exactly once in the built page.
pub const ANCHORS: [&str; 8] = [TITLE, ICON, TOUCH, HEADER, WELCOME, GOS, FOOT, HEAD_END];

/// The standard line under "Buy Wi-Fi time", kept when the owner wrote no welcome.
const STANDARD: &str = "Pick a plan, insert a coin and you are online.";

/// The credit every branded page carries, drawn here and not part of any owner text. It is small, but it is always there.
const CREDIT: &str = r#"<p class="credit">by TechNet Solutions</p>"#;

fn esc(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '&' => o.push_str("&amp;"),
            '<' => o.push_str("&lt;"),
            '>' => o.push_str("&gt;"),
            '"' => o.push_str("&quot;"),
            '\'' => o.push_str("&#39;"),
            c => o.push(c),
        }
    }
    o
}

/// One line per row of the owner's text.
fn lines(s: &str) -> String {
    s.lines().map(str::trim).filter(|l| !l.is_empty()).map(esc).collect::<Vec<_>>().join("<br>")
}

fn style(t: &Theme) -> String {
    let mut s = format!(
        ":root{{--bg:{};--band:{};--s1:{};--s2:{};--ln:{};--ln2:{};--brand:{};--bfg:{}}}",
        hex(t.bg),
        hex(t.band),
        hex(t.s1),
        hex(t.s2),
        hex(t.ln),
        hex(t.ln2),
        hex(t.accent),
        hex(t.accent)
    );
    if t.accent != 0x1d63d6 {
        s.push_str(&format!(".sl{{background:{}}}", hex(t.accent)));
    }
    s.push_str(&format!(".btn:not(.alt){{color:{}}}.btn:active{{background:{}}}", hex(t.accent_text), hex(t.accent_down)));
    s.push_str("header img,.hd img{object-fit:contain}");
    s.push_str(".promo{margin:0 0 12px;padding:10px 12px;border:1px solid var(--brand);border-radius:10px;background:var(--s1);color:var(--tx);font-size:14px;line-height:20px;font-weight:600}");
    s.push_str(".rules{margin:14px 0 0;padding:12px;border:1px solid var(--ln);border-radius:10px;background:var(--s1);color:var(--mut);font-size:13px;line-height:18px}");
    s.push_str(".rules b{color:var(--tx)}.rules p{margin:6px 0 0}");
    s.push_str(".credit{margin:6px 2px 0;color:var(--mut);font-size:12px;line-height:16px;text-align:center}");
    s
}

/// The page with the shop's branding. `logo` and `picture` say which pictures are installed (served at /brand/logo and /brand/picture).
/// Nothing set at all gives the page back unchanged; a page that has lost one of its places is an error and is not decorated.
pub fn decorate(page: &str, b: &Brand, logo: bool, picture: bool) -> Result<String, &'static str> {
    if b.is_empty() {
        return Ok(page.to_string());
    }
    if ANCHORS.iter().any(|a| page.matches(a).count() != 1) {
        return Err("the page has changed and no longer matches the branding");
    }
    let t = b.theme();
    let txt = |f: Field| b.shown(f, Lang::En);
    let own = !b.name.is_empty(); // a shop that set no name keeps the TechNet name and pictures unless it has its own
    let mut p = page.to_string();
    let mut put = |from: &str, to: &str| p = p.replacen(from, to, 1);

    if own {
        put(TITLE, &format!("<title>{}</title>", esc(&b.name)));
    }
    let icon = |from: &str, rel: &str, put: &mut dyn FnMut(&str, &str)| {
        if logo {
            put(from, &format!(r#"<link rel="{}" href="/brand/logo">"#, rel));
        } else if own {
            put(from, "");
        }
    };
    icon(ICON, "icon", &mut put);
    icon(TOUCH, "apple-touch-icon", &mut put);

    let img = if logo {
        r#"<img src="/brand/logo" alt="" onerror="this.remove()">"#
    } else if own {
        ""
    } else {
        r#"<img src="/brand.png" alt="" width="28" height="28">"#
    };
    let name = if own { esc(&b.name) } else { "TechNet".to_string() };
    let tagline = match (txt(Field::Tagline), own) {
        ("", true) => String::new(),
        ("", false) => "<span>PisoWifi</span>".to_string(),
        (t, _) => format!("<span>{}</span>", esc(t)),
    };
    put(HEADER, &format!("<header>{}<b>{}</b>{}</header>", img, name, tagline));

    let promo = match txt(Field::Promo) {
        "" => String::new(),
        s => format!(r#"<p class="promo">{}</p>"#, esc(s)),
    };
    let pic = if picture {
        r#"<img src="/brand/picture" alt="" onerror="this.remove()">"#
    } else if own {
        ""
    } else {
        r#"<img src="/mascot.png" alt="" width="72" height="74">"#
    };
    let welcome = match txt(Field::Welcome) {
        "" => STANDARD.to_string(),
        s => esc(s),
    };
    put(
        WELCOME,
        &format!(
            r#"{}<div class="hd" id="hd">{}<p>Buy Wi-Fi time<span class="mut" style="display:block;font-weight:400">{}</span></p></div>"#,
            promo, pic, welcome
        ),
    );

    let rules = match txt(Field::Rules) {
        "" => String::new(),
        s => format!(r#"<div class="rules"><b>House rules</b><p>{}</p></div>"#, lines(s)),
    };
    put(GOS, &format!("{}{}", GOS, rules));

    let foot = [txt(Field::Contact), txt(Field::Hours)].iter().filter(|s| !s.is_empty()).map(|s| esc(s)).collect::<Vec<_>>().join("<br>");
    let foot = if foot.is_empty() { String::new() } else { format!(r#"<p class="foot">{}</p>"#, foot) };
    put(FOOT, &format!("{}{}", foot, CREDIT));

    put(HEAD_END, &format!("<style>{}</style></head>", style(&t)));
    Ok(p)
}

#[cfg(test)]
#[allow(clippy::field_reassign_with_default)] // the tests build a Brand field by field, as the console will
mod tests {
    use super::*;
    use crate::brand::{Field, Lang};

    fn base() -> String {
        crate::http::raw_page()
    }

    fn count(hay: &str, needle: &str) -> usize {
        hay.matches(needle).count()
    }

    fn named(name: &str) -> Brand {
        let mut b = Brand::default();
        b.name = name.into();
        b
    }

    fn full() -> Brand {
        let mut b = named("Aling Nena's Net Cafe");
        b.palette = "dagat".into();
        b.accent = "#ff7a1a".into();
        b.set_text(Field::Tagline, Lang::En, "Mabilis. Mura. Malinis.");
        b.set_text(Field::Welcome, Lang::En, "Welcome to our shop!");
        b.set_text(Field::Contact, Lang::En, "0917 123 4567 | fb.com/alingnena");
        b.set_text(Field::Rules, Lang::En, "No illegal downloads.\nBe kind.");
        b.set_text(Field::Hours, Lang::En, "Open 8am to 10pm");
        b.set_text(Field::Promo, Lang::En, "Happy hour: double time until 5pm");
        b
    }

    const CREDIT: &str = r#"<p class="credit">by TechNet Solutions</p>"#;

    #[test]
    fn f049_an_empty_brand_leaves_the_page_exactly_as_it_was() {
        let page = base();
        assert_eq!(decorate(&page, &Brand::default(), true, true).unwrap(), page);
    }

    #[test]
    fn f049_every_place_the_page_is_decorated_exists_once_in_the_real_page() {
        let page = base();
        for a in ANCHORS {
            assert_eq!(count(&page, a), 1, "the page must have exactly one: {}", a);
        }
        let mut broken = page.replace(ANCHORS[2], "");
        assert!(decorate(&broken, &full(), false, false).is_err(), "a page that lost an anchor is not decorated half way");
        broken = page.replace(ANCHORS[0], &format!("{}{}", ANCHORS[0], ANCHORS[0]));
        assert!(decorate(&broken, &full(), false, false).is_err(), "nor one that has an anchor twice");
    }

    #[test]
    fn f049_the_shops_name_replaces_technet_in_the_title_and_header_and_the_credit_stays() {
        let page = decorate(&base(), &full(), false, false).unwrap();
        assert!(page.contains("<title>Aling Nena&#39;s Net Cafe</title>"), "title");
        assert!(page.contains("<b>Aling Nena&#39;s Net Cafe</b><span>Mabilis. Mura. Malinis.</span>"), "header");
        assert!(!page.contains("<b>TechNet</b>") && !page.contains("<title>TechNet PisoWifi</title>"));
        assert_eq!(count(&page, CREDIT), 1);
        assert!(!page.contains("Connect &middot; Play"), "the TechNet slogan footer is replaced by the credit");
    }

    #[test]
    fn f049_pictures_appear_only_when_installed_and_the_technet_ones_never_do() {
        let none = decorate(&base(), &full(), false, false).unwrap();
        for gone in ["/brand.png", "/mascot.png", "/brand/logo", "/brand/picture", "rel=\"icon\"", "apple-touch-icon"] {
            assert!(!none.contains(gone), "a branded page with no pictures must not mention {}", gone);
        }
        let logo = decorate(&base(), &full(), true, false).unwrap();
        assert!(logo.contains(r#"<header><img src="/brand/logo" alt="" onerror="this.remove()"><b>"#), "the logo before the name");
        assert!(
            logo.contains(r#"<link rel="icon" href="/brand/logo">"#)
                && logo.contains(r#"<link rel="apple-touch-icon" href="/brand/logo">"#)
        );
        assert!(!logo.contains("/brand.png") && !logo.contains("/mascot.png") && !logo.contains("/brand/picture"));
        let both = decorate(&base(), &full(), true, true).unwrap();
        assert!(both.contains(r#"<img src="/brand/picture" alt="" onerror="this.remove()">"#), "the picture is the welcome picture");
        assert!(!both.contains("/mascot.png"));
    }

    #[test]
    fn f049_each_text_lands_where_the_owner_was_promised() {
        let page = decorate(&base(), &full(), false, false).unwrap();
        let idle = &page[page.find(r#"<div id="v-idle""#).unwrap()..page.find(r#"<div id="v-live""#).unwrap()];
        assert!(idle.contains(r#"<p class="promo">Happy hour: double time until 5pm</p>"#), "the promo is on the idle page");
        assert!(idle.find("class=\"promo\"").unwrap() < idle.find("id=\"hd\"").unwrap(), "above the welcome");
        assert!(
            idle.contains("Welcome to our shop!") && !idle.contains("Pick a plan, insert a coin and you are online."),
            "the welcome replaces the standard line"
        );
        assert!(
            idle.contains(r#"<div class="rules"><b>House rules</b><p>No illegal downloads.<br>Be kind.</p></div>"#),
            "the rules, one line each"
        );
        assert!(idle.find("id=\"gos\"").unwrap() < idle.find("class=\"rules\"").unwrap(), "below the coin button");
        for outside_idle in [&page[..page.find(r#"<div id="v-idle""#).unwrap()], &page[page.find(r#"<div id="v-live""#).unwrap()..]] {
            assert!(
                !outside_idle.contains("class=\"promo\"") && !outside_idle.contains("class=\"rules\""),
                "promo and rules belong to the idle page only"
            );
        }
        assert!(
            page.contains(r#"<p class="foot">0917 123 4567 | fb.com/alingnena<br>Open 8am to 10pm</p>"#),
            "contact and hours in the footer, every state"
        );
        assert!(page.find("class=\"foot\"").unwrap() < page.find(CREDIT).unwrap());
    }

    #[test]
    fn f049_an_empty_text_shows_nothing_and_never_an_empty_frame() {
        let page = decorate(&base(), &named("Nena"), false, false).unwrap();
        for frame in ["class=\"promo\"", "class=\"rules\"", "class=\"foot\""] {
            assert!(!page.contains(frame), "{}", frame);
        }
        assert!(page.contains("Pick a plan, insert a coin and you are online."), "no welcome set: the standard line stays");
        assert!(page.contains("<b>Nena</b></header>"), "no tagline: just the name");
        let hours_only = {
            let mut b = named("Nena");
            b.set_text(Field::Hours, Lang::En, "Open 8 to 10");
            decorate(&base(), &b, false, false).unwrap()
        };
        assert!(hours_only.contains(r#"<p class="foot">Open 8 to 10</p>"#));
    }

    #[test]
    fn f049_no_text_can_become_markup_or_leave_its_place() {
        let evil = [
            "<script>alert(1)</script>",
            "\"><img src=x onerror=1>",
            "</title><script>1</script>",
            "</style><script>1</script>",
            "<!-- x",
            "' onload='1",
            "&lt;b&gt;",
        ];
        let plain = base();
        for payload in evil {
            let mut b = named(payload);
            for l in Lang::ALL {
                for f in Field::ALL {
                    b.set_text(f, l, payload);
                }
            }
            let page = decorate(&plain, &b, true, true).unwrap();
            assert!(!page.contains(payload), "the raw text {:?} reached the page", payload);
            assert_eq!(count(&page, "<script"), count(&plain, "<script"), "{:?} added a script", payload);
            assert_eq!(count(&page, "</title>"), count(&plain, "</title>"), "{:?} closed the title early", payload);
            assert_eq!(count(&page, "</style>"), count(&plain, "</style>") + 1, "only our own style block was added: {:?}", payload);
            assert_eq!(count(&page, "<!--"), count(&plain, "<!--"), "{:?}", payload);
            assert_eq!(count(&page, CREDIT), 1);
            assert_eq!(
                count(&page, " onerror=\"this.remove()\""),
                2 + count(&plain, " onerror="),
                "only the two picture tags have an event attribute"
            );
        }
        let mut b = named("x");
        b.set_text(Field::Promo, Lang::En, "5 > 3 & 2 < 4 \"quoted\"");
        assert!(decorate(&plain, &b, false, false).unwrap().contains("5 &gt; 3 &amp; 2 &lt; 4 &quot;quoted&quot;"));
    }

    #[test]
    fn f049_only_known_colours_reach_the_style_block() {
        let mut b = named("x");
        b.accent = "red;} body{display:none}".into();
        b.palette = "dagat;} .credit{display:none}".into();
        b.layout = "}</style>".into();
        let page = decorate(&base(), &b, false, false).unwrap();
        let plain = base();
        assert_eq!(count(&page, "display:none}"), count(&plain, "display:none}"), "an unchecked palette or layout never reaches the CSS");
        assert!(!page.contains("red;}") && !page.contains("body{display"), "nor an unchecked accent");
        let theme = Brand::default().theme();
        assert!(page.contains(&format!("--bg:{}", crate::brand::hex(theme.bg))), "an unknown palette means the default one");
        let ok = decorate(&base(), &full(), false, false).unwrap();
        let t = full().theme();
        assert!(ok.contains(&format!("--brand:{}", crate::brand::hex(t.accent))) && ok.contains("--brand:#ff7a1a"));
        assert!(ok.contains(&format!(".btn:not(.alt){{color:{}}}", crate::brand::hex(t.accent_text))));
    }

    #[test]
    fn f049_the_credit_is_in_every_branded_page_whatever_is_set_and_cannot_be_styled_away() {
        let mut variants = vec![named("x"), full(), Brand::default()];
        let mut all_empty_but_palette = Brand::default();
        all_empty_but_palette.palette = "rosas".into();
        variants.push(all_empty_but_palette);
        for (i, b) in variants.iter().enumerate() {
            for (logo, picture) in [(false, false), (true, false), (true, true)] {
                let page = decorate(&base(), b, logo, picture).unwrap();
                if b.is_empty() {
                    assert_eq!(page, base());
                    continue;
                }
                assert_eq!(count(&page, CREDIT), 1, "variant {}", i);
                let inside_w = &page[page.find(r#"<div class="w">"#).unwrap()..];
                assert!(inside_w.contains(CREDIT), "the credit is inside the page body, below the views");
                let style = &page[page.rfind("<style>").unwrap()..page.rfind("</style>").unwrap()];
                assert!(style.contains(".credit{") && !style.contains("display:none") && !style.contains("visibility:hidden"), "{}", style);
            }
        }
    }
}
