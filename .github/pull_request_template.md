## What and why
<!-- One or two sentences. Link the issue. -->

## Area
- [ ] Android app
- [ ] ESP32 firmware
- [ ] Router / setup
- [ ] Website
- [ ] CI / docs only

## Verification
<!-- Paste the output (or the last lines) of the commands you ran, from CONTRIBUTING.md / CLAUDE.md section 3. -->

## Checklist
- [ ] I ran the checks for every area I touched and they are clean
- [ ] No generated or published files were edited by hand (`WebAssets.h`, `setup/piso-setup.sh`, `website/update/*`, ...)
- [ ] No keys, passwords or tokens are included
- [ ] If this changes the box <-> phone protocol, both sides and the test vectors changed here
- [ ] If this changes money or time accounting, I explained how I checked it
- [ ] I raised `PISO_FW_VERSION` (firmware) or `setup/RELEASE` (router) if this should reach existing devices
