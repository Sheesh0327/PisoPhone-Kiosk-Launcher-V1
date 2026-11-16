# PisoWiFi Payment → openNDS Grant Audit Findings

**Scope:** `tools/pisoportal/src/` (`core.rs`, `nds.rs`, `roll.rs`, `fair.rs`, `http.rs`)  
**Focus:** The critical path from coin/voucher payment settlement to `ndsctl` internet access grant.

---

## 1. Critical (`RT-01` Bypass): `settle()` and `redeem()` do not call `check_clock()` before `roll::mint()`, wiping existing paid time on an NTP jump

- **Where:**
  - `tools/pisoportal/src/core.rs`: `Core::settle` (lines 1156–1165), `Core::redeem` (lines 636–648), `Core::check_clock` (lines 1567–1593)
  - `tools/pisoportal/src/roll.rs`: `roll::mint` (lines 196–208), `roll::session` (line 249)
- **Mechanism:**
  1. After a power outage, the router boots with a stale pre-NTP clock (`t = S0`). A customer pays for 60 minutes (`fp = S0`, `rm = 60`).
  2. The customer opens a top-up window (or redeems a voucher) just as `ntpd` steps the system clock forward (e.g., `t = S0 + 86400`), **before** the 60-second `fair::tick` or `maintenance` loop runs `Core::check_clock()`.
  3. `Core::settle` calls `self.record(..., now_secs())` → `roll::mint()` directly with the newly jumped `now_secs()` without calling `self.check_clock()` first.
  4. Inside `roll::mint()`, `e.left(now)` compares the jumped `now` against the unshifted `e.fp`, sees `e.left(now) == 0`, and executes `entries.remove(i)` — permanently deleting the customer's first session before `check_clock()` ever gets to shift `fp` forward. (Note the comment in `roll::session` at line 249 explicitly warning about `RT-01`, which `mint()` violates.)
- **Recommended Fix:** Call `self.check_clock()` (or run the clock-shift check inside the same `self.files` critical section) before `roll::mint()` in both `settle()` and `redeem()`.

---

## 2. High: Unsynchronized `Nds::grant()` causes `deauth`/`auth` interleaving across concurrent threads

- **Where:**
  - `tools/pisoportal/src/nds.rs`: `Nds::grant` (lines 103–111)
  - `tools/pisoportal/src/core.rs`: `Core::settle` (line 1175), `Core::reconnect` (line 502), `Core::keep_granting` (lines 1096–1110), `Core::heal` (line 521)
  - `tools/pisoportal/src/fair.rs`: `fair::tick` (lines 16, 43, 49)
  - `tools/pisoportal/src/http.rs`: WebSocket `"hello"` handler (line 479)
- **Mechanism:**
  `Nds::grant()` shells out to three separate `ndsctl` processes with **no mutex** around the sequence:
  1. `if self.state(mac) == "Authenticated" { self.deauth(mac); }`
  2. `self.auth(mac, minutes, down, up, dw, uw)`
  3. `self.state(mac) == "Authenticated"`

  When a payment finishes, `settle()` calls `nds.grant()` on the window worker thread concurrently with the customer's browser sending a WebSocket `"hello"` (`http.rs` → `Core::reconnect`) or `fair::tick` running in the background:
  - **Interleaving A:** Thread 1 (`settle`) runs `self.auth(mac)` and succeeds. Immediately before Thread 1 runs `self.state(mac)`, Thread 2 (`reconnect`) enters `grant()`, sees `"Authenticated"`, and runs `self.deauth(mac)`. Thread 1's `self.state(mac)` reads `"Preauthenticated"`, causing `settle()` to report `online = false` and spawn `keep_granting`, which races `deauth`/`auth` a third time.
  - **Interleaving B (`ndsctl` socket contention):** Concurrent `ndsctl` invocations against openNDS's Unix socket can cause Thread 2's `deauth(mac)` to succeed and its subsequent `auth(mac)` to fail on socket contention, leaving a paying customer deauthenticated.
