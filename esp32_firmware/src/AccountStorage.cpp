// Flash storage for the account table. The file is written whole to a temporary name and then moved over the old one;
// both carry a sequence number and a CRC, so after a power cut the newest intact copy is loaded.
// Writes are rate-limited: routine balance reports only mark the table changed and are saved at most every 30 s;
// the events that must not be lost (sign-in/out, credit, create, delete) are saved at once.

#include "AccountStorage.h"

#include "Config.h"
#include "Diagnostics.h"

#include <SPIFFS.h>
#include <esp_random.h>
#include <new>

static const char* ACCOUNTS_FILE = "/accounts.bin";
static const char* ACCOUNTS_TMP = "/accounts.tmp";
static const uint32_t SAVE_INTERVAL_MS = 30000UL;
static const uint32_t PRUNE_INTERVAL_MS = 3600000UL;

static accounts::AccountTable* table = nullptr; // about 25 KB: on the heap, not in static RAM
static bool dirty = false;
static bool everPruned = false;
static uint32_t lastSaveMs = 0;
static uint32_t lastPruneMs = 0;

bool accountsReady() {
    return table != nullptr;
}

accounts::AccountTable& accountsTable() {
    return *table;
}

uint32_t accountsNowS() {
    uint64_t ms = getCurrentMasterTimeMs();
    return (uint32_t)(ms / 1000ULL);
}

// Reads a whole file into a heap buffer (the caller frees it); nullptr if it is missing or empty.
static uint8_t* readFile(const char* path, size_t& len) {
    len = 0;
    if (!SPIFFS.exists(path)) return nullptr;
    File f = SPIFFS.open(path, "r");
    if (!f) return nullptr;
    size_t n = f.size();
    if (n == 0 || n > 64 * 1024) {
        f.close();
        return nullptr;
    }
    uint8_t* buf = (uint8_t*)malloc(n);
    if (!buf) {
        f.close();
        return nullptr;
    }
    if (f.read(buf, n) != n) {
        free(buf);
        f.close();
        return nullptr;
    }
    f.close();
    len = n;
    return buf;
}

static bool saveNow() {
    if (!table) return false;
    File f = SPIFFS.open(ACCOUNTS_TMP, "w");
    if (!f) {
        diagLog("[ACCT] cannot open %s for writing\n", ACCOUNTS_TMP);
        return false;
    }
    bool ok = true;
    table->serialize([&](const uint8_t* d, size_t n) {
        if (ok && f.write(d, n) != n) ok = false;
    });
    f.close();
    if (!ok) {
        diagLog("[ACCT] write failed; keeping the previous file\n");
        SPIFFS.remove(ACCOUNTS_TMP);
        return false;
    }
    // SPIFFS cannot rename over an existing file. If power fails between these two steps the temporary file is
    // complete and the loader picks it up on the next boot.
    SPIFFS.remove(ACCOUNTS_FILE);
    if (!SPIFFS.rename(ACCOUNTS_TMP, ACCOUNTS_FILE)) {
        diagLog("[ACCT] rename failed; the temporary file will be used on the next boot\n");
        return false;
    }
    dirty = false;
    lastSaveMs = millis();
    return true;
}

void accountsBegin() {
    if (table) return;
    if (!SPIFFS.begin(true)) {
        diagLog("[ACCT] filesystem unavailable; player accounts are off\n");
        return;
    }
    table = new (std::nothrow) accounts::AccountTable();
    if (!table) {
        diagLog("[ACCT] not enough memory for the account table; player accounts are off\n");
        return;
    }

    // Load the intact copy with the higher sequence number (the temporary file wins only if the move was cut short).
    size_t lenA = 0, lenB = 0;
    uint8_t* a = readFile(ACCOUNTS_FILE, lenA);
    uint8_t* b = readFile(ACCOUNTS_TMP, lenB);
    uint32_t seqA = 0, seqB = 0;
    bool hasA = a && accounts::AccountTable::peekSeq(a, lenA, seqA);
    bool hasB = b && accounts::AccountTable::peekSeq(b, lenB, seqB);
    bool loaded = false;
    if (hasB && (!hasA || seqB > seqA))
        loaded = table->deserialize(b, lenB) || (hasA && table->deserialize(a, lenA));
    else if (hasA)
        loaded = table->deserialize(a, lenA) || (hasB && table->deserialize(b, lenB));
    free(a);
    free(b);
    diagLog("[ACCT] %s, %u accounts\n", loaded ? "loaded" : "starting with an empty table", (unsigned)table->count());
    if (loaded && SPIFFS.exists(ACCOUNTS_TMP)) dirty = true; // tidy: rewrite and drop the leftover temporary file
}

void accountsCommit(bool urgent) {
    if (!table) return;
    dirty = true;
    if (urgent) saveNow();
}

void accountsLoop() {
    if (!table) return;
    uint32_t now = millis();
    uint32_t nowS = accountsNowS();
    if (nowS != 0 && (!everPruned || (uint32_t)(now - lastPruneMs) >= PRUNE_INTERVAL_MS)) {
        everPruned = true;
        lastPruneMs = now;
        size_t removed = table->prune(nowS);
        if (removed > 0) {
            diagLog("[ACCT] pruned %u unused accounts\n", (unsigned)removed);
            dirty = true;
        }
    }
    if (dirty && (uint32_t)(now - lastSaveMs) >= SAVE_INTERVAL_MS) saveNow();
}

accounts::Result accountsCreate(const String& username, const String& pin) {
    if (!table) return accounts::Result::INTERNAL;
    uint8_t salt[accounts::SALT_BYTES];
    esp_fill_random(salt, sizeof(salt));
    accounts::Result r = table->create(username.c_str(), pin.c_str(), salt, accountsNowS());
    if (r == accounts::Result::OK) accountsCommit(true);
    return r;
}
