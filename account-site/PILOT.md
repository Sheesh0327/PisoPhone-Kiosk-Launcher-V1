# PisoPhone workshop pilot

The operator flow runs at https://pisophone-accounts-test.pages.dev/account/pilot/activate/. It uses the beta Supabase account session. Workshop staff register dedicated hardware and issue the claim card. The claiming operator can see only their own kits, request activation of an assigned replacement, and refresh the board's confirmed state.

## Beta services

- Apply `supabase/pisophone-pilot.sql` once to the beta Supabase project after the storefront schema. Set the singleton `piso_pilot.service_credential.secret_sha` to the SHA-256 hex digest of the dedicated licensing service credential. The RPC checks this credential before looking up a board.
- Deploy `pilot-worker/wrangler.jsonc` only as `pisophone-pilot-api-test`. Set `PILOT_SUPABASE_URL` and `PILOT_SUPABASE_PUBLISHABLE_KEY` from the beta site config. Provision `PILOT_DATABASE_CREDENTIAL` and `PILOT_RECEIPT_KEY` as separate random 32-byte lowercase hex secrets. `PILOT_GRANT_PRIVATE_JWK` is a dedicated ECDSA P-256 private JWK. The beta public key is in `pilot-worker/grant-public-key.spki.b64`.
- Keep the dedicated signing key and workshop credentials out of source, scripts, output, and public pages. Retain the existing Worker secrets on beta redeploys.

## Board check-in contract

Send `GET /v1/check-in` to the beta pilot Worker over verified TLS with the assigned board credential in the Bearer header. A board may receive `awaiting_claim`, `awaiting_operator`, `grant_available`, `active`, or `retired`.

The signed grant message is ASCII `piso-pilot-v1|kind|kit-uuid|mac|revision|slot-limit` with a SHA-256 ECDSA signature in Base64 DER. Verify the signature against the pinned beta public key and verify the board MAC and revision. An `offer` is staged separately from an `active` grant. Persist the verified offer and its receipt before replying with `POST /v1/check-in` and headers `X-Pilot-Saved-Revision` and `X-Pilot-Grant-Receipt`. The same acknowledgement can be retried. Only a verified, saved `active` grant enables paid operation. Save a verified `retired` state across restart and refuse a replay of a previous active grant.

## Hardware acceptance

Build the dedicated bench firmware with `pio run -d esp32_firmware -e esp32-c3-workshop-pilot`. Its USB CLI accepts `PISO-PILOT-CREDENTIAL <credential>` for the assigned board and reports `PISO-PILOT-STATUS` without printing its credential. Verify the hardware identity over USB before provisioning. The pilot target requires a saved active grant to arm the acceptor and excludes browser OTA. ESP32-C3 bench boot and the initial zero-slot state have been verified. A licensed coin session and offline reboot still require testing on shop Wi-Fi.

The Android app still needs kit enrollment and proof that a discovered replacement is assigned to that kit. Verify reconnection to the same slot after the old board is unplugged. Check existing shop behavior with the release builds. Keep pilot builds out of the production update feeds. Evaluate hardware secure boot and protected credential storage before qualifying a shipping board.

Run `npm test` from the account-site directory to exercise the Postgres lifecycle and signed grant contract locally.
