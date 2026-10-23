# Plan: feature 012, no coin box mix-ups when a second router is set up nearby

Status: **draft, waiting for the owner's approval of spec.md (and its three owner decisions) and this plan. No code until approved.**

How to work (for the execution model): one task group at a time, in order. Test first: write the test, run it, see it fail, log the red result in `ledger/negative-constraints.md`; then the code; then the test passes. One commit per group, plain English, with the session's trailer lines. Tick the box here. Use only the names in the spec's "Names" table. Generated files are made by `python3 tools/build_piso_setup.py`, never by hand. If the spec does not say something you need, stop and ask the owner.

Context manifest (constitution rule 1). Unresolved ledger entries loaded: read `ledger/negative-constraints.md` at the start.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `specs/features/012-box-network-name/spec.md` | the contract | all | 3,500 |
| `setup/src/10-settings.sh` | `router_id`, `resolve_wifi_names`, `box_net_ssid` (40-90) | 50 | 800 |
| `setup/src/50-coin-box.sh` | `box_wifi_key`, `pair_box`, `lock_box_network`, `rotate_box_wifi` (1-200) | ranges | 2,500 |
| `setup/src/80-commands.sh` | `cmd_status` (1-60) | 60 | 800 |
| `setup/src/60-summary.sh` | the summary | grep, ranges | 500 |
| `setup/src/98-setup-flow.sh` | the command list and dispatch (140-200) | 60 | 900 |
| `setup/pisophone_setup.py` | `checkup` (3960-4060) | ranges | 1,200 |
| `router/tests/test_setup.py`, `test_pisophone_setup.py` | stubs for `box_curl`, `uci`, `wifi`, the existing rotate tests | grep, ranges | 2,500 |

## Task groups
- [ ] 0. **Owner gate.** The owner approves the spec, its three owner decisions and this plan.
- [ ] 1. **Baseline** (criterion 1). Run the tests; counts in `validation.md`.
- [ ] 2. **Failure before the switch changes nothing, tests first** (criterion 3). `rotate_box_wifi` restores `BOX_WIFI_ROTATED` (and leaves every other setting) when the box refuses; the after-switch message. This fixes `piso-setup rotate-box-wifi` too. Raise `setup/RELEASE` to 1.5.18 here. Regenerate; `--check` passes.
- [ ] 3. **The command, tests first** (criteria 2, 6). `own_box_wifi_name` and `piso-setup box-wifi-name`: the checks before it starts, the question or `--yes`, the new name from `router_id`, `BOX_SSID_NAME`, then `rotate_box_wifi`; no kiosk setting touched.
- [ ] 4. **Telling the owner, tests first** (criterion 4). The status line in `piso-setup status` and the summary; the Check-up line in the setup program. Regenerate `website/pisophone_setup.py`.
- [ ] 5. **Pairing tips, tests first** (criterion 5). The tip at 60 s and 150 s and in the "did not join" message; the pairing itself unchanged.
- [ ] 6. **Docs and the hardware check** (criterion 7). `docs/REAL_WORLD_TESTING.md` (new check, "not run"), `setup/README.md` and `setup/MANUAL.md` (the command, and "switch the other router off while pairing"), `website/manual.html`, `CHANGELOG.md`.
- [ ] 7. **Validate** (criterion 8). `validation.md` with evidence, the gates, "Not tested", "Money and time"; update `specs/STATUS.md`. Pull request into `beta` only when the owner asks.

## Release
- Router: `setup/RELEASE` 1.5.17 -> 1.5.18, once, in group 2. Firmware and phone app: not touched.
- Publishing: after the owner's order, the merge to `main` makes CI publish at 0%; the owner raises it to 100%.
