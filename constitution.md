---
version: 1.2.0
frozen: true
last_reviewed: 2026-10-21
---

# PisoHub Software Factory Constitution

> This file is the **absolute first bytes** of every system prompt.
> Do not edit between sessions. Version it, then freeze it.
> If a rule here conflicts with the two project rules in `CLAUDE.md` section 1 (money and time are never wrong; only owner-signed software is installed), the project rules win.

## 1. Zero Raw File Dumps

- **Hard cap: 200 lines / 1,500 tokens per file snippet.** Anything larger must be summarized.
- Before touching code, emit a **Context Manifest**: a flat table of `filename | purpose | lines | est_tokens`.
- Directory exploration must use `tree -L 2 -I node_modules` output, never `ls -R` dumps.
- CSV/JSON: show **headers + first 5 rows + row count only**. Never full data.

## 2. Byte-Stable Prefix & Context Hygiene

- This constitution is the **first bytes** of the system prompt. No preamble above it.
- **No timestamps, UUIDs, session IDs, or `Date.now()`** anywhere in the static prefix before the first user message.
- Tool definitions must be **pinned and versioned**. Any router or proxy that injects a header must append it to the *user message*, not the system prefix.
- **Do not reorder or reformat** static blocks between turns — even whitespace changes invalidate the cache.

## 3. Caching Telemetry Gate (NEW)

- After **turn 2**, verify the API response shows `cache_read_input_tokens > cache_creation_input_tokens`.
- If the ratio is inverted, **stop and diagnose**: check for (a) proxy header injection, (b) a model swap by a fallback, (c) any dynamic token in the prefix.
- Log the cache hit rate to the Negative Constraint Ledger (Rule 6) so you can spot regressions.

## 4. Tiered Model Discipline

- **Tier-1 (Haiku):** bulk codegen, markdown formatting, data cleaning. Default effort: `low`.
- **Tier-2 (Sonnet):** architecture, integration, complex scripts. Default effort: `medium`.
- **Tier-3 (Opus):** strict emergency fallback. Requires **two documented failed local test runs** in the ledger first.
- **Model-switch protocol:** Any tier change (manual or by fallback) **must start a new chat**. Caches are per-model; mixing tiers mid-session pays full price for every turn.
- A model fallback is allowed **only on quota exhaustion**, never on soft errors.

## 5. Fresh Chat / Nuke Rule

- **Turn limit: 20.** **Input token limit: 60K.** Whichever hits first, nuke the chat.
- Before nuking: **write a summary to `specs/notes/YYYY-MM-DD_<task>.md`** (task, decisions, artifacts, open questions) and link it from the ledger.
- Treat every task as a single transaction: generate → copy to disk → close.

## 6. Negative Constraint Ledger

- Maintain `ledger/negative-constraints.md` in this repository. Entries older than the table are free-text history and stay as they are. New entries use this table:

  | date | task | model | error_class | traceback_hash | constraint_violated | resolved |
  |------|------|-------|-------------|----------------|---------------------|----------|

- **Start every new task by loading relevant unresolved entries** into the context manifest.
- Prune entries older than **7 days** or after successful resolution. A bloated ledger is a context tax.

## 7. Output Verification Gate (NEW)

- **Python** (`scripts/`, `setup/`, `router/tests`, `protocol/`): no generated code is accepted without **`ruff check` + `mypy` + `pytest -x`** on the touched files.
- **Every area:** run the checks for the area in the `CONTRIBUTING.md` table and `bash scripts/check_security_rules.sh`. Say plainly what could not be run (for example no Android SDK or no board).
- Failed gate → append to ledger, do **not** retry in the same chat. Open a fresh chat with the traceback.

## 8. Spec First

- **No code without an approved spec.** Work is a feature: a folder `specs/features/NNN-name/` with `spec.md` (intent, constraints, success criteria), `plan.md` (small task groups) and `validation.md` (acceptance checks and evidence). The owner approves the spec and plan before the agent writes code.
- **The spec is the contract.** If the work needs something the spec does not say, stop and change the spec first (small, precise edit), then continue. Code that no spec asks for is removed.
- **One feature, one branch, one area**: `feat/NNN-name` from `beta`. Loop: plan, implement, validate. A feature is done only when `validation.md` is filled with evidence and `specs/STATUS.md` is updated.
- **Replan between features** (`specs/REPLAN.md`): update the constitution, roadmap and workflow before starting the next one.

## 9. Human in the Loop

- The owner is the architect and approves specs, plans, merges to `main` and releases. The agent does the execution.
- Review looks at the spec, the acceptance criteria and the evidence first; code style nits come last and only where a tool does not catch them.
- When unsure whether a change can hurt a running shop, ask the owner before shipping it (`CLAUDE.md` section 6).
