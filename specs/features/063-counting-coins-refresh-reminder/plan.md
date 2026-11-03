# Implementation Plan — Feature 063: "Counting coins" pull-down-to-refresh reminder and stuck-socket recovery

## 1. Context Manifest

The execution agent should load **only** the files in this table:

| File | Why it is needed |
|---|---|
| `specs/features/063-counting-coins-refresh-reminder/spec.md` | Exact before/after code blocks, line numbers, invariants, and success criteria |
| `tools/pisoportal/src/page.html` | Guest captive-portal HTML/JS (`#v-live`, `view()`, `connect()`, `#ldone`/`#lcancel` handlers) |
| `tools/pisoportal/src/brand_fil.js` | Filipino dictionary for branded guest portals |
| `tools/pisoportal/src/http.rs` | `WS_PER_DEVICE` constant and `ws_session` writer-thread TCP shutdown |
| `tools/pisoportal/tests/golden/page_states.json` | Unbranded guest page byte size and SHA-256 golden record |
| `tools/pisoportal/tests/test_page_golden.py` | Golden snapshot test runner |
| `tools/pisoportal/tests/test_page_languages.py` | Branded portal English/Filipino completeness test |
| `tools/pisoportal/tests/test_browser.py` | End-to-end Playwright browser tests (including the 3 s `"Counting coins"` proxy stall test) |
| `website/manual.html` | Chapter 13 troubleshooting row for `"Counting coins"` |
| `docs/REAL_WORLD_TESTING.md` | Hardware test checklist (`RS72`) |
| `setup/RELEASE` | Router release version (`1.5.35` -> `1.5.36`) |
| `specs/STATUS.md` | Project status board |

---

## 2. Ordered Task Groups (Test-First)

### Task Group 1: Server WebSocket slot release & limit in `tools/pisoportal/src/http.rs`
1. Edit `tools/pisoportal/src/http.rs` using **Section 4.3** of `spec.md`:
   - Line 314: change `const WS_PER_DEVICE: usize = 3;` to `const WS_PER_DEVICE: usize = 6;`.
   - Lines 445–447: after `cl2.store(true, Ordering::Relaxed);` in the `ws_session` writer thread, lock `w2` and call `let _ = s.shutdown(Shutdown::Both);` so the reader thread blocked in `ws_read_frame` wakes up immediately and drops its `_page` slot.
2. Add or update a unit test in `tools/pisoportal/src/http.rs` (inside `mod tests`) verifying that per-device WebSocket limit allows up to 6 concurrent connections and rejects the 7th, and that a closed session releases its slot.
3. Run `cargo test` inside `tools/pisoportal` and confirm all Rust unit tests pass.

### Task Group 2: Guest portal `#lstuck` reminder, `cw` guard, and `dn` retry in `tools/pisoportal/src/page.html` & `brand_fil.js`
1. Edit `tools/pisoportal/src/page.html` using the exact blocks in **Section 4.1** of `spec.md`:
   - **Block A (lines 82–93):** Add `<p class="sub" id="lstuck" style="display:none;cursor:pointer">Pull down to refresh if stuck</p>` right after `#ltop`.
   - **Block B (line 122):** Add `,sw=0,dn=0` to the state variable declaration list.
   - **Block C (lines 176–182):** Hide `el("lstuck")` in `wait(p)`, set `dn=1;if(!send({t:"done"}))connect()` in `#ldone.onclick` and `#lcancel.onclick`, and bind `el("rl").onclick=el("lstuck").onclick=function(){location.reload()};`.
   - **Block D (lines 214–253):**
     - In `view(j)`, reset `dn=0` when `s!=="armed"`.
     - Update the `s==="closing"` branch of `cw` and add `sw=setTimeout(function(){el("lstuck").style.display="block"},3e3)` (and clear `cw`/`sw` + hide `#lstuck` when `s!=="closing"`).
     - In `s==="armed"`, if `dn` is set, call `send({t:"done"})`; otherwise reset `el("ldone").disabled=false;el("ldone").textContent="Done"`.
     - In `s==="closing"`, hide `el("ltop").style.display="none"`.
     - In `s==="mismatch"` (line 253), rename local variable `sw` to `sp` so it does not shadow the outer `sw` timer variable inside `view(j)`.
   - **Block E (line 261):** In `w.onopen`, append `;if(dn)send({t:"done"})`.
2. Edit `tools/pisoportal/src/brand_fil.js` using **Section 4.2** of `spec.md`:
   - Add `"Pull down to refresh if stuck":"I-pull down para i-refresh kung na-stuck",` right after `"Counting coins":"Binibilang ang mga barya",`.
3. Update `tools/pisoportal/tests/golden/page_states.json` (`page_bytes` and `page_sha256` on lines 4–5) to match the rendered `page.html` (with `%FIRST%` replaced by `20`).
4. Verify footprint budgets:
   - `tools/pisoportal/src/page.html` `<= 28,672` bytes
   - `tools/pisoportal/src/brand_fil.js` `<= 14,336` bytes
5. Run `cargo test` in `tools/pisoportal` to confirm the embedded assets compile and pass all Rust tests.

### Task Group 3: Browser & language tests (`test_page_golden.py`, `test_page_languages.py`, `test_browser.py`)
1. Update `tools/pisoportal/tests/test_page_languages.py` using **Section 4.5** of `spec.md`:
   - Verify that after 3.2 seconds in `G.CASES["closing"]` in Filipino mode, `#lstuck` is visible, displays `"I-pull down para i-refresh kung na-stuck"`, and introduces zero forbidden English words.
2. Update `tools/pisoportal/tests/test_browser.py` using **Section 4.5** of `spec.md`:
   - In the `"Counting coins"` proxy stall test, assert `#lstuck` is hidden at `1.5 s`, and test that when a page stays in `s === "closing"` past `3.0 s`, `#lstuck` becomes visible with `"Pull down to refresh if stuck"` and hides when leaving `"closing"`.
3. Run the browser tests (where Linux `pisoportal` binary + Playwright are available, or in CI / WSL / container).

### Task Group 4: Documentation, hardware test `RS72`, and release gates
1. Edit `website/manual.html` (line 377) and `docs/REAL_WORLD_TESTING.md` (`RS72`) using **Section 4.6** of `spec.md`.
2. Bump `setup/RELEASE` from `1.5.35` to `1.5.36` and run `python tools/build_piso_setup.py` so `setup/piso-setup.sh` and `setup/piso-setup.sh.sha256` are regenerated for `1.5.36`.
3. Run local verification gates:
   - `python tools/build_piso_setup.py --check`
   - `python scripts/tests/test_manual.py`
   - `python scripts/check_links.py`
   - `python scripts/check_vendored.py`
   - `bash scripts/check_security_rules.sh`
4. Record all results in `specs/features/063-counting-coins-refresh-reminder/validation.md` and update `specs/STATUS.md`.
5. **Router program build note (`beta` workflow):** Because `tools/pisoportal/src/*` and `setup/RELEASE` changed, `scripts/check_router_program.py` (inside `scripts/verify_release.py` and `publish-router.yml`) checks that `router/bin/pisoportal-*` was rebuilt for `1.5.36`. Push the branch to `beta` first so `.github/workflows/router-program.yml` cross-compiles and commits the 5 `router/bin/pisoportal-*` binaries and rebuilt `setup/piso-setup.sh`, then fast-forward `main` from `beta` once green.
