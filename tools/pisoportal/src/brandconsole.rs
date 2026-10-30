//! The console's Branding screen (feature 049, group 6): what the editor shows, saving the draft, and installing a brand pack. The draft is
//! its own file; the guest page only ever reads the published one, so nothing typed here reaches a customer until Publish.
use crate::brand::{self, hex, Brand, Field, Lang, Which, LAYOUTS, NAME_MAX, PALETTES};
use crate::brandpack::{read_image, Slot};
use crate::util::{jget, json_esc};

/// Everything the Branding screen needs in one answer: the draft being edited (or the live branding when there is no draft yet), whether the
/// page is branded now, whether the draft differs from it, which pictures are installed, and the choices and limits the form offers.
pub fn editor_json(dir: &str) -> String {
    let b = brand::editing(dir);
    let published = brand::load(dir, Which::Published);
    let dirty = b.to_text() != published.as_ref().map(|p| p.to_text()).unwrap_or_default();
    let mut s = format!(
        r#"{{"v":1,"live":"{}","dirty":{},"logo":{},"picture":{},"accent_used":"{}","draft":{{"name":"{}","palette":"{}","accent":"{}","layout":"{}""#,
        if published.is_some() { "branded" } else { "default" },
        dirty,
        read_image(dir, Slot::Logo).is_some(),
        read_image(dir, Slot::Picture).is_some(),
        hex(b.theme().accent),
        json_esc(&b.name),
        json_esc(&b.palette),
        json_esc(&b.accent),
        json_esc(&b.layout)
    );
    for f in Field::ALL {
        for l in Lang::ALL {
            s.push_str(&format!(r#","{}_{}":"{}""#, f.key(), l.key(), json_esc(b.text(f, l))));
        }
    }
    s.push_str(&format!(r#"}},"limits":{{"name":{}"#, NAME_MAX));
    for f in Field::ALL {
        s.push_str(&format!(r#","{}":{}"#, f.key(), f.max()));
    }
    s.push_str(r#"},"palettes":["#);
    s.push_str(
        &PALETTES
            .iter()
            .map(|p| {
                format!(
                    r#"{{"id":"{}","bg":"{}","band":"{}","panel":"{}","accent":"{}"}}"#,
                    p.id,
                    hex(p.bg),
                    hex(p.band),
                    hex(p.panel),
                    hex(p.accent)
                )
            })
            .collect::<Vec<_>>()
            .join(","),
    );
    s.push_str(&format!(r#"],"layouts":[{}]}}"#, LAYOUTS.iter().map(|l| format!("\"{}\"", l)).collect::<Vec<_>>().join(",")));
    s
}

/// Keeps the form as the draft (cleaned: limits, control characters, an accent that shows), and says what had to change. The form is always
/// sent whole, so a body without its fields is refused rather than read as "empty everything".
pub fn save_draft(dir: &str, body: &str) -> Result<Vec<String>, String> {
    if !body.trim_start().starts_with('{') || !body.contains("\"name\"") {
        return Err("that is not the branding form".to_string());
    }
    let mut b = Brand::default();
    (b.name, b.palette, b.accent, b.layout) = (jget(body, "name"), jget(body, "palette"), jget(body, "accent"), jget(body, "layout"));
    for f in Field::ALL {
        for l in Lang::ALL {
            b.set_text(f, l, &jget(body, &format!("{}_{}", f.key(), l.key())));
        }
    }
    let (clean, notes) = b.cleaned();
    brand::save(dir, Which::Draft, &clean).map_err(|e| format!("could not save the draft: {}", e))?;
    Ok(notes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::brand::{self, Which};

    fn tmp(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("brandconsole-test-{}-{}", std::process::id(), name));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d.to_string_lossy().into_owned()
    }

    const FORM: &str = r##"{"name":"Aling \"Nena\"","palette":"dagat","accent":"#ff7a1a","layout":"banner","tagline_en":"Fast","tagline_fil":"Mabilis","welcome_en":"","welcome_fil":"","contact_en":"0917","contact_fil":"","rules_en":"No downloads.\nBe kind.","rules_fil":"","hours_en":"","hours_fil":"","promo_en":"","promo_fil":""}"##;

    #[test]
    fn f049_a_fresh_router_offers_an_empty_editor_with_every_choice_and_limit() {
        let j = editor_json(&tmp("fresh"));
        for part in [
            r#""v":1"#,
            r#""live":"default""#,
            r#""dirty":false"#,
            r#""logo":false,"picture":false"#,
            r#""layouts":["classic","banner","compact","poster"]"#,
            r#""limits":{"name":32,"tagline":60,"welcome":160,"contact":80,"rules":600,"hours":60,"promo":120}"#,
            r##""id":"technet","bg":"#050d22","band":"#0a1b45","panel":"#0c1a3a","accent":"#16c9a4""##,
            r#""tagline_fil":"""#,
        ] {
            assert!(j.contains(part), "missing {} in {}", part, j);
        }
        assert_eq!(j.matches(r#""id":""#).count(), 6, "six palettes");
    }

    #[test]
    fn f049_saving_keeps_a_cleaned_draft_and_leaves_what_customers_see_alone() {
        let dir = tmp("save");
        let notes = save_draft(&dir, FORM).unwrap();
        assert!(notes.is_empty(), "{:?}", notes);
        let d = brand::load(&dir, Which::Draft).unwrap();
        assert_eq!(
            (d.name.as_str(), d.palette.as_str(), d.accent.as_str(), d.layout.as_str()),
            ("Aling \"Nena\"", "dagat", "#ff7a1a", "banner")
        );
        assert_eq!(d.text(brand::Field::Rules, brand::Lang::En), "No downloads.\nBe kind.");
        assert!(brand::load(&dir, Which::Published).is_none(), "a draft is not the page");
        let j = editor_json(&dir);
        assert!(
            j.contains(r#""name":"Aling \"Nena\"""#)
                && j.contains(r#""rules_en":"No downloads.\nBe kind.""#)
                && j.contains(r#""layout":"banner""#),
            "{}",
            j
        );
        assert!(j.contains(r#""live":"default""#) && j.contains(r#""dirty":true"#), "{}", j);
        let long = FORM.replace("Aling \\\"Nena\\\"", &"N".repeat(40)).replace("#ff7a1a", "#050d22").replace("dagat", "technet");
        let notes = save_draft(&dir, &long).unwrap();
        assert!(notes.iter().any(|n| n.contains("shop name was shortened")) && notes.iter().any(|n| n.contains("accent")), "{:?}", notes);
    }

    #[test]
    fn f049_publish_and_restore_move_the_live_flag_and_the_draft_stays() {
        let dir = tmp("live");
        save_draft(&dir, FORM).unwrap();
        brand::publish(&dir).unwrap();
        let j = editor_json(&dir);
        assert!(j.contains(r#""live":"branded""#) && j.contains(r#""dirty":false"#), "{}", j);
        save_draft(&dir, &FORM.replace("banner", "poster")).unwrap();
        assert!(editor_json(&dir).contains(r#""dirty":true"#));
        assert_eq!(brand::load(&dir, Which::Published).unwrap().layout, "banner", "the live page did not follow the draft");
        brand::restore_default(&dir).unwrap();
        let j = editor_json(&dir);
        assert!(j.contains(r#""live":"default""#) && j.contains(r#""layout":"poster""#), "restoring keeps the draft: {}", j);
    }

    #[test]
    fn f049_the_editor_starts_from_what_is_live_and_clearing_everything_is_a_real_save() {
        let dir = tmp("start");
        save_draft(&dir, FORM).unwrap();
        brand::publish(&dir).unwrap();
        std::fs::remove_file(format!("{}/{}/draft.txt", dir, brand::BRAND_DIR)).unwrap();
        assert!(editor_json(&dir).contains(r#""name":"Aling \"Nena\"""#), "no draft: the editor shows the live branding");
        let empty = r#"{"name":"","palette":"","accent":"","layout":""}"#;
        save_draft(&dir, empty).unwrap();
        let j = editor_json(&dir);
        assert!(
            j.contains(r#""name":"""#) && j.contains(r#""dirty":true"#),
            "an emptied form is shown empty, not refilled from the live page: {}",
            j
        );
    }

    #[test]
    fn f049_something_that_is_not_the_form_changes_nothing() {
        let dir = tmp("junk");
        save_draft(&dir, FORM).unwrap();
        let before = std::fs::read(format!("{}/{}/draft.txt", dir, brand::BRAND_DIR)).unwrap();
        for junk in ["", "hello", "[]", r#"{"layout":"poster"}"#] {
            assert!(save_draft(&dir, junk).is_err(), "{:?}", junk);
        }
        assert_eq!(std::fs::read(format!("{}/{}/draft.txt", dir, brand::BRAND_DIR)).unwrap(), before);
    }
}
