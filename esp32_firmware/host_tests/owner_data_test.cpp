#include "../include/OwnerData.h"

#include <cstdio>
#include <map>
#include <string>

static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

// Like the real storage, a value only reads back with the type it was written with.
struct TypedStore {
    std::map<std::string, std::string> strings;
    std::map<std::string, uint32_t> uints;
    std::map<std::string, int32_t> ints;
    bool isKey(const char* k) const { return strings.count(k) || uints.count(k) || ints.count(k); }
    int32_t getInt(const char* k, int32_t d) const {
        auto i = ints.find(k);
        return i == ints.end() ? d : i->second;
    }
    void putInt(const char* k, int32_t v) { ints[k] = v; }
    uint32_t getUInt(const char* k, uint32_t d) const {
        auto i = uints.find(k);
        return i == uints.end() ? d : i->second;
    }
    void putUInt(const char* k, uint32_t v) { uints[k] = v; }
    std::string getString(const char* k, const std::string& d) const {
        auto i = strings.find(k);
        return i == strings.end() ? d : i->second;
    }
    void putString(const char* k, const std::string& v) { strings[k] = v; }
    void clear() {
        strings.clear();
        uints.clear();
        ints.clear();
    }
};

int main() {
    TypedStore s;
    s.putUInt("total_coins", 12345);
    s.putUInt("earn_c", 1234500);
    s.putInt("vendor_split", 35);
    s.putString("sa_salt", "aabb");
    s.putString("sa_hash", "ccdd");
    s.putUInt("sa_iter", 10000);
    s.putUInt("sa_ver", 2);
    s.putString("wifi_ssid", "shop-wifi");
    s.putString("admin_pw", "operatorpw");

    ownerdata::Snapshot snap = ownerdata::capture(s);
    s.clear(); // what Preferences::clear() does in a factory reset
    CHECK(!s.isKey("earn_c"));
    ownerdata::restore(s, snap);

    CHECK(s.getUInt("total_coins", 0) == 12345); // lifetime revenue survives
    CHECK(s.getUInt("earn_c", 0) == 1234500);
    CHECK(s.getInt("vendor_split", 0) == 35); // the vendor split survives
    CHECK(s.getString("sa_hash", "") == "ccdd" && s.getString("sa_salt", "") == "aabb");
    CHECK(s.getUInt("sa_iter", 0) == 10000 && s.getUInt("sa_ver", 0) == 2);
    CHECK(!s.isKey("wifi_ssid") && !s.isKey("admin_pw")); // operator settings are gone

    // a box that never had revenue/super-admin set restores nothing and invents nothing
    TypedStore empty;
    ownerdata::restore(empty, ownerdata::capture(empty));
    CHECK(!empty.isKey("vendor_split") && !empty.isKey("sa_ver"));

    // an incomplete super-admin set (power cut while it was being written) is not carried over
    TypedStore partial;
    partial.putString("sa_hash", "ccdd");
    CHECK(!ownerdata::capture(partial).hasSuperAdmin);

    printf("owner_data: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
