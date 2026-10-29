# Validation: feature 039, turn the owed hardware checks into one ordered list

Branch `feat/039-hardware-check-triage`, from `bf7612f`. Files changed: `docs/REAL_WORLD_TESTING.md` (157 lines added, **0 removed**) and this feature's own folder. No code, no release.
**Result: 7 of 8 criteria pass. Criterion 8 passes for everything this change touched, but two numbers that other files name (RS36, RS37) never had a row; that is reported, not fixed (see criterion 8).**
Nothing was run on hardware; this feature produces a list.

| # | Criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Every RS number appears exactly once in the triage with owed / retired / done | script over the triage tables of the document (sessions 1 to 4 and the closed list) | pass | 50 RS rows in the document; 50 distinct numbers in the triage tables; 0 duplicates, 0 missing, 0 extra. 38 owed (in sessions) + 11 done + 1 retired = 50. Plus 12 older owed rows and 5 unnumbered section 10 lines in the sessions: 55 owed in all, and every unticked `- [ ]` id is in a session (0 missing, 0 extra). |
| 2 | No row deleted, none renumbered | `git diff --numstat bf7612f`; every original line searched in the new file in order; RS lists compared | pass | `157 0 docs/REAL_WORLD_TESTING.md` (157 added, 0 removed); "all 202 original lines present, in order: True (202 -> 359)"; RS rows before and after are the same list, in the same order (below) |
| 3 | Each retired row names the reason and the feature or release | read the closed list | pass | Nothing was retired by this feature. The one retired row, RS34, is unchanged and still names router 1.5.25 and feature 033. The closed list repeats that. |
| 4 | Five sessions, ordered, each with equipment and rough time | read the document | pass | Sessions 1 to 4 each have **Needs** and **Roughly**; session 5 says its equipment, that its time is not known and that no owed row exists for it yet. Money rows come first in each. The times are my estimates and say so. |
| 5 | Still opens with "How to run it"; Issue log, Notes, Baseline intact | `grep -n "^## "`; the all-original-lines check in 2 | pass | The new section sits after "How to run it" and before "## 0. Test setup"; the Issue log, Notes and ideas and Baseline summary sections are byte-for-byte the original lines |
| 6 | Summary at the top of the owed list | read the document | pass | "Summary": 55 owed, 0 retired here, start with session 1 part 1a and why |
| 7 | `check_security_rules.sh` passes; only the document and this feature's files changed | run; `git diff --stat bf7612f` | pass | "security rules: all passed"; changed: `docs/REAL_WORLD_TESTING.md`, `specs/features/039-hardware-check-triage/inventory.md`, this file |
| 8 | Cross-references still resolve | `rg -o "\bRS[0-9]+\b"` over `specs/ docs/ CHANGELOG.md tools/ scripts/ .github/`, compared to the document's rows | **partial** | This change broke none: the set of RS rows is identical. But six numbers named elsewhere have no row, before or after: **RS36, RS37** (named by `011-offline-setup/validation.md`, the only validation file affected; also by 041, CHANGELOG, STATUS, RELEASE_STATE) and **RS40 to RS43** (named by `spec_02_always_on.md` and features 014, 015). Adding rows would be inventing checks, so none was added. They are listed in the document's notes for the owner. |

## The RS numbers, before and after
Before (`bf7612f`) and after, identical, 50 numbers: **RS1 to RS35, RS38, RS39, RS44 to RS56.**
Not in the document, before or after: RS36, RS37, RS40, RS41, RS42, RS43.
Tickable page: `python3 tools/checklist/build.py` gives byte-identical output before and after (108 tests), and `tools/checklist/test_apply.py` says 31 checks, 0 failures, so the new section does not disturb the phone page or the write-back tool.

## Gates (constitution rule 7)
- [x] `bash scripts/check_security_rules.sh`: "security rules: all passed".
- [x] Area check for a docs change: the checklist tools above.
- [ ] Python lint: not applicable, no Python was touched. Generated files: none touched, nothing to regenerate.

## Not tested
No check was run; there is no hardware here and the feature does not run any. No hardware check was added; the new section is a list of existing ones. Unsure points are written in the document ("Notes on this triage") and in `inventory.md`.

## Money and time
Not touched. No code, no release, no `setup/RELEASE` or `PISO_FW_VERSION` change.

## Not done, on purpose
- `specs/STATUS.md` and `specs/RELEASE_STATE.md` were not read in full nor changed (out of scope).
- Features 040, 041 and 043 assume RS numbers (RS54, RS55, "RS36 retired") that the document does not support. Their files were not changed; the document's notes say RS57 is the next free number.
- The commit trailer carries the session's own attribution lines (Claude Sonnet 5.5 and this session's link), as `CLAUDE.md` says the session gives them, not the "Sonnet 4.5" line in the task text.
