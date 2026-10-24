# Feature 021: The window's record is written when the box is armed (AO-11)

Status: **approved** (owner, spec 02 section 8, 2026-10-24: AO-11 is its own router feature)   Branch: `feat/021-window-record-at-arm`   Area: router   Release: router 1.5.19 (with 015 and 016)
Backlog rows: AO-11 (`specs/spec_02_findings.json`, found by feature 014's soak)

## Intent
A customer puts in a coin, the box counts it, and in the next fraction of a second the router loses power. Today the router writes the
window's record to flash only when it first sees a coin (the box's event takes about 0.3 s, a poll up to 1 s), so a restart in that
moment finds no record and the coin stays on the box for ever: the customer paid and was never credited. The owner decided to write
the record when the box is armed instead. Serves rule 1 (money and time are never wrong): no coin the box counted is left without a
way to credit it.

## Invariants touched
- **Money and time: touched.** A payment is still credited exactly once, by coin window id (`wid`). What changes: *when* the record
  (`open/<sid>`: device, plan, window id) is written, from the first coin seen to the moment the box is armed, and that a window
  that ends with no coin removes its record. Recovery (`recover()`) is unchanged: it asks the box what it holds for the window id,
  credits it once (`record`), and acknowledges. The record is synced to flash before the first coin can be counted.
- Only owner-signed software: not touched.

## Constraints
- **One small synced write per window, and one delete when it ends empty.** The ledger already appends a row per payment; empty windows
  are the new cost (flash wear: two tiny writes per window). Said plainly so the owner can weigh it.
- The router program only (`tools/pisoportal/src/core.rs`); no change to the box protocol (the "fake-box protocol fix" is later).
- The unit and flow tests for window recovery still pass; `test_soak.py --strict` passes (no stranded coin).

## Success criteria
1. `tests/test_window_record.py` (new): a window is on flash while the box is armed and before any coin was seen; a router killed
   after the box counted a coin but before it saw it (events dropped, a 60 s poll) credits the coin once after the restart, removes
   the record and acknowledges the box; an empty window leaves no record. **Red first** against the old behaviour.
2. `python3 tools/pisoportal/tests/test_soak.py --strict`: 0 pesos stranded in 70 kills including 20 power cuts; every coin credited
   exactly once.
3. `cargo test`, `tests/test_flow.py`, `tests/test_link_loss.py`, `test_soak.py --quick` pass.
4. A hardware check for a power cut at the worst moment is added to `docs/REAL_WORLD_TESTING.md` (RS45).

## Out of scope
The box's protocol; asking the box on start for sessions the router never knew (not needed now); older coins already stranded on a box.

## Owner decisions
Answered (spec 02 section 8): write the window record to flash, synced, when the box is armed.
