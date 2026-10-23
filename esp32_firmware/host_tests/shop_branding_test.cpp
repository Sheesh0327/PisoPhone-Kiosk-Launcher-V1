// The shop's branding on the box (include/ShopBranding.h): the four settings the admin page saves and the optional params the
// box adds to the config push it already sends. The box stores configuration only; the phones draw (feature 012).
#include <cstdio>
#include <string>

#include "../include/ShopBranding.h"

using namespace shopbranding;

static int failures = 0;
#define CHECK(cond, msg)                                                                                               \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL: %s\n", msg);                                                                            \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

static std::string repeat(const std::string& unit, int times) {
    std::string out;
    for (int i = 0; i < times; i++)
        out += unit;
    return out;
}

int main() {
    // the palette list: the six built-in ids, exactly as the phone knows them; blank means the default
    CHECK(validPalette("technet") && validPalette("dagat") && validPalette("tropiko") && validPalette("ginto") &&
              validPalette("rosas") && validPalette("berde"),
          "the six palettes");
    CHECK(validPalette(""), "blank is the default palette");
    CHECK(!validPalette("TECHNET") && !validPalette("nonsense") && !validPalette("rosas ") && !validPalette("../x") &&
              !validPalette("<b>"),
          "an unknown id is refused, not guessed");

    // the density words
    CHECK(validDensity("off") && validDensity("low") && validDensity("normal") && validDensity("high") && validDensity(""),
          "the four density words, or blank");
    CHECK(!validDensity("turbo") && !validDensity("OFF") && !validDensity("0") && !validDensity("high "), "any other word refused");

    // the shop name: 32 characters, not bytes
    CHECK(validName("") && validName("Tindahan ni Mang Juan") && validName("Mang Juan's Tindahan"), "plain names, an apostrophe ok");
    CHECK(validName(repeat("a", 32)) && !validName(repeat("a", 33)), "32 characters at most");
    CHECK(validName(repeat("\xC3\xB1", 32)) && !validName(repeat("\xC3\xB1", 33)), "a character is counted once, not by its bytes");
    CHECK(!validName(repeat("\xF0\x9F\x98\x80", 32)), "32 four-byte characters are too many bytes for the settings memory");
    CHECK(!validName(" Tindahan") && !validName("Tindahan "), "no space at either end");
    CHECK(!validName("a|b") && !validName("a;b") && !validName("a\"b") && !validName("a\\b") && !validName("<b>") && !validName("a&b"),
          "separators and markup refused");
    CHECK(!validName(std::string("a\nb")) && !validName(std::string("a\tb")) && !validName(std::string("a\x01")) &&
              !validName(std::string("a\x7F")) && !validName(std::string("a\r")),
          "control characters and newlines refused");
    CHECK(!validName("a\xC2\x85" "b") && !validName("a\xE2\x80\xAE" "b") && !validName("a\xE2\x80\xA8" "b") && !validName("a\xEF\xBB\xBF"),
          "C1 controls, direction marks, line separators and the byte order mark refused");
    CHECK(!validName("a\x80") && !validName("\xC3") && !validName("\xC0\xAF") && !validName("\xF8\x88\x80\x80\x80") && !validName("\xED\xA0\x80"),
          "broken UTF-8 refused");

    // the header title: 20 characters
    CHECK(validTitle("") && validTitle("Piso Net") && validTitle(repeat("t", 20)) && !validTitle(repeat("t", 21)), "20 characters at most");
    CHECK(!validTitle("a;b") && !validTitle(std::string("a\nb")), "the same rules as the name");

    // what the phone is told: nothing when the owner never set anything
    Branding none;
    CHECK(pushBody(none).empty(), "a box nobody branded sends no branding params at all");

    Branding b;
    b.configured = true;
    b.palette = "rosas";
    b.name = "Mang Juan's Tindahan";
    b.title = "Piso Net";
    b.density = "low";
    CHECK(pushBody(b) == "palette=rosas&shop_name=Mang+Juan%27s+Tindahan&brand_title=Piso+Net&anim_density=low",
          "the four optional params, url-encoded");

    // blank values are sent too once the owner has saved: that is how a cleared name clears the phone
    Branding cleared;
    cleared.configured = true;
    CHECK(pushBody(cleared) == "palette=&shop_name=&brand_title=&anim_density=", "cleared settings are sent blank");

    // the encoding round trip the phone does (+ is a space, %25 a percent sign, %2B a plus, UTF-8 as %XX)
    CHECK(urlEncode("100% + sulit") == "100%25+%2B+sulit", "percent and plus survive");
    CHECK(urlEncode("Ni\xC3\xB1o") == "Ni%C3%B1o", "UTF-8 bytes are percent-encoded");
    CHECK(urlEncode("a-b_c.d~e") == "a-b_c.d~e", "unreserved characters stay");

    // the push buffer on the box holds 256 bytes: the branding push must fit, and a value that would not is refused at save
    CHECK(fitsInPush(b), "a typical branding fits the push buffer");
    Branding longest = b;
    longest.name = repeat("a", 32);
    longest.title = repeat("t", 20);
    longest.palette = "tropiko";
    longest.density = "normal";
    CHECK(fitsInPush(longest), "the longest plain-letter branding fits");
    Branding quotes = longest;
    quotes.name = repeat("'", 32); // every apostrophe becomes %27
    quotes.title = repeat("'", 20);
    CHECK(fitsInPush(quotes), "32 apostrophes and a 20-apostrophe title still fit");
    Branding accents = longest;
    accents.name = repeat("\xC3\xB1", 32); // every n-tilde becomes %C3%B1, six characters each
    accents.title = repeat("\xC3\xB1", 20);
    CHECK(validName(accents.name) && validTitle(accents.title), "the accented values are valid text");
    CHECK(!fitsInPush(accents), "but too long once encoded: refused at save, never cut in the middle of an escape");
    CHECK(pushBody(longest).size() <= (size_t)MAX_PUSH_CHARS && MAX_PUSH_CHARS < 255, "and it is under the 256-byte buffer");

    // the size on the box: four short strings in the settings memory (an NVS string takes a 32-byte header plus its bytes and a
    // terminator rounded up to 32). The worst values the page accepts must stay under 512 bytes.
    int worst = nvsBytes((int)std::string("technet").size()) + nvsBytes(MAX_NAME_BYTES) + nvsBytes(MAX_TITLE_BYTES) + nvsBytes(6);
    int typical = nvsBytes((int)b.palette.size()) + nvsBytes((int)b.name.size()) + nvsBytes((int)b.title.size()) +
                  nvsBytes((int)b.density.size());
    std::printf("settings memory: typical %d bytes, worst case %d bytes (limit 512; name %d + title %d + palette %d + density %d at worst)\n",
                typical, worst, nvsBytes(MAX_NAME_BYTES), nvsBytes(MAX_TITLE_BYTES), nvsBytes(7), nvsBytes(6));
    CHECK(worst < 512, "the four settings together take under 512 bytes at their longest");
    CHECK(nvsBytes(0) == 64 && nvsBytes(31) == 64 && nvsBytes(32) == 96, "the size rule rounds up by 32");

    if (failures == 0) std::printf("shop_branding_test: all checks passed\n");
    return failures == 0 ? 0 : 1;
}
