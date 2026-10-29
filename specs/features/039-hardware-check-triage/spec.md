# Feature 039: turn the owed hardware checks into one ordered list

Status: **approved by the owner 2026-10-28 (epic D15).** Can run at any time, in parallel with anything.
Branch: `feat/039-hardware-check-triage`   Area: docs   Epic: `specs/spec_03_overhaul.md` feature 039
Release: **none** — this feature changes no software.

## Intent
`docs/REAL_WORLD_TESTING.md` carries about 50 `RS` checks, most of them never run, written at different times against
releases that have since been replaced. The epic's own measure of the product's weakness is this list: the correctness story
is verified on a computer, the competition's is verified by attrition in shops. The list is not the problem — **the list being
unordered and partly obsolete is**, because that is what stops the owner working through it.

This feature writes no code. It reads every owed check, closes the ones later releases made pointless, and leaves **one
ordered list the owner can work through a session at a time**, each session being a set of checks that need the same
equipment set up once.

## Invariants touched
- **Money and time:** not touched. No code, no release.
- **Only owner-signed software:** not touched.

## Constraints
- **`docs/REAL_WORLD_TESTING.md` must not move or be renamed** — workflows and the signed router script read it by path
  (`CLAUDE.md` section 5).
- **Never delete a row.** Retire it in place, the way feature 033 retired RS34: keep the steps, mark it, say why and in which
  release. The log's value is that it stays readable backwards.
- **Never renumber a row.** `RS28` means `RS28` in commit messages, validation files and the owner's memory. New rows take
  the next free number (RS53, RS54 and RS55 are already claimed by features 032, 040 and 041).
- Do not invent checks. If a gap is obvious, note it at the end as a suggestion; do not add it to the ordered list as though
  it were owed.
- Do not change what a check asks for. Reordering and retiring only; if a check looks wrong, say so in the notes.

## What "obsolete" means here
A check may be retired only for a reason you can point at:
1. **The thing it tested is gone** (RS34, the spinning wheel, feature 033 — already done; the mini-game after feature 034;
   the offline package path after feature 041).
2. **A later check covers the same ground** on a newer release — name the check that supersedes it.
3. **The code it tested was replaced**, not merely changed, and the new code has its own check.
Anything else stays owed, including checks that are old, boring, or awkward to run. "Probably fine" is not a reason.

## The ordering rule
The list is ordered by **what the owner can do in one sitting**, not by number and not by release. A session is a set of
checks that need the same equipment set up once. Group them in this order, which follows the epic's priorities:
1. **Box on the bench** (a box, USB, a phone): the firmware money rows. These need the least setting up and carry the
   heaviest money risk.
2. **Box plus phone in a shop**: pairing, the lock screen, paid time, the kiosk behaviours.
3. **Router** (a router, a box, a guest phone): the PisoWiFi path, coin windows, vouchers, isolation.
4. **Whole site**: the checks that need a box, a router, a rental phone and a guest phone at once.
5. **Hardened box** (feature 040's bench work and feature 043's checks): a sacrificial board and the eFuse steps.

Within each group, money checks come before everything else.

**The owner's rule of 2026-10-28 (epic D30) decides what is at the top:** the **legacy, field-proven build is the default and
stays selectable**; hardening is an option. So group 5 is **last**, and nothing in groups 1 to 4 may be made to depend on a
hardened box.

## Success criteria
Each becomes a line in `validation.md`.
1. Every `RS` number in `docs/REAL_WORLD_TESTING.md` appears exactly once in the triage, with one of: **owed**, **retired**
   (with the reason and the release), or **done** (with where the evidence is). A count that matches the number of rows.
2. No row is deleted and no row is renumbered. `git diff` shows this: prove it by listing every `RS` number before and after.
3. Each retired row names the reason and the feature or release that retired it.
4. The owed checks are grouped into the five sessions above, ordered, with each session saying **what equipment it needs** and
   roughly how long it takes, so the owner can decide to do one on a given evening.
5. The document still opens with its existing "How to run it" section and keeps its Issue log, Notes and Baseline summary
   sections intact.
6. A short summary at the top of the owed list: how many checks are owed, how many were retired here, and which session the
   owner should do first.
7. `bash scripts/check_security_rules.sh` passes and `git diff --stat` shows **only** `docs/REAL_WORLD_TESTING.md` and this
   feature's own files changed.
8. Cross-references still work: every feature `validation.md` that names an `RS` number still names one that exists
   (`rg -o "RS[0-9]+" specs/ docs/` and check each against the document).

## Out of scope
- Running any check. This feature produces a list; the owner runs it.
- Adding new checks, except the suggestions section at the end.
- Any change to `specs/STATUS.md` or `specs/RELEASE_STATE.md` — they are stale for other reasons and the epic sends that to
  the next replan (D16).
- Changing the wording of what a check asks for.

## Owner decisions
None open. **D15** approved the feature; **D30** fixes hardening as the last group.
