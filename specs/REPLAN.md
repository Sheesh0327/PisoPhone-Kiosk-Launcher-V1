# Replanning checklist

Run between features (skill `sdd-replan`). Keep it short; the point is to stop and look, not to write a report.

- [ ] Did the finished feature meet every success criterion in its `validation.md`? What was not tested?
- [ ] Did the spec change during the work? Should the template or the constitution change because of it?
- [ ] `specs/roadmap.md`: still in the right order (money first, then safety, then convenience)? Anything new, blocked or dropped?
- [ ] `specs/techstack.md`, `specs/mission.md`: still true?
- [ ] Backlog (`specs/spec_NN_findings.json`) and `specs/STATUS.md` match what is on `beta` and `main`.
- [ ] Open owner decisions: list them and ask.
- [ ] Ledger: prune resolved or older than 7 days. Notes: anything worth keeping moved to the spec.
- [ ] Workflow: anything that cost tokens or time that a skill or a template could save?
- [ ] Next feature chosen with the owner.

Write the result in `specs/replans/YYYY-MM-DD.md`.