- **Recommended Fix:** Guard `Nds::grant()` (and `deauth`/`auth` operations) with a mutex (per-MAC or global `Nds` mutex) so only one thread can manipulate a client's openNDS state at a time.

---

## 3. High: Top-Up (`Mode::TopUp`) unconditionally `deauth`s an already-online user & bypasses fair-use throttling

- **Where:**
  - `tools/pisoportal/src/nds.rs`: `Nds::grant` (lines 104–108)
  - `tools/pisoportal/src/core.rs`: `Core::settle` (lines 1174–1185), `Core::reconnect` (line 502), `Core::heal` (line 521)
  - `tools/pisoportal/src/fair.rs`: `fair::tick` (lines 35–53)
- **Mechanism:**
  1. **Disconnection risk on Top-Up:** When an already-online (`Authenticated`) user inserts extra coins via "Add more time", `nds.grant()` first executes `ndsctl deauth <mac>`. If the subsequent `ndsctl auth` call fails or times out, a customer who *already had active internet access* is kicked offline into `Preauthenticated`.
  2. **Fair-use throttle bypass:** Calling `deauth` + `auth` resets openNDS's `download_this_session` / `upload_this_session` counters to `0`. Worse, `Core::settle`, `Core::reconnect`, and `Core::heal` always call `nds.grant()` with the **unthrottled** speeds (`s.down, s.up`), ignoring `st.fair.get(&mac).throttled`. Because `fs_.throttled` remains `true` in memory, `fair::tick` (`if over && !fs_.throttled`) will not re-apply the throttle — giving a throttled user full speed again whenever they top up or reconnect.
- **Recommended Fix:** Check `st.fair.get(&mac).throttled` when computing `(down, up)` in `settle()`, `reconnect()`, and `heal()` (and reset `fs_.throttled` / preserve byte counters appropriately if openNDS is re-authed).

---

## 4. High: Silent background unpause when topping up or recovering coins on a paused session

- **Where:**
  - `tools/pisoportal/src/roll.rs`: `roll::mint` (lines 215–217) vs. `roll::session` (lines 269–274)
  - `tools/pisoportal/src/core.rs`: `Core::settle` (line 1167), `Core::recover_recorded` (lines 1410–1443), `Core::resume` (line 569)
- **Mechanism:**
  - `roll::mint()` explicitly preserves pause state when adding minutes (`if e.pa != 0 { e.rp += minutes as u64 * 60; }`).
  - However, both `Core::settle` and `Core::recover_recorded` immediately call `roll::session()`, which unconditionally **unpauses** the entry (`entries[i].pa = 0; entries[i].rp = 0;`).
  - **Race condition with `recover_recorded`:** If a customer finishes paying, immediately taps **Pause**, and walks away — or if a late coin / box reconnect triggers `recover_recorded()` while the customer's session is paused — `recover_recorded()` calls `roll::session()` + `nds.grant()` in the background, **silently unpausing and draining the customer's time** while they believe their time is paused.
  - Additionally, unpausing via `settle()` does not update `st.last_resume` (unlike `Core::resume`), bypassing `pause_gap`.
- **Recommended Fix:** In `recover_recorded()` (and optionally when topping up a paused session, depending on intended UX), check whether the entry is paused (`e.pa != 0`) before calling `roll::session()` and `nds.grant()`. If `settle()` intentionally resumes on top-up, it should also update `st.last_resume`.

---

## 5. Medium-High: `publish_early` + slow `ack_and_clear` blocks `"Add more time"`, blocks `heal()`, and holds the global slot lock for up to 23s

- **Where:**
  - `tools/pisoportal/src/core.rs`: `Core::settle` (lines 1188–1190), `Core::ack_and_clear` (lines 1284–1296), `Core::finish_window` (line 1091), `Core::heal` (line 512), `Core::start` (lines 741–751), `Core::window_open_for` (line 791), `Core::pause` (line 528)
