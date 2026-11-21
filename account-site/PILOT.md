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

The browser and licensing service cover kit ownership, same-kit slot capacity, and board cutover. The ESP32 firmware still needs to implement check-in and enforce the verified grant. The Android app still needs kit enrollment and proof that a discovered replacement is assigned to that kit. Use dedicated boards and phones to verify both, including reconnecting to the same slot after the old board is unplugged. Check existing shop behavior with the unmodified release builds. Keep pilot builds out of the production update feeds.

Run `npm test` from the account-site directory to exercise the Postgres lifecycle and signed grant contract locally.
