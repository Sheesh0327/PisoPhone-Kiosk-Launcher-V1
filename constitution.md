---
version: 1.3.0
frozen: true
last_reviewed: 2026-11-11
---

# PisoHub Software Factory Constitution

This is a versioned project policy. The two project rules in `CLAUDE.md` section 1 (money and time are never wrong; only owner-signed software is installed) govern trade-offs.

## 1. Context hygiene

- Read only relevant files and use focused searches. Do not disclose secrets.
- Keep file snippets under 200 lines / 1,500 tokens; summarize larger material.
- Before touching code, emit a context manifest with filename, purpose, lines and estimated tokens.
- For CSV/JSON, show headers, the first five rows and the row count only.
- Preserve a handoff when work cannot be completed in a session.

## 2. Provider-specific guidance

Stable prompts, cache measurements, model selection, and chat limits depend on the provider and tools actually available. Use cache telemetry only when the runtime exposes it; never invent or require inaccessible measurements. Prior cache and model guidance is historical and not a universal gate.

## 6. Negative Constraint Ledger

- Maintain `ledger/negative-constraints.md` in this repository. Entries older than the table are free-text history and stay as they are. New entries use this table:

  | date | task | model | error_class | traceback_hash | constraint_violated | resolved |
  |------|------|-------|-------------|----------------|---------------------|----------|

- **Start every new task by loading relevant unresolved entries** into the context manifest.
- Prune entries older than **7 days** or after successful resolution. A bloated ledger is a context tax.

## 7. Output Verification Gate (NEW)

- **Python** (`scripts/`, `setup/`, `router/tests`, `protocol/`): no generated code is accepted without **`ruff check` + `mypy` + `pytest -x`** on the touched files.
- **Every area:** run the checks for the area in the `CONTRIBUTING.md` table and `bash scripts/check_security_rules.sh`. Say plainly what could not be run (for example no Android SDK or no board).
- Record a failed acceptance gate and diagnose it. A bounded retry after a documented diagnosis or change is allowed; do not hide repeated failures. An intentional red regression test is not a failed acceptance gate.

## 8. Spec First

- No code without an owner-approved spec and plan. Use OpenSpec for new and resumed work, as described in `specs/README.md`; link and preserve legacy records.
- Record approval scope and acceptance evidence separately from task checkboxes. Update the spec first and obtain approval for material new scope before changing code.
- Start feature branches from `main`. Follow `CLAUDE.md` section 5 for router beta builds, exact-candidate pre-merge checks, and owner-directed PRs, merges, and releases.
- Record results or explicit not-run reasons for each criterion, update the status and backlog, and keep outstanding hardware checks open.

## 9. Human in the Loop

- The owner is the architect and approves specs, plans, merges to `main` and releases. The agent does the execution.
- Review looks at the spec, the acceptance criteria and the evidence first; code style nits come last and only where a tool does not catch them.
- When unsure whether a change can hurt a running shop, ask the owner before shipping it (`CLAUDE.md` section 6).