- **Mechanism:**
  In `Core::settle`, `self.publish_early(sid, result.clone())` sets `w.early = Some(View::Final { ... })` so the UI immediately transitions to the `"You're online"` screen, and **then** calls `self.ack_and_clear(sid)` synchronously before `finish_window()` clears `st.window = None`.
  If the ESP32 link drops packets during `ack_and_clear` (3 attempts × [3s challenge + 4s ack + 1s sleep] = up to **23 seconds**):
  1. **"Add more time" fails silently:** The customer is already looking at `View::Final` with the `"Add more time"` (`start`) button. If they tap it during those 23s, `Core::start` sees `st.window` is still `Some(w)` with `w.mac == mac` and returns the stale `w.early` (`View::Final`) instead of opening a new coin window.
  2. **`heal()` is blocked:** `Core::heal` checks `st.window.as_ref().is_some_and(|w| w.mac == mac)` **without** checking `w.early.is_none()` (unlike `window_open_for` and `pause`). If `nds.grant()` failed in `settle()`, `fair::tick` cannot heal the client during this window.
  3. **Other customers see `Busy`:** Any other customer tapping "Insert Coin" gets `View::Busy` while `ack_and_clear` retries.
- **Recommended Fix:** In `Core::heal`, check `w.early.is_none()`. In `Core::start`, if `st.window` has `w.early.is_some()` for the same MAC, allow clearing/finalizing the UI window or queueing rather than returning the stale `View::Final`.

---

## 6. Medium: Split `self.files` lock between `record()` and `roll::session()` in `settle()` can ack coins on `NO_SESSION`

- **Where:**
  - `tools/pisoportal/src/core.rs`: `Core::settle` (lines 1160–1172)
- **Mechanism:**
  - Line 1160 calls `self.record(...)` which locks `self.files`, calls `roll::mint` at timestamp `now`, and **unlocks** `self.files`.
  - Line 1166 re-locks `self.files` and calls `roll::session(..., now_secs(), mac)` with a newly sampled timestamp.
  - If `roll::session` returns `None` (e.g., if a sub-1-minute rate configuration produced `minutes == 0`, or another thread mutated the roll in between), lines 1169–1171 **still call `self.ack_and_clear(sid)`** and return `View::Error("NO_SESSION")` — wiping the unacknowledged coins from the ESP32 NVS even though the customer received an error and no session.
- **Recommended Fix:** Perform `roll::mint` and `roll::session` under the same `self.files` lock using the same `now` timestamp, and do not `ack_and_clear` if no session was created.

---

## 7. Medium: OpenNDS `Preauthenticated` client eviction during long portal idle before coin insertion

- **Where:**
  - `tools/pisoportal/src/nds.rs`: `Nds::auth` (lines 82–95)
  - `tools/pisoportal/src/http.rs`: `http::ws_session` (line 641)
- **Mechanism:**
  - Once redirected to the captive portal (`:2080`), the browser talks directly to `pisoportal` over a WebSocket (`SESSION_MAX = 900s`), which resolves the client's MAC from `/proc/net/arp` rather than through openNDS.
  - OpenNDS only adds a client to its internal client list when intercepting port-80 traffic, and evicts idle `Preauthenticated` entries after its `preauthidletimeout` (or if the `opennds` daemon restarts).
  - If a customer leaves the portal tab open for several minutes before tapping "Insert Coin" and paying, their MAC is still in `/proc/net/arp` so `pisoportal` accepts the coins, but `ndsctl auth <mac>` fails because openNDS no longer has `<mac>` in its client table until the device generates new port-80 traffic.
- **Recommended Fix:** When `ndsctl auth <mac>` fails on a missing client, have the portal UI trigger a lightweight port-80 probe (e.g., `fetch('http://192.168.1.1/...')` or captive-portal probe) or retry via `keep_granting` once the client's browser generates port-80 traffic.
