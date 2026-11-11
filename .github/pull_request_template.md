## What and why
<!-- One or two sentences. Link the issue. -->

## Spec and approval
<!-- Link the OpenSpec change and relevant legacy feature ID, if any. Link evidence.md and the owner's approval and scope; checked tasks alone are not approval. Explain any applicable exception. -->

## Area
- [ ] Android app
- [ ] ESP32 firmware
- [ ] Router / setup
- [ ] Website
- [ ] CI / docs only

## Verification
<!-- Link criterion-by-criterion results and evidence, including not-run reasons and outstanding hardware checks. Give commands and results for the applicable checks in CONTRIBUTING.md. -->

## Checklist
- [ ] I ran the checks for every area I touched and they are clean, or stated what could not run
- [ ] No generated or published files were edited by hand (`WebAssets.h`, `setup/piso-setup.sh`, `website/update/*`, ...)
- [ ] No keys, passwords or tokens are included
- [ ] If this changes the box <-> phone protocol, both sides and the test vectors changed here
- [ ] If this changes money or time accounting, I explained how I checked it
- [ ] I raised `PISO_FW_VERSION` (firmware) or `setup/RELEASE` (router) if this should reach existing devices

<!-- Spec-only PRs should include the Specification integrity result and any outstanding historical evidence gaps. -->
