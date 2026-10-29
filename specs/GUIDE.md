# Developing with spec-driven development (SDD): guide

Part A is how to work. Part B is how to keep the prompt cache working (the 90% discount) and what it can and cannot do. Part C lists the templates.
Read `specs/README.md` first for the one-page version.

---
## Part A. The workflow

### A1. Why
"Vibe coding" is a chat that steers by feel: fast, but the intent lives only in the chat, a new chat starts from zero, and nobody can say what was
promised. SDD keeps the intent in files. The spec is the contract between the owner and the agent; the agent is stateless, the files are not.
Three gains: **control** (a big change is a small edit to a spec), **memory** (files anchor every new chat), **fidelity** (goal, limits and success
criteria are written before code, so the result can be checked against them).

### A2. The layers and who changes them

| Layer | Files | Changed by | How often |
|---|---|---|---|
| Rules for the agent | `constitution.md` (frozen), `CLAUDE.md` (project rules) | owner, new version | rarely |
| Direction | `specs/mission.md`, `techstack.md`, `roadmap.md` | owner, at a replan | between features |
| Backlog | `specs/spec_NN_*.md` (epic) + `spec_NN_findings.json` (one row per item) | agent updates rows | each feature |
| Feature | `specs/features/NNN-name/{spec,plan,validation}.md` | written, approved, built, proved | one per branch |
| State | `specs/STATUS.md` (board), `specs/RELEASE_STATE.md` (what is live) | agent, at the end of a task | often |
| Memory | `specs/notes/` (handoffs), `specs/replans/`, `ledger/negative-constraints.md` | agent | each chat |

Rule of thumb: what rarely changes goes at the top of the list and is read first; what changes often is read last.

### A3. The lifecycle

1. **Constitution and direction** (once, then at replans). The two project rules decide every trade-off: money and time are never wrong; only
   owner-signed software is installed.
2. **Backlog.** Findings and roadmap items become rows with an id, files, severity, test and release type.
3. **Feature loop**, one feature = one branch `feat/NNN-name` cut from `main` (a router change: cut from `beta`, see `CLAUDE.md` section 5):
   - `/sdd-plan` writes `spec.md` and `plan.md` and **stops**. The owner approves. No code before approval.
   - `/sdd-implement` works one task group at a time, test first (see it fail, log it, fix, see it pass), one commit per group.
   - `/sdd-validate` fills `validation.md` with evidence (command and last lines of output), runs the gates and lists what was not tested.
   - Review: the owner reads the spec, the success criteria and the evidence first; code style last.
4. **Land**: open a pull request into `main` (a router change: into `beta`) (Code quality only runs on pull requests and on `beta`/`main`, not on `feat/*` pushes). Merge when CI is
   green. Never push to a branch while a needed Code quality run on it is in flight (a push cancels it). Merging to `main` and releases only when the owner asks.
5. **Replan** (`/sdd-replan`): what was learned, roadmap order, workflow changes, the next feature. Write `specs/replans/YYYY-MM-DD.md`.
6. **Handoff** (`/sdd-handoff`) before ending a long chat; the next chat starts from files.

### A4. Writing a spec that works
- **Intent**: who is helped and which project rule it serves, in 2 or 3 sentences. Not how.
- **Constraints**: what must not change (formats, generated files, crypto limits, live devices).
- **Success criteria**: numbered, each checkable by a command or an observation. "Robust" and "fast" are not criteria; "`cargo test` passes and
  `test_flow.py` shows 202 checks, 0 failures" is.
- **Invariants touched**: say how money/time (credited once, recorded first, nothing lost on a power cut) and signing are kept, or "not touched".
- **Out of scope** and **owner decisions** are lists, not prose. A spec with an open owner decision is not approved.
- Name files and line ranges so the agent opens nothing else.
Bad: "Make the router more reliable." Good: "RT-10: the old nft table is deleted in the same `nft -f` transaction as the new load; test: a fake nft that
fails on load leaves the old table in place."

### A5. Test first, and the ledger
Write the test, run it, see it fail for the right reason, write the failure in `ledger/negative-constraints.md` (date, task, error class, the constraint
it broke). Then fix, then see the same test pass. If only hardware can show the problem, add a numbered check to `docs/REAL_WORLD_TESTING.md` and say
plainly it was not run. For a ported patch, break the fix on purpose once to see its test fail.

### A6. Gates by area (run what you touched; always the security rules)

