// Chore Board & Piggy Goal rules for PisoHome (include/ChoreBoard.h, feature 064).
#include <cstdio>
#include <cstring>
#include <string>

#include "../include/ChoreBoard.h"

using namespace choreboard;

static int failures = 0;
#define CHECK(cond, msg)                                                                                               \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL: %s\n", msg);                                                                            \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    ChoreBoard board;
    initBoard(&board);

    CHECK(board.count == 0, "initial chore count is 0");
    CHECK(board.piggyGoal.targetPesos == 500, "default piggy target is 500");
    CHECK(std::string(board.piggyGoal.title) == "Savings Goal", "default piggy title");

    // 1. Add chores
    int id1 = addChore(&board, "Wash the dishes", REWARD_MINUTES, 20, true, 0);
    CHECK(id1 > 0, "chore 1 added with positive ID");
    CHECK(board.count == 1, "count is 1");
    CHECK(std::string(board.chores[0].title) == "Wash the dishes", "title matches");
    CHECK(board.chores[0].rewardType == REWARD_MINUTES, "reward type is minutes");
    CHECK(board.chores[0].rewardValue == 20, "reward value is 20");
    CHECK(board.chores[0].status == STATUS_TODO, "status starts as TODO");
    CHECK(board.chores[0].repeatDaily == true, "repeatDaily is true");

    int id2 = addChore(&board, "Clean your room", REWARD_PESOS, 10, false, 1);
    CHECK(id2 > 0 && id2 != id1, "chore 2 added with unique ID");
    CHECK(board.count == 2, "count is 2");
    CHECK(board.chores[1].rewardType == REWARD_PESOS, "reward type is pesos");
    CHECK(board.chores[1].targetSlot == 1, "target slot is 1");
    CHECK(board.chores[1].repeatDaily == false, "repeatDaily is false");

    // 2. Title sanitization (strips newlines, carriage returns, pipe characters, caps length at 32)
    int id3 = addChore(&board, "Read 30 mins\n| Do Math\r", REWARD_MINUTES, 30, true, 0);
    CHECK(id3 > 0, "chore 3 added");
    CHECK(std::string(board.chores[2].title) == "Read 30 mins Do Math", "delimiters stripped from title");

    // 3. Status transitions
    // Todo -> Done ("I Did It!")
    CHECK(markChoreDone(&board, id1), "mark chore 1 done succeeds");
    ChoreItem* c1 = findChore(&board, id1);
    CHECK(c1 != nullptr && c1->status == STATUS_DONE, "chore 1 is now DONE");

    // Cannot re-mark DONE if already DONE
    CHECK(!markChoreDone(&board, id1), "cannot mark done again when already DONE");

    // Done -> Approved ("Parent Approves")
    CHECK(approveChore(&board, id1), "approve chore 1 succeeds");
    CHECK(c1->status == STATUS_APPROVED, "chore 1 is now APPROVED");

    // 4. Midnight Daily Reset (D14)
    // Daily chore (id1) is APPROVED -> reverts to TODO
    // One-time chore (id2) is TODO -> stays TODO
    resetDailyChores(&board);
    CHECK(c1->status == STATUS_TODO, "daily repeating chore reverts to TODO after daily reset");

    // Now mark id2 done and approved
    markChoreDone(&board, id2);
    approveChore(&board, id2);
    ChoreItem* c2 = findChore(&board, id2);
    CHECK(c2->status == STATUS_APPROVED, "chore 2 approved");

    // Daily reset on one-time quest: remains APPROVED
    resetDailyChores(&board);
    CHECK(c2->status == STATUS_APPROVED, "one-time quest remains APPROVED after daily reset");

    // 5. Delete chore
    CHECK(deleteChore(&board, id3), "delete chore 3 succeeds");
    CHECK(board.count == 2, "count decreases to 2");
    CHECK(findChore(&board, id3) == nullptr, "chore 3 not found");

    // 6. Max chore capacity (MAX_CHORES = 12)
    while (board.count < MAX_CHORES) {
        addChore(&board, "Extra chore", REWARD_MINUTES, 5, true, 0);
    }
    CHECK(board.count == MAX_CHORES, "board is at max capacity 12");
    CHECK(addChore(&board, "One too many", REWARD_MINUTES, 5, true, 0) == -1, "rejects 13th chore");

    // 7. Piggy Bank Goal
    setPiggyGoal(&board, 1000, "Weekend Pizza & Ice Cream\n");
    CHECK(board.piggyGoal.targetPesos == 1000, "goal target is 1000");
    CHECK(std::string(board.piggyGoal.title) == "Weekend Pizza & Ice Cream", "goal title sanitized");

    // 8. Serialization roundtrip
    char serialized[1024];
    size_t len = serializeBoard(&board, serialized, sizeof(serialized));
    CHECK(len > 0, "serialized string is not empty");

    ChoreBoard restored;
    initBoard(&restored);
    CHECK(deserializeBoard(&restored, serialized), "deserialization succeeds");
    CHECK(restored.count == board.count, "restored count matches");
    CHECK(restored.piggyGoal.targetPesos == 1000, "restored goal target matches");
    CHECK(std::string(restored.piggyGoal.title) == "Weekend Pizza & Ice Cream", "restored goal title matches");
    CHECK(std::string(restored.chores[0].title) == std::string(board.chores[0].title), "restored chore 0 title matches");
    CHECK(restored.chores[0].rewardValue == board.chores[0].rewardValue, "restored chore 0 reward matches");

    if (failures > 0) {
        std::printf("TOTAL FAILURES: %d\n", failures);
        return 1;
    }
    std::printf("OK: all chore board checks passed\n");
    return 0;
}
