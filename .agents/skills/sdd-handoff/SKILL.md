---
name: sdd-handoff
description: Write a short handoff note before ending a session so the next session can start from files instead of history. Use at turn or token limits or when directed to pause.
---
1. Write a concise handoff document in `specs/notes/YYYY-MM-DD_<feature>.md`:
   - Active branch and current tip commit hash.
   - What is completed and verified.
   - Decisions made with the owner.
   - What is currently open or blocked.
   - Precise next step and command to run slow or pending tests.
2. Update `specs/STATUS.md` to reflect current state.
3. Commit the handoff note and status update.
4. Provide the exact continuation prompt for the owner to paste into a fresh session:
   `Read constitution.md, GEMINI.md, specs/README.md, specs/STATUS.md and specs/notes/<note>.md, then continue.`
