---
name: sdd-cache
description: Check how much of this chat is read from the prompt cache and warn when it is not. Use after the second reply of a chat, at the end of a feature, or when a chat feels slow or costly.
---
Run `python3 scripts/cache_report.py --last 10` (and without `--last` for the whole chat). Report the hit rate and the saving in one line each.
If it warns (more written to the cache than read from the third call on), say which of the usual causes fits: a rule file or skill edited in this chat, a tool or connector change, a pause longer than the cache lifetime, a model switch. If the chat is long (20 turns or 60K input tokens), run `sdd-handoff` and suggest a fresh chat. Add a bad ratio to `ledger/negative-constraints.md`.
