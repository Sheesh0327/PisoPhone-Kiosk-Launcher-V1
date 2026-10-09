// Flash storage for the account table. This is the only copy of players' saved time, so it is written defensively:
//  - The file is written whole to a temporary name, read back and checked, and only then moved into place; the previous
//    good file is kept as a backup. Every file carries a sequence number and a CRC, so after a power cut at any moment the
//    newest intact copy of the three is loaded.
//  - The filesystem is formatted only the very first time it is used. If it later refuses to mount, accounts are switched
//    off and nothing is erased: the data may still be recoverable, and formatting would destroy it.
//  - Writes are rate-limited (routine balance reports are saved at most every 30 s; sign-in/out, credit, create and delete
//    at once) and failures are counted and reported in the diagnostics instead of being retried silently.

#include "AccountStorage.h"

#include "Config.h"
#include "Diagnostics.h"

#include <Preferences.h>
#include <SPIFFS.h>
#include <new>

static const char* ACCOUNTS_FILE = "/accounts.bin";
static const char* ACCOUNTS_TMP = "/accounts.tmp";
static const char* ACCOUNTS_BAK = "/accounts.bak";
static const uint32_t SAVE_INTERVAL_MS = 30000UL;
static const uint32_t PRUNE_INTERVAL_MS = 3600000UL;

static accounts::AccountTable* table = nullptr; // about 25 KB: on the heap, not in static RAM
static bool dirty = false;
static bool everPruned = false;
static uint32_t lastSaveMs = 0;
static uint32_t lastPruneMs = 0;
static bool fsMounted = false;
static uint32_t saveFailures = 0;
static uint32_t consecutiveFailures = 0;
static uint32_t savesOk = 0;

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

AccountStorageHealth accountsHealth() {
    AccountStorageHealth h;
    h.ready = table != nullptr;
    h.fsMounted = fsMounted;
    h.accounts = table ? table->count() : 0;
    h.dirty = dirty;
    h.saveFailures = saveFailures;
    h.consecutiveFailures = consecutiveFailures;
    h.savesOk = savesOk;
    return h;
}

// Mounts the filesystem. A partition that was never set up is formatted once (nothing to lose). After that a failed mount
// is NOT answered with a format: the accounts may still be on flash, so accounts stay off until an admin decides.
static bool mountFilesystem() {
    if (SPIFFS.begin(false)) return true;
    Preferences marker;
    if (!marker.begin("acct_fs", false)) return false;
    bool setUpBefore = marker.getBool("formatted", false);
    bool ok = false;
    if (!setUpBefore) {
        ok = SPIFFS.begin(true);
        if (ok) marker.putBool("formatted", true);
        diagLog("[ACCT] first use of the accounts partition: formatted it (%s)\n", ok ? "ok" : "FAILED");
    } else {
        diagLog(
            "[ACCT] the filesystem will not mount although it was set up before. NOT formatting it (the accounts may "
            "still be there). Player accounts are off; a factory reset sets the partition up again.\n");
    }
    marker.end();
    return ok;
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

static void noteSaveFailure(const char* why) {
    saveFailures++;
    consecutiveFailures++;
    // The first failure and then every tenth: enough to see it, not enough to flood the log (a save is retried every 30 s).
    if (consecutiveFailures == 1 || consecutiveFailures % 10 == 0) {
        diagLog(
            "[ACCT] SAVE FAILED (%s), %u in a row; the accounts are still safe in RAM and the older copy on flash\n",
            why, (unsigned)consecutiveFailures);
    }
}

static bool saveNow() {
    if (!table) return false;
    File f = SPIFFS.open(ACCOUNTS_TMP, "w");
    if (!f) {
        noteSaveFailure("cannot open the temporary file");
        return false;
    }
    bool ok = true;
    size_t written = 0;
    table->serialize([&](const uint8_t* d, size_t n) {
        if (ok && f.write(d, n) != n) ok = false;
        written += n;
    });
    f.close();
    if (!ok) {
        SPIFFS.remove(ACCOUNTS_TMP);
        noteSaveFailure("write failed (flash full?)");
        return false;
    }
    // Read it back: a file that cannot be read and verified must never replace a good one.
    size_t len = 0;
    uint8_t* check = readFile(ACCOUNTS_TMP, len);
    uint32_t seq = 0;
    bool verified = check && len == written && accounts::AccountTable::peekSeq(check, len, seq);
    free(check);
    if (!verified) {
        SPIFFS.remove(ACCOUNTS_TMP);
        noteSaveFailure("read-back check failed");
        return false;
    }
    // Keep the previous good file as the backup, then move the new one into place. SPIFFS cannot rename over an existing
    // file, so there are moments with no main file; the loader accepts any of the three, newest first.
    SPIFFS.remove(ACCOUNTS_BAK);
    if (SPIFFS.exists(ACCOUNTS_FILE)) SPIFFS.rename(ACCOUNTS_FILE, ACCOUNTS_BAK);
    if (!SPIFFS.rename(ACCOUNTS_TMP, ACCOUNTS_FILE)) {
        noteSaveFailure("rename failed; the verified temporary file will be used on the next boot");
        return false;
    }
    dirty = false;
    consecutiveFailures = 0;
    savesOk++;
    lastSaveMs = millis();
    return true;
}

void accountsBegin() {
    if (table) return;
    fsMounted = mountFilesystem();
    if (!fsMounted) {
        diagLog("[ACCT] filesystem unavailable; player accounts are off\n");
        return;
    }
    table = new (std::nothrow) accounts::AccountTable();
    if (!table) {
        diagLog("[ACCT] not enough memory for the account table; player accounts are off\n");
        return;
    }

    // Load the intact copy with the highest sequence number; if that one turns out damaged, the next newest.
    const char* paths[3] = {ACCOUNTS_FILE, ACCOUNTS_TMP, ACCOUNTS_BAK};
    uint8_t* bufs[3] = {nullptr, nullptr, nullptr};
    size_t lens[3] = {0, 0, 0};
    uint32_t seqs[3] = {0, 0, 0};
    bool valid[3] = {false, false, false};
    for (int i = 0; i < 3; i++) {
        bufs[i] = readFile(paths[i], lens[i]);
        valid[i] = bufs[i] && accounts::AccountTable::peekSeq(bufs[i], lens[i], seqs[i]);
    }
    bool loaded = false;
    int loadedFrom = -1;
    bool tried[3] = {false, false, false};
    for (int round = 0; round < 3 && !loaded; round++) {
        int best = -1;
        for (int i = 0; i < 3; i++)
            if (valid[i] && !tried[i] && (best < 0 || seqs[i] > seqs[best])) best = i;
        if (best < 0) break;
        tried[best] = true;
        loaded = table->deserialize(bufs[best], lens[best]);
        if (loaded) loadedFrom = best;
    }
    for (int i = 0; i < 3; i++)
        free(bufs[i]);
    diagLog("[ACCT] %s%s, %u accounts\n", loaded ? "loaded from " : "starting with an empty table",
            loaded ? paths[loadedFrom] : "", (unsigned)table->count());
    // Anything but a clean main file means the last save was cut short: write a fresh, complete set.
    if (loaded && loadedFrom != 0) dirty = true;
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
    if (dirty && (uint32_t)(now - lastSaveMs) >= SAVE_INTERVAL_MS) {
        saveNow();
        lastSaveMs = now; // also after a failure: retry every 30 s, not on every pass of the loop
    }
}
