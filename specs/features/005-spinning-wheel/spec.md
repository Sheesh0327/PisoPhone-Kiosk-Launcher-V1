# Feature 005: spinning wheel on the PisoWiFi portal (replaces the free mini-game)

Status: **done** (all 8 success criteria passed on the computer, see `validation.md`; released as router 1.5.14 on 2026-10-22; open: hardware check RS34 and the legal review of the warning text). Approved by the owner 2026-10-21 with the recommended answers to decisions 1 to 7   Branch: `feat/005-spinning-wheel`   Area: router (portal program, console, setup defaults)
Backlog rows: none (side feature, owner request 2026-10-21)   Release: router (raise `setup/RELEASE`)

## Intent
A paying Wi-Fi customer can bet some of their **paid minutes** on a spinning wheel: a win multiplies the bet (2x, 3x, 5x), a loss costs it. The wheel replaces the free mini-game (a daily play allowance that never touched paid time) once the owner turns it on. The shop keeps a small long-run edge from the published odds.
Serves the owner's wish for a livelier portal. **It also puts paid time at risk**, so it is built around project rule 1 (money and time are never wrong): every spin changes a customer's time exactly once, by exactly what the screen said, and can be audited.

## What the owner chose (2026-10-21)
- Stake: **paid internet minutes**. Small **house edge**. Safeguards required: max bet per spin, a minimum of time always kept, spins per day and a wait between spins, **result decided by the router** and only drawn by the page.
- Limits to start with: bet 1 to 10 minutes and never more than half of the time left; a loss never leaves less than **5 minutes**; **10 spins a day** per device with a **10-second wait**; all editable in the console.
- Wheel segments: the owner asked for an algorithm that "creates an illusion of winning" and "adjusts the odds so the house wins in the long run". **Not specified, on purpose** (see "Not built" below). The house edge comes from the published table.
- Rollout: **a router that updates keeps the free mini-game and has the wheel off until the owner turns it on.** A new install earlier was to have it on by default; with the responsibility warning below **nothing can have the wheel on until the owner has confirmed it in the console**, so a new install also starts with it off (decision 6).
- Accounting: each spin is a separate ledger row; coins stay the only revenue.
- **Added 2026-10-21: a hard responsibility warning before the owner can turn the wheel on** (constraint 8).

## Not built, and why
- No hidden or changing odds: the outcome of a spin never depends on the customer's earlier wins or losses, their balance, the time of day or how much they just won. One fixed table, the same for everyone, shown on the page.
- No near-miss or "almost won" effects and no wheel that draws a different picture from the result. The animation is free to be lively, but it ends exactly on the segment the router chose.
- No auto-spin, no "double or nothing" chain, no spin when the customer has paused or has under the floor.
Reason: a customer staking paid minutes must be able to rely on the odds they are shown; anything else is misleading them about what they risk. It also puts the shop owner at legal risk. A house edge is fine and is built in openly.

## Invariants touched
- **Money and time (rule 1) - heavily.** A spin is one change to one session's time under the router's file lock (`files`), through one function; the router journals the spin before changing the time (write, flush, rename, flush folder: `util::write_atomic`), so a power cut leaves either the old time or the new time, never both or neither; a repeated request with the same spin id returns the same result and never charges twice. The ledger of coins (`revenue.csv`, the chain the box is reconciled against) is not touched; spins go in their own log.
- Only owner-signed software: not touched (router update is signed as usual).

