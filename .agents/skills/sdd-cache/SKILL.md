---
name: sdd-cache
description: Monitor context token economy, cache stability, and conversation length. Use after the second reply of a chat, at the completion of a feature, or when a session becomes sluggish.
---
1. Keep the prompt prefix byte-stable (constitution rule 2). Do not inject timestamps, UUIDs, or dynamic tokens into static prefixes.
2. Maintain strict zero raw file dumps discipline (constitution rule 1): maximum 200 lines / 1,500 tokens per snippet; use targeted `grep -n` and line ranges.
3. Check token consumption and conversation length. If the session exceeds 20 turns or approaches context limits, initiate `sdd-handoff` to summarize state into disk files (`specs/notes/`) and recommend a fresh chat.
4. Record any context or cache regressions in `ledger/negative-constraints.md`.
