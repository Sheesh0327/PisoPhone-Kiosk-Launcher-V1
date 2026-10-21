# Spec NN: <name> (backlog epic)

Status: **draft | approved | in progress | done**   Written: <date> against `<branch>` at `<commit>`   Related: <specs, docs>

## 1. Goal
Which of the two project rules does it serve (money and time never wrong; only owner-signed software)? What gets better for the shop owner?

## 2. Non-goals
What this will not do, and where that work lives.

## 3. Invariants touched
- Money and time: <how credit, expiry, deduction, the payment queue are kept right, or "not touched">
- Signed software: <OTA, signing, keys: how kept, or "not touched">

## 4. Context manifest
`area | purpose | files | lines | est_tokens` (hand-written source only; generated files not read).

## 5. Schemas
Every stored, signed or wire format this changes, byte for byte. Everything else stays unchanged: say so.

## 6. Work packages
`id | confidence (confirmed / reported / uncertain) | where (file:line) | problem | fix | test | release (none/app/firmware/router/site)`
Each row also goes into `spec_NN_findings.json` (see `finding-row.json`). Group rows into features; one area per feature.

## 7. Acceptance criteria
Numbered (AC1.1 ...), each checkable by a command or an observation.

## 8. Owner decisions
Numbered, one line each, with the recommended answer.

## 9. Definition of done
Every row `fixed`, `verified-on-hardware`, `wont-fix` (reason) or `owner-decision`; CHANGELOG and `specs/RELEASE_STATE.md` updated.