## Constraints
1. Result from the router's own random source (`/dev/urandom` via `util::random_hex`), drawn before the page animates; the page cannot choose or change it.
2. Odds table: default **20 equal slots: 13 lose, 5 x2, 1 x3, 1 x5** = win chance 35%, average return **90%** of the bet (house edge 10%). The router refuses to load any table whose average return is **above 100%** (a customer-favoured wheel); the console warns below 80%.
3. Win means the bet comes back multiplied: net gain = (multiplier - 1) x bet minutes; loss = the bet. Session time never goes above the existing cap.
4. Safeguards as chosen above, enforced on the router, not in the page. No spin while the session is paused, expired, or in a plan that cannot take it.
5. The page shows, before the spin, every segment with its chance and payout and the average return, and after the spin the exact result in words ("You bet 5 min and lost 5 min. Time left: 42 min").
6. The wheel works without the internet (served by the router, as the mini-game is) and before payment shows no wheel. *(Changed 2026-10-22 by feature 006: before payment the button and the odds are shown to everyone; only spinning needs paid time.)*
7. Router program keeps using only `sha2`, `hmac`, `p256`; no new library. No change to the coin box, the phone app, or the box protocol.
8. **Responsibility warning (hard gate).** The wheel cannot be switched on until the owner has read a full-screen warning in the router console and typed a confirmation. Enforced **on the router**, not only in the page:
   - The console shows the warning (text below) as a blocking dialog with no "OK" button: the on switch stays disabled until the owner types the exact phrase `I TAKE RESPONSIBILITY` (case as written) into a box, then presses Turn on.
   - The router's "turn on" request must carry that phrase and the warning's version number. A request without them, with a wrong phrase, or with an old warning version is refused with a plain message, and the wheel stays off.
   - The router keeps a record of the confirmation (date and time, the warning version, the console session) in its own file under the data directory; the console shows "Wheel on since <date>, responsibility confirmed" next to the switch. The record is written only by that request.
   - A hand-edited setting (`WHEEL=on` in the config or the setup program's answers) **does not turn the wheel on** without a valid record: the router treats the wheel as off and logs why. An update, an install or an override cannot bypass it.
   - **Every time it is turned on again** the owner confirms again (turning it off, or a new warning version, clears the record). Turning it off needs no confirmation and takes effect at once; minutes already won or lost stay as they are.
   - The warning, the confirmation, each turn on and each turn off are written to the wheel log.
   - The customer-facing page shows nothing of the wheel while it is off.

   **Warning text (version 1, plain English, shown as written):**
   > **Responsibility warning. Read this before turning on the spinning wheel.**
   > Customers will bet minutes they paid for. A win gives them more time; a loss takes the minutes away.
   > In many places a game of chance played for something of value is regulated, needs a licence, has age limits or is not allowed at all. This software cannot know the rules where you work.
   > By turning the wheel on you confirm that:
   > 1. You have checked the rules where your shop operates, and you are allowed to run this.
   > 2. You are responsible for who plays, children included, and for every complaint, refund, tax or penalty that comes from it.
   > 3. The odds are shown to every customer and are the same for everyone; you will not change the program to hide them.
   > 4. You can turn the wheel off at any time. Minutes already won or lost stay as they are.
   > To go on, type: **I TAKE RESPONSIBILITY**

## Success criteria
1. Pure logic tests (fail first): the table's average return is computed exactly; a table above 100% is refused; bet rules (1..10, <= half of left, floor 5 left after a loss) accept and refuse the right bets; the draw over a large run with a seeded source lands within a tight range of the table's chances.
2. Time tests: a spin changes the session by exactly the stated amount; a repeated spin id returns the same result and changes nothing; a simulated power cut after the journal and before the time change (and after) ends with consistent time; paused and expired sessions refuse.
3. Limit tests: 10 spins a day then refused; the 10-second wait; limits reset at the router's local midnight.
4. End to end (`test_flow.py` and a browser test like `test_game_browser.py`): a paying customer opens the wheel, sees the odds panel, spins, and the page's time left matches the router's.
5. Old routers keep the mini-game and have the wheel off after update; a new install also starts with it off; the console switch (behind the responsibility gate) turns it on, and off at once.
6. Revenue ledger and box reconciliation unchanged (existing tests untouched and green).
7. **Responsibility gate tests (fail first):** the router refuses to turn the wheel on with no phrase, a wrong phrase (including lower case and extra spaces), or an old warning version; accepts the exact phrase with the current version and writes the record; a hand-edited `WHEEL=on` with no record leaves the wheel off and logs the reason; turning it off clears the record and the next turn on asks again; a new warning version clears an old record; the customer page and `/wheel/*` answer "off" while it is off. A browser test shows the dialog, the disabled switch, and that only the exact phrase enables the button.
8. `cargo fmt --check`, `clippy`, `cargo test`, `test_flow.py`, router setup tests, `check_security_rules.sh`, `build_piso_setup.py --check` pass; `setup/RELEASE` raised once; hardware check added (not run).

## Out of scope
Phone app, coin box, other games, tournaments or leaderboards, selling spins for coins, any prize other than minutes.

## Owner decisions (please answer before approval)
1. **Legal check before customers use it.** (The hard responsibility warning, constraint 8, is the in-product part of this; it does not replace a legal review.) Staking paid time on a chance game may fall under gambling rules where the shop operates (roadmap 8.3 is still open and is reviewed outside this repo). Recommended: the code can be merged and released with the wheel **off** for existing shops, and you confirm the local rules before anyone switches it on or ships it as default-on for new installs. Do you accept that?
2. **Children.** Kids use these shops. Recommended: the wheel is hidden unless the owner turns it on, the odds panel is always shown, and the console says the owner decides who may play. Is a minimum-age note on the page wanted?
3. **The old mini-game.** Recommended: keep it on updated routers while the wheel is off, and show only the wheel once the owner turns it on; remove the mini-game code in a later change. OK?
4. **The default table** (13 lose, 5 x2, 1 x3, 1 x5 of 20; 35% win; 90% return). OK, or different numbers?
5. **A cap on one win** (for example a session may not gain more than 60 minutes a day from the wheel). Wanted?
6. **New installs and "on by default".** You first chose on by default for new installs. With the responsibility gate the wheel cannot be on before the owner confirms in the console, so a new install starts with it off and the setup program's last screen tells the owner where to find it. OK? (The only way to keep "on at install" would be to put the same warning and typed phrase into the setup program; recommended against, because the owner is busy with Wi-Fi and router steps there.)
7. **The warning text** above (version 1). Anything to add or change, for example the name of the authority to check with in your country? A lawyer should read it before release (roadmap 8.3).

## Owner answers (2026-10-21: "Approve", taken as the recommended option of each)
1. Legal: the wheel ships **off** everywhere; the owner confirms local rules before turning it on; a lawyer reads the warning before release. 2. Children: hidden unless the owner turns it on; the odds panel is always shown; no age note on the page for now. 3. Old mini-game: kept while the wheel is off; code removed in a later change. 4. Default table 13 lose, 5 x2, 1 x3, 1 x5 of 20. 5. Win cap: 60 minutes a day per device from the wheel (editable). 6. New installs start with the wheel off. 7. Warning text version 1 as written.
