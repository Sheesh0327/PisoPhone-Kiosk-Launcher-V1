# Plan: a fresh page never sees the last result first (feature 025)

Context manifest (constitution rule 1). Unresolved ledger entries loaded: none.

| file | purpose | lines read | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/http.rs` | the page's WebSocket: sender thread and `hello` | 380 to 500 | 1,800 |
| `tools/pisoportal/tests/harness.py` | `Cust`, the test customer page | 199 to 260 | 700 |
| `tools/pisoportal/tests/test_flow.py` | the flaky check, line ~581 | 570 to 585 | 400 |

## Task groups
- [ ] 1. **Red test.** `harness.py`: `Cust(send_hello=False)` plus `hello(reset)`. `test_flow.py`: the deterministic scenario of criterion 1 next to the flaky check. Log the red result. (red result logged: no)
- [ ] 2. **The fix** in `http.rs`: an `AtomicBool` the `hello` handler sets after `clear_result` and `reconnect`, before it computes its own view; the sender thread skips the view (not the ping) until it is set.
- [ ] 3. Green: the new test, `test_flow.py` ten times, `test_console.py`, `cargo test`, clippy, rustfmt, the router setup tests, security rules.
- [ ] 4. `setup/RELEASE` 1.5.21, `build_piso_setup.py`, `CHANGELOG.md`, `validation.md`, STATUS.

## Release
Router 1.5.21 (the programs are built by CI on `beta`/`main`). The firmware and the app are not touched.
