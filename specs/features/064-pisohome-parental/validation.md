# Validation: Feature 064 — PisoHome (`PisoKids`)

Criteria from `specs/features/064-pisohome-parental/spec.md`:

| # | Criterion | How checked | State |
|---|---|---|---|
| 1 | Firmware pure rules (`ChoreBoard.h`, `CurfewRules.h`, `PiggyGoal`) pass host unit tests | Host tests execution & compile | **passed** |
| 2 | Product strip isolation: `PISO_PRODUCT_HOME` excludes commercial code | `python3 scripts/check_product_strip.py` | **passed** |
| 3 | 3-Tab Family Dashboard rendered when `PISO_IS_HOME = 1`; curfew rejects arming with HTTP 423 | `python3 scripts/embed_web.py --check` & firmware tests | **passed** |
| 4 | Phone-to-phone pairing with `home_mode` and clickable `chrome://flags` link with 1-tap copy | `node scripts/tests/test_provisioning.js` | **passed** |
| 5 | Android pure parental rules, Tailscale policy & session pause unit tests pass | `./gradlew :app:testDebugUnitTest` | **passed** |
| 6 | Android engine curfew auto-pause, chore crediting, and `/parent` console functional | `./gradlew :app:testDebugUnitTest` | **passed** |
| 7 | Tailscale remote management console restricted to `100.64.0.0/10` and authenticated by PIN | Unit tests in `KioskHttpServerUnitTest.kt` | **passed** |
| 8 | `PisoKids` Dual-Hero lock screen hub and floating pill with Pause & Save Time compile | `./gradlew :app:compileDebugUnitTestKotlin` | **passed** |
| 9 | Repository security rules, dead links, and release integrity pass cleanly | `bash scripts/check_security_rules.sh` & `python3 scripts/check_links.py` | **passed** |

## Verification runs

### 1. Firmware Product Isolation
```powershell
& "C:\Users\User\.platformio\penv\Scripts\python.exe" scripts\check_product_strip.py
# Output:
# phone: no trace of the other product
# wifi: no trace of the other product
# home: no trace of the other product
```

### 2. Web Assets Sync
```powershell
& "C:\Users\User\.platformio\penv\Scripts\python.exe" scripts\embed_web.py --check
# Output:
# WebAssets.h is up to date.
```

### 3. Provisioning & WebADB Phone-to-Phone Tests
```powershell
& "E:\NodeJS\node.exe" scripts\tests\test_provisioning.js
# Output:
# 58 checks, 0 failures
```

### 4. Android Unit Test Suite & Flavor Verification
```powershell
$env:JAVA_HOME = "C:\Users\User\.jdks\jbr-21.0.11"
$env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
# Output:
# BUILD SUCCESSFUL in 49s (testCommercialDebugUnitTest, testHomeDebugUnitTest, lintDebug all passed)
```
Including:
- `ParentalRulesTest`: curfew evaluation across midnight boundaries, chore JSON codecs, piggy bank savings goals, allowed apps whitelist.
- `TailscalePolicyTest`: `100.64.0.0/10` CGNAT range validation, localhost loopback verification, remote parent access control.
- `SessionRulesTest`: session pause state transitions (`afterPause` keeping held time).
- `PaymentRepositoryUnitTest`: pause active running clock into `heldSeconds` idempotently.
- `KioskHttpServerUnitTest`: remote `/parent` console, PIN verification, 401/403 security enforcement, chore approval bonus time credit.

### 5. Repository Dead Links Check
```powershell
& "C:\Users\User\.platformio\penv\Scripts\python.exe" scripts\check_links.py
# Output:
# 0 dead link(s)
```

### 6. Repository Security Rules
```powershell
& "E:\Git\bin\bash.exe" scripts/check_security_rules.sh
# Output:
# ok: 8 workflows, no signing step follows a git reset --hard; publish-router.yml checks the router program first
# security rules: all passed
```
