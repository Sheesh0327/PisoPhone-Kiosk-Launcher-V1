//! The guest page in the shop's own branding. `decorate` takes the page the program always builds and changes only the places listed in
//! `ANCHORS`: the title, the icons, the header, the welcome, and (new) a promo, the house rules, contact and hours and the credit. Every
//! owner text goes in escaped; colours are only ever ones `Brand::theme` made, so nothing the owner typed can become markup or style.
//! A page that has lost one of its places (someone edited `page.html`) is not decorated at all: guests get the plain page, never half of one.
//!
//! Two languages: a text with a Filipino version is written into the page twice (`data-e`, `data-f`) and the page's script swaps them when
//! the customer taps the language button; the page's own English wording is turned into Filipino there too (`brand_fil.js`). The four layouts
//! are the same page with another heading and other spacing (`brand_page.css`, chosen by the class on `<body>`); none of them touches a payment control.
use crate::brand::{hex, Brand, Field, Lang, Theme};
use crate::http::squeeze;

const TITLE: &str = "<title>TechNet PisoWifi</title>";
const ICON: &str = r#"<link rel="icon" href="/brand.png">"#;
const TOUCH: &str = r#"<link rel="apple-touch-icon" href="/brand.png">"#;
const HEADER: &str = r#"<header><img src="/brand.png" alt="" width="28" height="28"><b>TechNet</b><span>PisoWifi</span></header>"#;
const WELCOME: &str = r#"<div class="hd" id="hd"><img src="/mascot.png" alt="" width="72" height="74"><p>Buy Wi-Fi time<span class="mut" style="display:block;font-weight:400">Pick a plan, insert a coin and you are online.</span></p></div>"#;
const GOS: &str = r#"<p class="sub" id="gos">Coins add up: every coin adds more time</p>"#;
const FOOT: &str = r#"<p class="foot">TechNet Solutions &middot; Connect &middot; Play &middot; More Possibilities</p>"#;
/// The line shown when the page cannot reach the router for 8 seconds: today it names TechNet's Wi-Fi, which is wrong on a shop's own page.
const CONN_LINE: &str = "Still connecting. Check you are on the TechNet Wi-Fi.";
const CONN_LINE_SHOP: &str = "Still connecting. Check you are on the shop's Wi-Fi.";
/// The saved voucher file (a small HTML page the customer keeps) and its file name, built by the page's script.
const VF_FN: &str = "function vfile(c){";
const VF_TITLE: &str = "<title>PisoWiFi voucher</title>";
const VF_NAME: &str = "<p><b>TechNet PisoWiFi voucher</b></p>";
const VF_JOIN: &str = "Join the PisoWiFi network, then";
const VF_DOWNLOAD: &str = r#"a.download="TechNet-PisoWiFi-voucher-"+c.slice(0,5)+".html""#;
/// Read the shop's name back from the page (`data-shop`), make it safe for the HTML file and for a file name.
const VF_HELPERS: &str = r#"function shop(){return document.body.getAttribute("data-shop")||""}function hx(s){return s.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;")}function fname(){return shop().replace(/[^0-9A-Za-z]+/g,"-").replace(/^-+|-+$/g,"")||"Wi-Fi"}"#;
const HEAD_BODY: &str = "</head><body>";
const PAGE_END: &str = "</script></body></html>";

/// Every place the decorated page is found by. Each must occur exactly once in the built page.
pub const ANCHORS: [&str; 15] =
    [TITLE, ICON, TOUCH, HEADER, WELCOME, GOS, FOOT, HEAD_BODY, PAGE_END, CONN_LINE, VF_FN, VF_TITLE, VF_NAME, VF_JOIN, VF_DOWNLOAD];

/// The standard line under "Buy Wi-Fi time", kept when the owner wrote no welcome.
const STANDARD: &str = "Pick a plan, insert a coin and you are online.";

/// The credit every branded page carries, drawn here and not part of any owner text. It is small, but it is always there.
const CREDIT: &str = r#"<p class="credit">by TechNet Solutions</p>"#;

const LANG_BUTTON: &str = r#"<button class="lang" type="button">Filipino</button>"#;
const SWITCH_JS: &str = include_str!("brand_fil.js");
const LAYOUT_CSS: &str = include_str!("brand_page.css");

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

