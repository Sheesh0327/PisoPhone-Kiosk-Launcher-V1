# Plan: feature 039, turn the owed hardware checks into one ordered list

Context manifest (constitution rule 1). Unresolved ledger entries loaded: load them first (rule 6).

| file | purpose | how to read |
|---|---|---|
| `docs/REAL_WORLD_TESTING.md` | the subject | 202 lines, **read in full** — this is the one file this feature is about |
| `specs/RELEASE_STATE.md` | which releases are live, so you can tell what a check still applies to | read the top section only; it is stale in places, so trust the code and `CHANGELOG.md` over it |
| `CHANGELOG.md` | what each release changed, for judging whether a check was superseded | grep by release number |
| `specs/features/*/validation.md` | which checks a feature claimed, and which it owed | `rg -n "RS[0-9]+" specs/features/` — do not open them all |

Read nothing else. No source code needs reading for this feature.

## Task groups
- [ ] **1. The inventory.** `rg -o "RS[0-9]+" docs/REAL_WORLD_TESTING.md | sort -u` and the same across `specs/`. Write the
      full list with, for each: its release, its current checkbox state, and where else it is referenced. This is the
      "before" for criteria 1, 2 and 8. No edits yet.
- [ ] **2. Judge each one.** owed / retired / done, with the reason. Use only the three reasons in the spec's "What
      obsolete means here". When unsure, it stays **owed** — that is the safe direction, and say in the notes that you were
      unsure. Expect the mini-game checks to become retirable only once feature 034 lands, and the offline-setup check RS36
      only once 041 lands: if those have not merged yet, leave them owed and say so.
- [ ] **3. Group and order.** The five sessions from the spec. Each gets: what equipment, roughly how long, and the checks in
      order with money first. Criterion 4.
- [ ] **4. Write it into the document.** Retire rows in place in feature 033's style (RS34 is the worked example, read it
      first). Add the ordered list and the summary at the top of it. Keep every other section intact. Criteria 3, 5, 6.
- [ ] **5. Check nothing broke.** Criteria 1, 2, 7, 8: the before/after `RS` lists identical as sets, no renumbering, only the
      expected files changed, every cross-reference still resolves.

## Release
**None.** No `setup/RELEASE`, no `PISO_FW_VERSION`, no app build, nothing published. This feature ships nothing to any device.

## Hardware check
None, and that is the point: this feature exists so the owner can run the ones already owed.
