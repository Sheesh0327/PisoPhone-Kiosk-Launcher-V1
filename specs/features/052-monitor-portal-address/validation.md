# Validation: feature 052

- **Reproduced first** (a script run on 2026-10-30, not kept): the real portal bound to `127.0.0.2` and the real monitor against it, default settings:
  after 4 ticks, 4 failed pings, 1 restart of a healthy portal and the "did not answer" alert; the same with the portal bound to `0.0.0.0`: 0 and 0.
- **Red first:** `python3 router/tests/test_monitor.py` with the new `052` checks and the old monitor: `60 checks, 4 failures` (a healthy guest-bound
  portal was restarted: `restarts=1 fails=5`; the three after it follow from the same cause). Logged in `ledger/negative-constraints.md`.
- **Green:** the same test, `60 checks, 0 failures`, covering criteria 1 to 4: a portal on the guest address; the portal bound to `0.0.0.0`, no bind at
  all, and `127.0.0.1`; a portal that hangs (two failed pings do nothing, the third restarts once, the count is 0 afterwards, the next ticks say nothing new,
  and "answers again" is noted).
- **Other scripts checked:** the update script and the installer's own checks already ask the guest address; the monitor was the only one asking loopback.
- **Criterion 5:** `tools/build_piso_setup.py --check` passes; the program embedded in each setup file is the committed program; `git status` shows no
  change under `tools/pisoportal/bin`. `setup/RELEASE` is 1.5.32. Gates: see the commit message.
- **Not tested:** a real router (the cure is the monitor asking `10.0.x.1:PORT`; the router's own address and port are read from `/etc/coinslot.conf`
  exactly as the update script reads them). The owner's workaround until 1.5.32 is installed: `PING_URL='http://<PORTAL_BIND>:<PORTAL_PORT>/ping'` in `/etc/piso-monitor.conf`.