/// One block of owner text, in both languages. `en` and `fil` are the finished inner markup of each; nothing at all when both are empty. Where the
/// two differ the element carries both and the page's script swaps them; where English is empty it starts hidden.
fn slot(tag: &str, attrs: &str, en: &str, fil: &str) -> String {
    if en.is_empty() && fil.is_empty() {
        return String::new();
    }
    let both = if en == fil { String::new() } else { format!(r#" data-e="{}" data-f="{}""#, esc(en), esc(fil)) };
    let hidden = if en.is_empty() { " hidden" } else { "" };
    format!("<{tag}{attrs}{both}{hidden}>{en}</{tag}>")
}

/// A text in each language (English never borrows; Filipino falls back to English), shaped by `f`; "" when not set.
fn both(b: &Brand, field: Field, f: &dyn Fn(&str) -> String) -> (String, String) {
    let one = |l| match b.shown(field, l) {
        "" => String::new(),
        t => f(t),
    };
    (one(Lang::En), one(Lang::Fil))
}

/// Contact and hours, one under the other (or `sep` apart), per language.
fn contact_hours(b: &Brand, sep: &str) -> (String, String) {
    let join = |l| {
        [b.shown(Field::Contact, l), b.shown(Field::Hours, l)]
            .iter()
            .filter(|s| !s.is_empty())
            .map(|s| esc(s))
            .collect::<Vec<_>>()
            .join(sep)
    };
    (join(Lang::En), join(Lang::Fil))
}

fn style(t: &Theme) -> String {
    let mut s = format!(
        ":root{{--bg:{};--band:{};--s1:{};--s2:{};--ln:{};--ln2:{};--brand:{};--bfg:{};--bt:{}}}",
        hex(t.bg),
        hex(t.band),
        hex(t.s1),
        hex(t.s2),
        hex(t.ln),
        hex(t.ln2),
        hex(t.accent),
        hex(t.accent),
        hex(t.accent_text)
    );
    if t.accent != 0x1d63d6 {
        s.push_str(&format!(".sl{{background:{}}}", hex(t.accent)));
    }
    s.push_str(&format!(".btn:not(.alt){{color:{}}}.btn:active{{background:{}}}", hex(t.accent_text), hex(t.accent_down)));
    s.push_str(&squeeze(LAYOUT_CSS));
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
    let layout = b.layout_id();
    let own = !b.name.is_empty(); // a shop that set no name keeps the TechNet name and pictures unless it has its own
    let mut p = page.to_string();
    let mut put = |from: &str, to: &str| p = p.replacen(from, to, 1);

    if own {
        put(TITLE, &format!("<title>{}</title>", esc(&b.name)));
        put(CONN_LINE, CONN_LINE_SHOP);
        put(VF_FN, &format!("{}{}", VF_HELPERS, VF_FN));
        put(VF_TITLE, "<title>'+hx(shop())+' voucher</title>");
        put(VF_NAME, "<p><b>'+hx(shop())+' voucher</b></p>");
        put(VF_JOIN, "Join the shop&#39;s Wi-Fi, then");
        put(VF_DOWNLOAD, r#"a.download=fname()+"-voucher-"+c.slice(0,5)+".html""#);
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
    let (tag_en, tag_fil) = both(b, Field::Tagline, &esc);
    let tagline = match (tag_en.is_empty() && tag_fil.is_empty(), own) {
        (true, true) => String::new(),
        (true, false) => "<span>PisoWifi</span>".to_string(),
        _ => slot("span", "", &tag_en, &tag_fil),
    };
    let poster_line = {
        let (en, fil) = contact_hours(b, " &middot; ");
        if layout == "poster" {
            slot("em", "", &en, &fil)
        } else {
            String::new()
        }
    };
    let class = match layout {
        "banner" => r#" class="hero""#,
        "compact" => r#" class="cmp""#,
        "poster" => r#" class="poster""#,
        _ => "",
    };
    put(HEADER, &format!("<header{}>{}<b>{}</b>{}{}{}</header>", class, img, name, tagline, poster_line, LANG_BUTTON));

    let (promo_en, promo_fil) = both(b, Field::Promo, &esc);
    let promo = slot("p", r#" class="promo""#, &promo_en, &promo_fil);
    let pic = if picture {
        r#"<img src="/brand/picture" alt="" onerror="this.remove()">"#
    } else if own {
        ""
    } else {
        r#"<img src="/mascot.png" alt="" width="72" height="74">"#
    };
    let (wel_en, wel_fil) = both(b, Field::Welcome, &esc);
    let welcome = slot(
        "span",
        r#" class="mut" style="display:block;font-weight:400""#,
        if wel_en.is_empty() { STANDARD } else { &wel_en },
        if wel_fil.is_empty() { STANDARD } else { &wel_fil },
    );
    put(WELCOME, &format!(r#"{}<div class="hd" id="hd">{}<p>Buy Wi-Fi time{}</p></div>"#, promo, pic, welcome));

    let (rules_en, rules_fil) = both(b, Field::Rules, &lines);
    let (rtag, head) = if layout == "compact" { ("details", "summary") } else { ("div", "b") };
    let mk = |title: &str, text: &str| if text.is_empty() { String::new() } else { format!("<{head}>{title}</{head}><p>{text}</p>") };
    // (the heading is turned into Filipino by the page's script unless the Filipino text is its own)
    let (en_html, fil_html) = if rules_en == rules_fil {
        let x = mk("House rules", &rules_en);
        (x.clone(), x)
    } else {
        (mk("House rules", &rules_en), mk("Mga patakaran", &rules_fil))
    };
    let rules = slot(rtag, r#" class="rules""#, &en_html, &fil_html);
    put(GOS, &format!("{}{}", GOS, rules));

    let foot = if layout == "poster" {
        String::new()
    } else {
        let (en, fil) = contact_hours(b, "<br>");
        slot("p", r#" class="foot""#, &en, &fil)
    };
    put(FOOT, &format!("{}{}", foot, CREDIT));

    let shop = if own { format!(r#" data-shop="{}""#, esc(&b.name)) } else { String::new() };
    put(HEAD_BODY, &format!(r#"<style>{}</style></head><body class="l-{}"{}>"#, style(&t), layout, shop));
    put(PAGE_END, &format!("</script><script>{}</script></body></html>", SWITCH_JS.trim()));
    Ok(p)
}

const CONNECT_FN: &str = "function connect(){";
/// The page's start-up call, found by its two ends (the message between them, shown after 8 seconds, differs from page to page).
const START_HEAD: &str = r#"show("conn");connect();setInterval(beat,4e3);setTimeout(function(){"#;
const START_TAIL: &str = "},8e3);";

/// Stops every press, tap, key and script click on the page's body before it reaches a button (the language button, in the header, still
/// works), and takes the body out of the tab order.
const NO_PRESS: &str = r#"document.addEventListener("click",function(e){if(e.target.closest&&e.target.closest(".w")){e.stopPropagation();e.preventDefault()}},true);document.addEventListener("DOMContentLoaded",function(){document.querySelector(".w").setAttribute("inert","")})"#;

/// The page for the console's preview: the idle view only, with no way to talk to the coin box. The page never connects (the start-up call is
/// removed and `connect` itself does nothing, because the page also asks again whenever it is shown), and nothing on it can be pressed except
/// the language button. The console also sends it with a policy that forbids any connection. A page that has changed shape is an error.
pub fn inert(page: &str) -> Result<String, &'static str> {
    let shape = "the page has changed and the preview can no longer be made safe";
    if [CONNECT_FN, START_HEAD, START_TAIL, "</head>"].iter().any(|a| page.matches(a).count() != 1) {
        return Err(shape);
    }
    let from = page.find(START_HEAD).ok_or(shape)?;
    let to = from + page[from..].find(START_TAIL).ok_or(shape)? + START_TAIL.len();
    let cut = &page[from..to];
    if cut.matches("setInterval").count() != 1 || cut.matches("setTimeout").count() != 1 {
        return Err(shape);
    }
    let page = format!("{}{}{}", &page[..from], r#"show("idle");"#, &page[to..]);
    Ok(page.replacen(CONNECT_FN, "function connect(){return;", 1).replacen(
        "</head>",
        &format!("<style>.w{{pointer-events:none}}</style><script>{}</script></head>", NO_PRESS),
        1,
    ))
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
        assert!(page.contains("<b>Nena</b>") && !page.contains("<b>Nena</b><span>"), "no tagline: just the name");
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
            assert_eq!(
                count(&page, "<script"),
                count(&plain, "<script") + 1,
                "{:?} added a script besides the page's own and ours",
                payload
            );
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
        assert!(
            !page.contains("red;}") && !page.contains("dagat;}") && !page.contains("body{display"),
            "an unchecked accent, palette or layout never reaches the CSS"
        );
        assert!(page.contains(r#"<body class="l-classic""#), "an unknown layout is the default one");
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
                assert!(
                    style.contains(".credit{") && style.matches("credit").count() == 1,
                    "the credit has its one rule and no other rule names it"
                );
            }
        }
    }
    fn with_layout(id: &str) -> Brand {
        let mut b = full();
        b.layout = id.into();
        b
    }

    #[test]
    fn f049_each_layout_has_its_own_structure_and_all_keep_the_page_whole() {
        for id in crate::brand::LAYOUTS {
            let page = decorate(&base(), &with_layout(id), true, true).unwrap();
            assert!(page.contains(&format!(r#"<body class="l-{}""#, id)), "{}", id);
            assert_eq!(count(&page, CREDIT), 1, "{}", id);
            assert_eq!(count(&page, r#"class="lang""#), 1, "{}: one language switch", id);
            for kept in ["id=\"go\"", "id=\"v-idle\"", "id=\"pk\"", "%TIERS_H%", "id=\"ldone\""] {
                assert!(page.contains(kept), "{} lost {}", id, kept);
            }
            assert!(
                page.contains("Aling Nena&#39;s Net Cafe") && page.contains("0917 123 4567") && page.contains("Open 8am to 10pm"),
                "{}: name, contact and hours show",
                id
            );
        }
        let header = |id: &str| {
            let p = decorate(&base(), &with_layout(id), false, false).unwrap();
            p[p.find("<header").unwrap()..p.find("</header>").unwrap()].to_string()
        };
        assert!(header("classic").starts_with("<header>") && header("banner").starts_with(r#"<header class="hero">"#));
        assert!(header("compact").starts_with(r#"<header class="cmp">"#) && header("poster").starts_with(r#"<header class="poster">"#));
        assert!(
            header("poster").contains("<em>0917 123 4567 | fb.com/alingnena &middot; Open 8am to 10pm</em>"),
            "the poster puts contact and hours in its heading"
        );
        assert!(
            !decorate(&base(), &with_layout("poster"), false, false).unwrap().contains(r#"class="foot""#),
            "and then has no footer line of them"
        );
        assert!(
            decorate(&base(), &with_layout("compact"), false, false)
                .unwrap()
                .contains(r#"<details class="rules"><summary>House rules</summary><p>No illegal downloads.<br>Be kind.</p></details>"#),
            "compact folds the rules away"
        );
        assert!(decorate(&base(), &with_layout("banner"), false, false).unwrap().contains(r#"<div class="rules"><b>House rules</b>"#));
    }

    #[test]
    fn f049_a_text_with_a_filipino_version_carries_both_and_one_without_stays_plain() {
        let mut b = named("Nena");
        b.set_text(Field::Promo, Lang::En, "Happy hour");
        b.set_text(Field::Promo, Lang::Fil, "Mura ngayon \"po\"");
        b.set_text(Field::Contact, Lang::En, "0917");
        let page = decorate(&base(), &b, false, false).unwrap();
        assert!(
            page.contains(r#"<p class="promo" data-e="Happy hour" data-f="Mura ngayon &amp;quot;po&amp;quot;">Happy hour</p>"#),
            "both versions (the attribute holds markup, so it is escaped twice), English showing first"
        );
        assert!(page.contains(r#"<p class="foot">0917</p>"#), "no Filipino contact: the English one for both, nothing to swap");
        let mut only_fil = named("Nena");
        only_fil.set_text(Field::Promo, Lang::Fil, "Mura ngayon");
        only_fil.set_text(Field::Welcome, Lang::Fil, "Maligayang pagdating");
        let page = decorate(&base(), &only_fil, false, false).unwrap();
        assert!(
            page.contains(r#"<p class="promo" data-e="" data-f="Mura ngayon" hidden></p>"#),
            "a Filipino-only block is hidden in English"
        );
        assert!(
            page.contains(&format!(r#"data-e="{}" data-f="Maligayang pagdating">{}</span>"#, STANDARD, STANDARD)),
            "a Filipino-only welcome: the standard line in English"
        );
    }

    #[test]
    fn f049_the_page_script_is_added_once_after_the_pages_own_and_never_to_an_unbranded_page() {
        let plain = base();
        let page = decorate(&plain, &named("Nena"), false, false).unwrap();
        assert_eq!(count(&page, "<script"), count(&plain, "<script") + 1);
        let ours = page.find("pp-lang").unwrap();
        assert!(
            ours > page.find("var FIRST=").unwrap() && page[ours..].contains("</script></body></html>") && count(&page, "pp-lang") >= 1
        );
        assert!(page.contains("Magpasok ng Barya"), "the Filipino wording travels with the page");
        assert!(!plain.contains("pp-lang") && !plain.contains("Magpasok"));
    }
    #[test]
    fn f049_the_preview_page_cannot_connect_or_start_a_coin_window() {
        let branded = decorate(&base(), &full(), false, false).unwrap();
        for page in [branded.as_str(), base().as_str()] {
            let v = inert(page).unwrap();
            assert!(!v.contains("connect();setInterval(beat"), "the start-up connection is gone");
            assert!(
                v.contains("function connect(){return;"),
                "and so is every later one (the page asks again when it is shown or comes back online)"
            );
            assert!(
                v.contains(r#"show("idle");"#) && v.contains(".w{pointer-events:none}"),
                "it shows the idle view and nothing on it can be pressed"
            );
            assert!(
                v.contains(r#"e.target.closest(".w")"#) && v.contains("stopPropagation") && v.contains(r#"setAttribute("inert","")"#),
                "not by keyboard or script either"
            );
            assert!(v.contains(r#"id="go""#), "the coin button is there to look at");
        }
        assert!(
            inert(&branded.replace("function connect(){", "function  connect(){")).is_err(),
            "a page that changed shape is not shown half neutralised"
        );
        assert!(inert(&branded.replace("connect();setInterval(beat", "connect(); setInterval(beat")).is_err());
    }
    #[test]
    fn f049_a_page_with_the_shops_name_never_tells_a_customer_to_look_for_a_technet_wifi() {
        let old = "Check you are on the TechNet Wi-Fi.";
        assert_eq!(count(&base(), old), 1, "today's page says it, once (and must go on saying it: it is the golden page)");
        for l in crate::brand::LAYOUTS {
            let page = decorate(&base(), &with_layout(l), true, true).unwrap();
            assert!(
                !page.contains(r#"put("conn","Still connecting. Check you are on the TechNet"#),
                "{}: the shop's own page named TechNet's Wi-Fi",
                l
            );
            assert_eq!(count(&page, r#"put("conn","Still connecting. Check you are on the shop's Wi-Fi.")"#), 1, "{}", l);
        }
        let mut palette_only = Brand::default();
        palette_only.palette = "rosas".into();
        assert!(
            decorate(&base(), &palette_only, false, false).unwrap().contains(old),
            "a shop that kept the TechNet name keeps the TechNet line"
        );
    }

    #[test]
    fn f049_the_preview_still_cuts_the_start_up_out_whatever_the_connection_line_says() {
        for page in [decorate(&base(), &full(), false, false).unwrap(), decorate(&base(), &Brand::default(), false, false).unwrap(), base()]
        {
            let v = inert(&page).unwrap();
            assert!(
                !v.contains(r#"put("conn","Still connecting"#) && !v.contains("},8e3);") && !v.contains("connect();setInterval(beat"),
                "the start-up code, with its 8 second message, is gone"
            );
            assert!(v.contains(r#"show("idle");"#));
        }
    }
    #[test]
    fn f049_the_voucher_file_of_a_shop_names_the_shop_and_not_technet() {
        let page = decorate(&base(), &full(), false, false).unwrap();
        assert!(
            page.contains(r#"<body class="l-classic" data-shop="Aling Nena&#39;s Net Cafe">"#),
            "the page carries the name as an escaped attribute"
        );
        assert_eq!(count(&page, "data-shop="), 1);
        for gone in
            ["TechNet PisoWiFi voucher", "TechNet-PisoWiFi-voucher-", "<title>PisoWiFi voucher</title>", "Join the PisoWiFi network"]
        {
            assert!(!page.contains(gone), "the saved voucher still says {:?}", gone);
        }
        assert!(
            page.contains("<title>'+hx(shop())+' voucher</title>") && page.contains("<p><b>'+hx(shop())+' voucher</b></p>"),
            "the file's title and heading are the shop's name, escaped for HTML"
        );
        assert!(
            page.contains("Join the shop&#39;s Wi-Fi, then") && page.contains(r#"a.download=fname()+"-voucher-""#),
            "and so are its sentence and its file name"
        );
        let mut palette_only = Brand::default();
        palette_only.palette = "rosas".into();
        let kept = decorate(&base(), &palette_only, false, false).unwrap();
        assert!(
            !kept.contains("data-shop") && kept.contains("TechNet PisoWiFi voucher") && kept.contains("TechNet-PisoWiFi-voucher-"),
            "a shop that kept the TechNet name keeps the TechNet file"
        );
        for payload in ["</script><script>1</script>", "\"><img src=x onerror=1>", "'; alert(1); '"] {
            let p = decorate(&base(), &named(payload), false, false).unwrap();
            assert!(!p.contains(payload), "the raw name {:?} reached the page", payload);
            assert_eq!(count(&p, "data-shop="), 1);
        }
    }
}
