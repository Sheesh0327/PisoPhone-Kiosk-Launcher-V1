<!-- Paste this as the FIRST message of every chat, unchanged. Only the last line differs. Same words = same first bytes = cache hit. -->
Read, in this order and nothing else yet: `constitution.md`, `CLAUDE.md`, `specs/README.md`, `specs/STATUS.md`.
Follow the loop in `specs/README.md`: plan, implement, validate. No code without an approved feature spec. Do not read generated or big files; use `grep -n` and line ranges. Run slow tests in the background and read the last lines only. Do not push to `beta`, open a pull request, merge to `main` or release unless I ask. If unsure whether a change can hurt a running shop, ask me.
After your second reply, run `python3 scripts/cache_report.py --last 10` and tell me the hit rate in one line.
Task: <feature number and name, or the handoff note to continue: specs/notes/YYYY-MM-DD_name.md>
