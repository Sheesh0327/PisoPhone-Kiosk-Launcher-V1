# Plan: the wheel button for everyone

Context manifest (rule 1): read line ranges only. Unresolved ledger entries loaded: the 2026-10-22 owner report (this feature fixes it).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/core.rs` | `View::Idle`/`View::Final` (fields, `to_json`, `view_for` ~ line 400-445), `wheel_changed` | ranges | ~2k |
| `tools/pisoportal/src/wheel_run.rs` | `state()` (no-session answer) and its unit tests | ~300 | ~3k |
| `tools/pisoportal/src/wheel.html` | the page: odds for everyone, disabled Spin, "Buy Wi-Fi time first" | ~110 | ~2k |
| `tools/pisoportal/src/page.html` | the idle block and the final block (button placement) | ranges | ~1k |
| `tools/pisoportal/src/console.html` | one line under the wheel switch | ranges | ~0.3k |
| `tools/pisoportal/tests/test_wheel.py`, `test_wheel_browser.py` | end to end and browser tests to extend | ~300 | ~4k |
| `docs/REAL_WORLD_TESTING.md`, `CHANGELOG.md`, `website/manual.html`, `setup/RELEASE` | docs and release | ranges | ~1k |

## Task groups
Small, ordered, test first (the red result goes to `ledger/negative-constraints.md`).

- [x] 1. **State for a device without time** (`wheel_run.rs::state`): 200 with the odds and `can_spin: false`, reason NO_SESSION; off still 404. Unit tests first (the existing "no paid time" test expects 403: change it with the spec).
- [x] 2. **The views** (`core.rs`): `wh` true for every device while the wheel is on (idle) and the final view gets `wh`; unit test of `to_json`/`view_for` first.
- [x] 3. **The coin page** (`page.html`): the button in the final block too; the idle block shows it for `wh`. `test_wheel.py`: unpaid visitor, then a real coin payment (the harness script in the scratchpad is the model).
- [x] 4. **The wheel page** (`wheel.html`): odds and wheel for everyone, "Buy Wi-Fi time first, then you can spin", Spin disabled; browser test (`test_wheel_browser.py`) first.
- [x] 5. **Console line + docs + release**: console text, `setup/RELEASE` 1.5.15, `build_piso_setup.py`, CHANGELOG, manual, RS34 wording, `validation.md`.
- [ ] 6. **Pull request into `beta`** (when the owner asks); Code quality; merge and rollout are the owner's.

## Release
Router only (`setup/RELEASE` 1.5.14 to 1.5.15). Generated files are regenerated (`build_piso_setup.py`; CI rebuilds the router programs). 1.5.14 stays live at 100% until 1.5.15 is published and the owner widens it.
