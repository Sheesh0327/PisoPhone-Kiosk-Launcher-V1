#ifndef PISO_CHORE_BOARD_H
#define PISO_CHORE_BOARD_H

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace choreboard {

static constexpr size_t MAX_CHORES = 12;
static constexpr size_t MAX_TITLE_LEN = 32;

enum ChoreStatus : uint8_t { STATUS_TODO = 0, STATUS_DONE = 1, STATUS_APPROVED = 2 };

enum RewardType : uint8_t { REWARD_MINUTES = 0, REWARD_PESOS = 1 };

struct PiggyGoal {
    uint32_t targetPesos = 500;
    char title[MAX_TITLE_LEN + 1] = "Savings Goal";
};

struct ChoreItem {
    uint8_t id = 0;
    char title[MAX_TITLE_LEN + 1] = {0};
    uint8_t rewardType = REWARD_MINUTES;
    uint16_t rewardValue = 0;
    uint8_t status = STATUS_TODO;
    bool repeatDaily = true;
    uint8_t targetSlot = 0; // 0 = all / any
};

struct ChoreBoard {
    uint8_t count = 0;
    uint8_t nextId = 1;
    PiggyGoal piggyGoal;
    ChoreItem chores[MAX_CHORES];
};

inline void sanitizeTitle(const char* in, char* out, size_t outSize) {
    if (!out || outSize == 0) return;
    out[0] = '\0';
    if (!in) return;

    size_t written = 0;
    size_t limit = (outSize - 1 < MAX_TITLE_LEN) ? outSize - 1 : MAX_TITLE_LEN;

    for (size_t i = 0; in[i] != '\0' && written < limit; i++) {
        char c = in[i];
        if (c == '\r' || c == '\n' || c == '|') {
            continue; // strip delimiters
        }
        out[written++] = c;
    }
    out[written] = '\0';
}

inline void initBoard(ChoreBoard* b) {
    if (!b) return;
    b->count = 0;
    b->nextId = 1;
    b->piggyGoal.targetPesos = 500;
    std::strncpy(b->piggyGoal.title, "Savings Goal", MAX_TITLE_LEN);
    b->piggyGoal.title[MAX_TITLE_LEN] = '\0';
    for (size_t i = 0; i < MAX_CHORES; i++) {
        b->chores[i] = ChoreItem();
    }
}

inline ChoreItem* findChore(ChoreBoard* b, uint8_t id) {
    if (!b || id == 0) return nullptr;
    for (size_t i = 0; i < b->count; i++) {
        if (b->chores[i].id == id) {
            return &b->chores[i];
        }
    }
    return nullptr;
}

inline int addChore(ChoreBoard* b, const char* title, uint8_t rewardType, uint16_t rewardValue, bool repeatDaily,
                    uint8_t targetSlot) {
    if (!b || b->count >= MAX_CHORES) {
        return -1;
    }
    uint8_t id = b->nextId++;
    if (b->nextId == 0) b->nextId = 1; // wrap around 1..255

    ChoreItem& item = b->chores[b->count++];
    item.id = id;
    sanitizeTitle(title, item.title, sizeof(item.title));
    item.rewardType = (rewardType == REWARD_PESOS) ? REWARD_PESOS : REWARD_MINUTES;
    item.rewardValue = rewardValue;
    item.status = STATUS_TODO;
    item.repeatDaily = repeatDaily;
    item.targetSlot = targetSlot;
    return id;
}

inline bool markChoreDone(ChoreBoard* b, uint8_t id) {
    ChoreItem* item = findChore(b, id);
    if (!item || item->status != STATUS_TODO) {
        return false;
    }
    item->status = STATUS_DONE;
    return true;
}

inline bool approveChore(ChoreBoard* b, uint8_t id) {
    ChoreItem* item = findChore(b, id);
    if (!item || item->status != STATUS_DONE) {
        return false;
    }
    item->status = STATUS_APPROVED;
    return true;
}

inline bool deleteChore(ChoreBoard* b, uint8_t id) {
    if (!b || id == 0) return false;
    for (size_t i = 0; i < b->count; i++) {
        if (b->chores[i].id == id) {
            for (size_t j = i; j + 1 < b->count; j++) {
                b->chores[j] = b->chores[j + 1];
            }
            b->count--;
            return true;
        }
    }
    return false;
}

inline void resetDailyChores(ChoreBoard* b) {
    if (!b) return;
    for (size_t i = 0; i < b->count; i++) {
        if (b->chores[i].status == STATUS_APPROVED && b->chores[i].repeatDaily) {
            b->chores[i].status = STATUS_TODO;
        }
    }
}

inline void setPiggyGoal(ChoreBoard* b, uint32_t targetPesos, const char* title) {
    if (!b) return;
    b->piggyGoal.targetPesos = (targetPesos > 0) ? targetPesos : 1;
    sanitizeTitle(title, b->piggyGoal.title, sizeof(b->piggyGoal.title));
}

inline size_t serializeBoard(const ChoreBoard* b, char* out, size_t outSize) {
    if (!b || !out || outSize == 0) return 0;
    out[0] = '\0';

    int written =
        std::snprintf(out, outSize, "GOAL|%u|%s\n", (unsigned int)b->piggyGoal.targetPesos, b->piggyGoal.title);
    if (written < 0 || (size_t)written >= outSize) return 0;

    size_t total = (size_t)written;
    for (size_t i = 0; i < b->count; i++) {
        const ChoreItem& c = b->chores[i];
        int n = std::snprintf(out + total, outSize - total, "CHORE|%u|%u|%u|%u|%u|%u|%s\n", c.id, c.rewardType,
                              c.rewardValue, c.status, c.repeatDaily ? 1 : 0, c.targetSlot, c.title);
        if (n < 0 || (size_t)n >= outSize - total) {
            break;
        }
        total += (size_t)n;
    }
    return total;
}

inline bool deserializeBoard(ChoreBoard* b, const char* in) {
    if (!b || !in) return false;
    initBoard(b);

    const char* line = in;
    while (*line != '\0') {
        const char* nextLine = std::strchr(line, '\n');
        size_t lineLen = nextLine ? (size_t)(nextLine - line) : std::strlen(line);
        if (lineLen > 0 && line[lineLen - 1] == '\r') {
            lineLen--;
        }

        if (std::strncmp(line, "GOAL|", 5) == 0) {
            const char* p = line + 5;
            char targetBuf[16] = {0};
            size_t ti = 0;
            while (*p != '|' && *p != '\0' && *p != '\n' && *p != '\r' && ti < sizeof(targetBuf) - 1) {
                targetBuf[ti++] = *p++;
            }
            if (*p == '|') p++;
            char goalTitle[MAX_TITLE_LEN + 1] = {0};
            size_t gi = 0;
            while (*p != '\0' && *p != '\n' && *p != '\r' && gi < MAX_TITLE_LEN) {
                goalTitle[gi++] = *p++;
            }
            uint32_t target = (uint32_t)std::strtoul(targetBuf, nullptr, 10);
            setPiggyGoal(b, target, goalTitle);
        } else if (std::strncmp(line, "CHORE|", 6) == 0) {
            const char* p = line + 6;
            unsigned int id = 0, rewType = 0, rewVal = 0, status = 0, rep = 0, slot = 0;
            char titleBuf[MAX_TITLE_LEN + 1] = {0};
            if (std::sscanf(p, "%u|%u|%u|%u|%u|%u|", &id, &rewType, &rewVal, &status, &rep, &slot) == 6) {
                // Find start of title (after 6th pipe)
                int pipes = 0;
                while (*p != '\0' && *p != '\n' && *p != '\r') {
                    if (*p == '|') {
                        pipes++;
                        if (pipes == 6) {
                            p++;
                            break;
                        }
                    }
                    p++;
                }
                size_t ti = 0;
                while (*p != '\0' && *p != '\n' && *p != '\r' && ti < MAX_TITLE_LEN) {
                    titleBuf[ti++] = *p++;
                }
                if (b->count < MAX_CHORES) {
                    ChoreItem& item = b->chores[b->count++];
                    item.id = (uint8_t)id;
                    item.rewardType = (rewType == REWARD_PESOS) ? REWARD_PESOS : REWARD_MINUTES;
                    item.rewardValue = (uint16_t)rewVal;
                    item.status = (uint8_t)status;
                    item.repeatDaily = (rep != 0);
                    item.targetSlot = (uint8_t)slot;
                    sanitizeTitle(titleBuf, item.title, sizeof(item.title));
                    if (item.id >= b->nextId) {
                        b->nextId = item.id + 1;
                    }
                }
            }
        }

        if (!nextLine) break;
        line = nextLine + 1;
    }
    return true;
}

} // namespace choreboard

#endif // PISO_CHORE_BOARD_H
