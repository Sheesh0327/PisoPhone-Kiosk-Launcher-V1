# Feature 039, task group 1: the inventory (the "before")

Taken from `docs/REAL_WORLD_TESTING.md` at the commit this branch started from (`bf7612f`, 202 lines), before any edit. Nothing was judged
yet. "Referenced by" lists the other places that name the number: feature folders by number, other files by name (`rg -n -o "\bRS[0-9]+\b"`
over `specs/ docs/ CHANGELOG.md tools/ scripts/ .github/`).

## Every `RS` number in the document: 50 rows, 50 distinct numbers

State is the checkbox as it stands: `ticked` = `[x]`, `owed` = `[ ]`. Release is the bracket the row itself carries, copied, not judged.

| RS | Line | Checkbox | Release the row names | Referenced by |
|---|---|---|---|---|
| RS1 | 143 | ticked | - | - |
| RS2 | 144 | ticked | - | - |
| RS3 | 145 | ticked | - | - |
| RS4 | 146 | ticked | - | - |
| RS5 | 147 | ticked | - | - |
| RS6 | 148 | owed | - | - |
| RS7 | 149 | owed | - | build.py |
| RS8 | 150 | ticked | - | build.py |
| RS9 | 151 | ticked | - | build.py |
| RS10 | 152 | owed | - | - |
| RS11 | 153 | ticked | - | RELEASE.md |
| RS12 | 154 | ticked | - | RELEASE.md |
| RS13 | 155 | owed | needs your owner key in `tools/pisoportal/owner_key.b64`, then a router built from that | - |
| RS14 | 156 | owed | - | - |
| RS15 | 157 | ticked | - | - |
| RS16 | 158 | owed | - | - |
| RS17 | 159 | owed | - | - |
| RS18 | 160 | ticked | - | - |
| RS19 | 161 | owed | - | - |
| RS20 | 162 | owed | - | - |
| RS21 | 163 | owed | router 1.5.0, box firmware 3.6.0 | - |
| RS22 | 164 | owed | router 1.5.9/1.5.10, not tried yet | - |
| RS23 | 165 | owed | router 1.5.10, not tried yet | - |
| RS24 | 166 | owed | router 1.5.11, box firmware 3.8.0, not tried yet | - |
| RS25 | 167 | owed | router 1.5.12, box firmware 3.9.0, phone app, not tried yet | - |
| RS26 | 169 | owed | phone app, not tried yet | 004 |
| RS27 | 168 | owed | box firmware 3.10.0, not tried yet | roadmap.md |
| RS28 | 170 | owed | box firmware 3.11.1, not tried yet | 004,039,RELEASE_STATE.md,STATUS.md,notes/2026-10-21_feature-004-firmware.md,replans/2026-10-22.md,spec_03_overhaul.md |
| RS29 | 171 | owed | box firmware 3.11.1, not tried yet | 004 |
| RS30 | 172 | owed | box firmware 3.11.1, not tried yet | 004,040 |
| RS31 | 173 | owed | box firmware 3.11.1, not tried yet | 004,RELEASE_STATE.md,STATUS.md,notes/2026-10-21_feature-004-firmware.md |
| RS32 | 174 | owed | router 1.5.13, not tried yet | 001,004,010,CHANGELOG.md,RELEASE_STATE.md,STATUS.md,notes/2026-10-21_feature-004-firmware.md,replans/2026-10-21.md |
| RS33 | 175 | owed | phone app, dev build, not tried yet | 004,RELEASE_STATE.md,STATUS.md,notes/2026-10-21_feature-004-firmware.md,replans/2026-10-21.md |
| RS34 | 176 | ticked | retired: router 1.5.25, feature 033 | 005,006,007,009,033,039,CHANGELOG.md,RELEASE_STATE.md,STATUS.md,replans/2026-10-22.md,spec_03_overhaul.md |
| RS35 | 177 | owed | router 1.5.17, not tried yet; nothing of feature 010 has run on a router | 010,CHANGELOG.md,RELEASE_STATE.md,STATUS.md |
| RS38 | 178 | owed | box firmware 3.12.0 and the phone app with feature 013, not tried yet | 013,CHANGELOG.md,STATUS.md |
| RS39 | 179 | owed | an old phone build with the new box, and a new phone build with a live 3.11.1 box; not tri | 013,CHANGELOG.md,STATUS.md |
| RS44 | 181 | owed | router 1.5.19, not tried yet; nothing of feature 015 has run on a router | 015,016,022,CHANGELOG.md,RELEASE_STATE.md,notes/2026-10-24_features-022-024.md |
| RS45 | 184 | owed | router 1.5.19, not tried yet | 021,CHANGELOG.md |
| RS46 | 182 | owed | coin box firmware 3.12.1, not tried yet; nothing of feature 017 has run on a box | 017,CHANGELOG.md |
| RS47 | 185 | owed | router 1.5.20, not tried yet; nothing of feature 022 has run on a router | 022,CHANGELOG.md,RELEASE_STATE.md,notes/2026-10-24_features-022-024.md |
| RS48 | 188 | owed | phone app, not tried yet; nothing of feature 023 has run on a phone | 023,CHANGELOG.md,RELEASE_STATE.md |
| RS49 | 187 | owed | firmware 3.12.3 and the phone app, not tried yet; nothing of feature 024 has run on a box  | 024,CHANGELOG.md,RELEASE_STATE.md |
| RS50 | 186 | owed | firmware 3.12.2, not tried yet | 026,CHANGELOG.md,RELEASE_STATE.md |
| RS51 | 189 | owed | phone app, not tried yet; feature 027 | 027,CHANGELOG.md,RELEASE_STATE.md |
| RS52 | 190 | owed | firmware 3.12.4 and the setup page, not tried yet; feature 028 | 028,CHANGELOG.md,RELEASE_STATE.md,spec_03_overhaul.md |
| RS53 | 191 | owed | phone app, not tried yet; feature 029 | 029,032,039,043,CHANGELOG.md |
| RS54 | 192 | owed | phone app, not tried yet; feature 030 | 030,039,040,043,CHANGELOG.md |
| RS55 | 193 | owed | phone app, not tried yet; feature 031 | 031,032,039,040,041,043,CHANGELOG.md |
| RS56 | 194 | owed | router program, not tried yet; feature 032: nothing of it has run on a real router | 032,040,STATUS.md |

Totals: 38 unticked, 12 ticked (one of them, RS34, is the feature 033 retirement).

## Numbers that other files use but the document does not have
`RS36` and `RS37` (feature 011, `specs/features/011-offline-setup/validation.md` criterion 14 says the owner ran both on 2026-10-23 and
reported them passed), and `RS40` to `RS43` (feature 019 and `specs/spec_02_always_on.md` AC6, soak checks "to be written"). None of them
has a row in the document, in this checkout or in its git history (`git log -S"RS36" -- docs/REAL_WORLD_TESTING.md` finds nothing).
They cannot be retired, because there is nothing to retire. See the notes in the document.

## The other checklist ids (not `RS`), before
Unticked (12): B9, C3b, C8, F8, S1, S2, S3, S4, S5, S6, H2, R4.
Ticked (46): B1, B2, B3, B4, B5, B6, B7, B8, P1, P2, P3, P4, P5, P6, P8, P7, C1, C2, C3, M3a, M3b, C4, C5, C6, C7, F1, F2, F3, F4, F5, F6, F7, K1, K2, K4, K5, H4a, H4b, H4c, H4d, R1, R2, R3, R5, L1, L2.
Section 10 ("PisoPhone only setup") has 5 unticked lines with no id at all (lines 198 to 202).
