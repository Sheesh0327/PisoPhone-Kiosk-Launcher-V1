# .github: CI and repository settings

Workflows, templates and code owners for GitHub. The rules for changing them are at the end.

## Workflows (`workflows/`)
| Workflow (file) | Runs on | What it does |
|---|---|---|
| Code quality (`quality.yml`) | Pushes and pull requests to `main` and `beta`, when code, scripts or the file changed | Style, unit tests, the firmware build, the router program and router tests, website tests and the repository security rules. Only the jobs for the parts a change touches run. |
| Build and Deploy APK to Website (`build-apk.yml`) | Pushes to any branch that change the app, the protocol or the Gradle files; by hand | Builds and tests the app, signs it and publishes it. On `main` the signing step runs in the protected `production-signing` environment; on other branches it uses a public test key. |
| Emulator tests (`emulator-tests.yml`) | A push to `main`; every night at 02:00 UTC; by hand | Runs the app's instrumented tests on an emulated phone. |
| Coin box images for the setup program (`firmware-images.yml`) | Pushes to `main` and `beta`; by hand | Builds the firmware images the setup program writes to a box and commits them to `website/flash/`. On `main` it also publishes the signed over-the-air update. |
| Router program (all CPU families) (`router-program.yml`) | Pushes that change the router program, router scripts or setup sources; by hand | Builds `pisoportal` for each router CPU family and regenerates the setup files that embed it. A release needs this build to have run on `beta` (`CLAUDE.md` section 5). |
| Publish the router update (`publish-router.yml`) | Pushes to `main`; by hand (with a rollout percent) | Signs the router setup file with the owner key and publishes it to `website/update/`. Routers take it in a staged rollout. |
| Test checklist (`checklist.yml`) | Pushes and pull requests to `main` and `beta` that change the real-world test list or `tools/checklist/` | Checks the tickable test page and writing results back into `docs/REAL_WORLD_TESTING.md`. |
| Real-world test issues (`test-issues.yml`) | Pushes that change `docs/REAL_WORLD_TESTING.md`; by hand | Opens a GitHub issue for each new row of the Issue log and closes it when the row is fixed or won't-fix. |

Each file starts with a comment that says what it does and why. How a release uses them: `docs/RELEASE.md`.

## Other files
- `CODEOWNERS`: who must review a change. `pull_request_template.md`: what a pull request says (the checks are in `CONTRIBUTING.md`).
- `ISSUE_TEMPLATE/`: the bug report and feature request forms. A security problem goes to the private form linked from `config.yml`
  (`SECURITY.md`), not to an issue.
- `dependabot.yml`: weekly Gradle updates (no major jumps; patch updates only for the Kotlin toolchain) and monthly GitHub Actions updates, as pull requests.
- `scripts/sync_test_issues.py`: used by `test-issues.yml`.

## Rules for changing them
The signing keys exist only in the protected `production-signing` environment, which `build-apk.yml`, `firmware-images.yml` and
`publish-router.yml` use (`docs/KEYS.md`, `docs/RELEASE.md`). Do not change a workflow without the owner, and run
`bash scripts/check_security_rules.sh`, which checks the workflows. A change to `router-program.yml` or the paths it watches changes what
routers install (`CLAUDE.md` section 5).