| Area | Commands |
|---|---|
| Always | `bash scripts/check_security_rules.sh` |
| Router program | `cd tools/pisoportal && cargo fmt --check && cargo clippy --all-targets && cargo test`; release build, then `PISOPORTAL=<binary> python3 tests/test_flow.py` |
| Router scripts and setup | `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, `test_phone_only.py`, `test_setup_phone_web.py` (the first takes minutes: run it in the background) |
| Firmware | `clang-format` 23.1.2 (`--dry-run --Werror` on `src include host_tests`), `sh esp32_firmware/host_tests/run.sh` (needs `libmbedtls-dev`), `python3 scripts/embed_web.py --check`, `python3 scripts/check_product_strip.py` |
| Generated setup files | `python3 tools/build_piso_setup.py` then `--check` |
| Python tools | `ruff check`, `mypy`, `pytest -x` on the touched files |
| Phone app | Gradle unit tests and `ktlint` (needs the Android SDK; say so when it was not run) |
Generated files are regenerated, never edited or merged by hand.

### A7. When things go wrong
- **The spec is silent or wrong**: stop, make a small precise edit to the spec, tell the owner, continue. The code never gets ahead of the spec.
- **A red check you cannot reproduce**: run it again once; a second failure is real. Never skip, disable or loosen a test to get green.
- **Prior work on another branch**: trial-apply it in a throwaway worktree first (`git worktree add --detach`, `git cherry-pick --no-commit`), list the
  conflicts and what they depend on, put that in the spec, then ask the owner.
- **A review bot finding**: verify the claim with a test; fix if real; reply on the thread with the commit.
- **Unsure whether a change can hurt a running shop**: ask the owner.

### A8. Checklist for one feature
[ ] spec and plan written and approved  [ ] each task: red test logged, fix, green  [ ] gates run  [ ] `validation.md` has evidence and "not tested"
[ ] backlog rows and `specs/STATUS.md` updated  [ ] `CHANGELOG.md` in owner terms if a release is raised  [ ] PR opened, CI read  [ ] handoff note if the chat is long

---
## Part B. Using the prompt cache (the 90% discount)

### B1. What is true
- Claude reads the start of every request from a cache when that start is **byte-for-byte identical** to an earlier request. A token read from the cache
  costs about **0.1x** of a normal input token (the 90% discount). The first time a prefix is sent it is **written** to the cache at about 1.25x
  (5-minute lifetime) or 2x (1-hour lifetime). Each hit renews the lifetime.
- The order of a request is: tools, then system prompt (including `CLAUDE.md`, skills), then the conversation. A change anywhere in the prefix makes
  everything after the change a miss.
- **"Always 90%" cannot be promised.** The first request of a chat is a write; a pause longer than the cache lifetime loses it; changing the model,
  the tool list or `CLAUDE.md` in the middle of a chat breaks it. What can be promised: with the rules below the hit rate stays near 100% of the
  *repeated* part, and every chat is measured.
- Claude Code places the cache markers itself. You cannot add padding or markers; you can only keep the prefix stable and the chat short.

### B2. Measured in this repository
`python3 scripts/cache_report.py` reads the chat's transcript. For the long chat that built this workflow: 264 model calls, 79.3 million input tokens,
**99.5% read from the cache**, input cost about **11% of sending it uncached** (an 89% saving; the last 20 calls: 90%). So the cache already works.
The lesson is the other half: the chat still *re-read* 79 million tokens, because each turn re-reads the whole history. The discount makes a long chat
cheaper, not cheap. **Cost per turn grows with the length of the chat**, so short chats (rule R6) save more than any cache trick.

### B3. The rules
- **R1. Stable first bytes.** `constitution.md` and `CLAUDE.md` carry no dates, status or counts. Anything that changes (what is live, what is done, what
  is next) is in `specs/RELEASE_STATE.md` and `specs/STATUS.md`, read later in the chat, so they never change the cached prefix.
- **R2. Edit rules between chats, not during one.** Changing `CLAUDE.md`, the constitution or a skill in the middle of a chat changes the prefix. Batch
  those edits and start a fresh chat afterwards. (Writing specs, code, notes and the ledger is fine: that is conversation, appended at the end.)
- **R3. Do not change the model, the tool list or the connectors mid-chat.** Connecting or removing an MCP server or plugin changes the tools at the
  very front of the request. Decide the tools before the chat starts. A model switch starts a new cache: start a new chat for it.
- **R4. Stay inside the cache lifetime.** The default lifetime is 5 minutes (some setups 1 hour). A wait for CI longer than that costs one rewrite of
  the whole history when you come back. Do not poll; let events wake the chat (PR subscriptions), or end the chat and start a fresh one from a handoff
  note when the wait is long.
- **R5. Append, do not dump.** Read line ranges, use `grep -n` and `| tail`, run slow tests in the background and read the last lines. Big pasted
  files and full logs stay in the history for every later turn. Never read generated files.
- **R6. One feature per chat; nuke at 20 turns or 60K input tokens** (constitution rule 5). Write `/sdd-handoff` first. A fresh chat re-sends only the
  small stable files, not the history.
- **R7. Open every chat the same way.** Use `specs/templates/session-start.md` word for word; only the last line (the feature) changes. Same opening,
  same first bytes, cache hit from the second chat onwards (the system part is shared across chats within the lifetime).
- **R8. Measure.** After the second turn and at the end of each feature run `python3 scripts/cache_report.py --last 20`. The script warns when, from
  the third call on, more was written to the cache than read. Log a bad ratio in the ledger and look for: an edited rule file, a tool change, a long
  pause, a model switch.

### B4. What does not help
Repeating text to "fill" the cache; splitting the rules into many small files that all get read (more tokens, same prefix); putting the date or the
session id in the first bytes (breaks every chat); keeping one chat open for days (cost per turn keeps growing).

---
## Part C. Templates (in `specs/templates/` and `specs/features/_template/`)

| Template | Use |
|---|---|
| `specs/templates/session-start.md` | The opening message of every chat (cache-stable) |
| `specs/features/_template/spec.md`, `plan.md`, `validation.md` | A feature: intent and criteria, task groups, evidence |
| `specs/templates/epic-spec.md` | A backlog epic (many rows) |
| `specs/templates/finding-row.json` | One backlog row |
| `specs/templates/owner-decision.md` | A question only the owner can answer |
| `specs/templates/handoff.md` | End of a long chat |
| `specs/templates/replan.md` | Between features |
| `specs/templates/ledger-entry.md` | A failure, before the fix |
