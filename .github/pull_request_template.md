## What and why
<!-- One or two sentences. Link the issue. -->

## Spec
<!-- Link `specs/features/NNN-name/` (or say why none: docs/CI only). Reviewers read the spec, its success criteria and `validation.md` first. -->

## Area
- [ ] Android app
- [ ] ESP32 firmware (including the dashboard files and the protocol)
- [ ] Router program (`tools/pisoportal/`)
- [ ] Router add-on and setup (`router/`, `setup/`)
- [ ] Website
- [ ] CI / docs only

## Verification
<!-- Paste the output (or the last lines) of the commands you ran, from CONTRIBUTING.md ("Before you open a pull request"). -->

## Checklist
- [ ] I ran the checks for every area I touched and they are clean, and `bash scripts/check_security_rules.sh` and `python3 scripts/check_links.py` pass
- [ ] No generated or published files were edited by hand (`WebAssets.h`, `setup/piso-setup.sh`, `website/update/*`, ...)
- [ ] No keys, passwords or tokens are included
- [ ] If this changes the box <-> phone protocol, both sides and the test vectors changed here
- [ ] If this changes money or time accounting, I explained how I checked it
- [ ] I raised `PISO_FW_VERSION` (firmware) or `setup/RELEASE` (router) if this should reach existing devices
- [ ] If this changes the router (`tools/pisoportal/src/`, `setup/`, `router/`), it goes through `beta` (`CONTRIBUTING.md`, "Branches")
