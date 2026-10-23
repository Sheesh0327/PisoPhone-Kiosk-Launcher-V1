#ifndef SHOP_BRANDING_H
#define SHOP_BRANDING_H

// The shop's branding: four short settings the owner types on the admin page (a palette, the shop name, the header title and how
// busy the background animation is). The box stores them and hands them to the phones with the config push it already sends;
// the phones draw everything (feature 012). The box never stores or serves a file for this, and never renders anything.
// Pure functions, tested on a PC (host_tests/shop_branding_test.cpp). The phone applies the same caps and cleaning again
// (ShopBranding.kt), so a value that gets past one side is still made safe by the other.

#include <cstring>
#include <string>

namespace shopbranding {

constexpr int MAX_NAME_CHARS = 32;
constexpr int MAX_TITLE_CHARS = 20;
// A character may take up to 3 bytes of a Filipino or Latin name; this keeps the four settings far under 512 bytes of settings memory.
constexpr int MAX_NAME_BYTES = 96;
constexpr int MAX_TITLE_BYTES = 60;

// The palettes compiled into the phone app (BrandPalette.kt). Blank means the default (technet).
inline bool validPalette(const std::string& id) {
    static const char* const IDS[] = {"technet", "dagat", "tropiko", "ginto", "rosas", "berde"};
    if (id.empty()) return true;
    for (const char* known : IDS) {
        if (id == known) return true;
    }
    return false;
}

// The animation density words (AnimationDensity.kt). Blank means normal, which is today's screen.
inline bool validDensity(const std::string& word) {
    static const char* const WORDS[] = {"off", "low", "normal", "high"};
    if (word.empty()) return true;
    for (const char* known : WORDS) {
        if (word == known) return true;
    }
    return false;
}

// Text the owner types that the phone draws: well-formed UTF-8, at most maxChars characters (not bytes) and maxBytes bytes, no
// control characters, newlines, line separators, direction marks or byte order mark, none of | ; " \ < > &, no space at either end.
// Blank is fine: it means "not set".
inline bool validText(const std::string& text, int maxChars, int maxBytes) {
    if (text.empty()) return true;
    if ((int)text.size() > maxBytes) return false;
    if (text[0] == ' ' || text[text.size() - 1] == ' ') return false;
    int chars = 0;
    size_t i = 0;
    while (i < text.size()) {
        unsigned char c = (unsigned char)text[i];
        unsigned long cp = 0;
        size_t len = 0;
        if (c < 0x80) {
            cp = c;
            len = 1;
        } else if (c >= 0xC2 && c <= 0xDF) {
            cp = c & 0x1F;
            len = 2;
        } else if (c >= 0xE0 && c <= 0xEF) {
            cp = c & 0x0F;
            len = 3;
        } else if (c >= 0xF0 && c <= 0xF4) {
            cp = c & 0x07;
            len = 4;
        } else {
            return false; // a stray continuation byte, an overlong lead (C0, C1) or a lead that does not exist
        }
        if (i + len > text.size()) return false;
        for (size_t k = 1; k < len; k++) {
            unsigned char cont = (unsigned char)text[i + k];
            if ((cont & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (cont & 0x3F);
        }
        if (len == 3 && cp < 0x800) return false;                      // overlong
        if (len == 4 && (cp < 0x10000 || cp > 0x10FFFF)) return false; // overlong or beyond Unicode
        if (cp >= 0xD800 && cp <= 0xDFFF) return false;               // a surrogate half
        if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) return false;    // control characters, DEL and the C1 controls
        if (cp >= 0x200B && cp <= 0x200F) return false;               // zero-width and direction marks
        if (cp >= 0x2028 && cp <= 0x202E) return false;               // line and paragraph separators, direction overrides
        if (cp >= 0x2060 && cp <= 0x206F) return false;               // invisible operators and isolates
        if (cp == 0xFEFF) return false;                                // byte order mark
        if (len == 1 && std::strchr("|;\"\\<>&", (int)c) != nullptr) return false;
        chars++;
        i += len;
    }
    return chars <= maxChars;
}

inline bool validName(const std::string& name) {
    return validText(name, MAX_NAME_CHARS, MAX_NAME_BYTES);
}

inline bool validTitle(const std::string& title) {
    return validText(title, MAX_TITLE_CHARS, MAX_TITLE_BYTES);
}

struct Branding {
    // False until the owner saves branding once. A box that was never branded sends the phones nothing, exactly as before.
    bool configured = false;
    std::string palette;
    std::string name;
    std::string title;
    std::string density;
};

// The phone reads the values url-encoded (a space is +, a percent sign %25): the same encoding the rest of the push uses.
inline std::string urlEncode(const std::string& text) {
    static const char* const HEX = "0123456789ABCDEF";
    std::string out;
    for (size_t i = 0; i < text.size(); i++) {
        unsigned char c = (unsigned char)text[i];
        if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '-' || c == '_' || c == '.' ||
            c == '~') {
            out += (char)c;
        } else if (c == ' ') {
            out += '+';
        } else {
            out += '%';
            out += HEX[(c >> 4) & 0xF];
            out += HEX[c & 0xF];
        }
    }
    return out;
}

// The box's push to a phone holds its params in a 256-byte buffer (AuthRequest in Config.h), and the existing push already
// carries the phone's name, slot and PIN in it. So the branding goes in a /config push of its own, and its text must fit that
// buffer with room to spare: a longer one is refused when the owner saves it, never cut in the middle of an escape.
constexpr int MAX_PUSH_CHARS = 240;

// The params of the branding push: the four optional params, or nothing for a box nobody branded. Once the owner has saved,
// all four go every time, blank ones included, so clearing a name on the box clears it on the phones too. A phone built before
// this feature ignores params it does not know.
inline std::string pushBody(const Branding& b) {
    if (!b.configured) return "";
    return "palette=" + urlEncode(b.palette) + "&shop_name=" + urlEncode(b.name) + "&brand_title=" + urlEncode(b.title) +
           "&anim_density=" + urlEncode(b.density);
}

// True when the branding push fits the buffer. A name made of plain letters always does; one of many accented characters may not.
inline bool fitsInPush(const Branding& b) {
    Branding asSaved = b;
    asSaved.configured = true;
    return (int)pushBody(asSaved).size() <= MAX_PUSH_CHARS;
}

// Why the admin page refuses a branding, as the code its message is chosen by; "" when it is good. Nothing is saved on a refusal.
inline const char* refusalFor(const Branding& b) {
    if (!validPalette(b.palette)) return "BAD_PALETTE";
    if (!validDensity(b.density)) return "BAD_DENSITY";
    if (!validName(b.name)) return "BAD_NAME";
    if (!validTitle(b.title)) return "BAD_TITLE";
    if (!fitsInPush(b)) return "TOO_LONG_FOR_PHONES";
    return "";
}

// The settings memory (NVS) an entry takes for a string of this many bytes: a 32-byte header, then the bytes and a terminator
// rounded up to 32.
inline int nvsBytes(int stringLength) {
    return 32 + ((stringLength + 1 + 31) / 32) * 32;
}

} // namespace shopbranding

#endif
