# Spec NN: <name>

Status: **draft | approved | in progress | done**
Written: <date>, against `<branch>` at `<commit>`
Related: `specs/mission.md`, `specs/roadmap.md`, <other docs>

## 1. Goal
One paragraph. Which of the two project rules does it serve?

## 2. Non-goals
What this spec will not do, and where that work lives instead.

## 3. Context manifest
| area | purpose | files | lines | est_tokens |
|---|---|---|---|---|

Unresolved ledger entries loaded: <none | list>.

## 4. How sure are we
Which claims were checked in the source (`confirmed`) and which are `reported` or uncertain (U).

## 5. Data schemas
Every stored, signed or wire format this spec changes, byte for byte. Everything else stays unchanged; say so.

## 6. Work packages
Each package: id, where, problem, fix, test (fails before, passes after), release type (`none | app | firmware | router | site`).
One area per pull request.

**Acceptance criteria**: numbered, checkable (AC1.1 ...).

## 7. Verification gates
The checks from `CLAUDE.md` section 3 and `CONTRIBUTING.md`; constitution rule 7; generated files regenerated; hardware checks added but not run are listed as **not tested**.

## 8. Owner decisions needed
Numbered, one line each.

## 9. Definition of done
- Every row `fixed`, `verified-on-hardware`, `wont-fix` (reason) or `owner-decision`.
- `CHANGELOG.md` and `CLAUDE.md` section 2 updated.
