# PisoPhone workshop pilot

The operator flow runs at https://pisophone-accounts-test.pages.dev/account/pilot/activate/ and uses the beta Supabase account session.

1. In **Workshop registration**, staff enter the prepared ESP32 MAC address and register a kit. Use **Save credential over USB** and record the claim code for the kit card before leaving the page. To assign a prepared replacement, enter the existing kit ID and the new board's MAC address. The kit keeps its licensed slot count.
2. Sign in as the operator and claim the kit with the card. Choose the assigned board in **My kits**. For a replacement, give the workshop the kit ID, then use **Activate replacement** after the assignment appears. The old ESP32 retires when the replacement acknowledges the license through the beta service.
3. In desktop Chrome or Edge, use **Save Wi-Fi over USB** to connect the assigned ESP32 to the shop's 2.4 GHz network. The website sends the Wi-Fi details directly to the board. Both Wi-Fi setup and credential provisioning check the connected ESP32 MAC and workshop firmware before sending configuration.
4. Use **Refresh kit status** to see whether the beta service accepted activation. Use **Check ESP32 over USB** to verify the license saved locally and get the dashboard link. On the same Wi-Fi, open the dashboard and verify a paired rental phone is online under **Tools → Phone slots**.

## Beta services

- Apply `supabase/pisophone-pilot.sql` once to the beta Supabase project after the storefront schema. Set the singleton `piso_pilot.service_credential.secret_sha` to the SHA-256 hex digest of the dedicated licensing service credential. The RPC checks this credential before looking up a board.
- Deploy `pilot-worker/wrangler.jsonc` only as `pisophone-pilot-api-test`. Set `PILOT_SUPABASE_URL` and `PILOT_SUPABASE_PUBLISHABLE_KEY` from the beta site config. Provision `PILOT_DATABASE_CREDENTIAL` and `PILOT_RECEIPT_KEY` as separate random 32-byte lowercase hex secrets. `PILOT_GRANT_PRIVATE_JWK` is a dedicated ECDSA P-256 private JWK. The beta public key is in `pilot-worker/grant-public-key.spki.b64`.
- Keep the dedicated signing key and workshop credentials out of source, scripts, output, and public pages. Retain the existing Worker secrets on beta redeploys.

## Board check-in contract

Send `GET /v1/check-in` to the beta pilot Worker over verified TLS with the assigned board credential in the Bearer header. A board may receive `awaiting_claim`, `awaiting_operator`, `grant_available`, `active`, or `retired`.

The signed grant message is ASCII `piso-pilot-v1|kind|kit-uuid|mac|revision|slot-limit` with a SHA-256 ECDSA signature in Base64 DER. Verify the signature against the pinned beta public key and verify the board MAC and revision. An `offer` is staged separately from an `active` grant. Persist the verified offer and its receipt before replying with `POST /v1/check-in` and headers `X-Pilot-Saved-Revision` and `X-Pilot-Grant-Receipt`. The same acknowledgement can be retried. Only a verified, saved `active` grant enables paid operation. Save a verified `retired` state across restart and refuse a replay of a previous active grant.

## Hardware acceptance

Build the dedicated bench firmware with `pio run -d esp32_firmware -e esp32-c3-workshop-pilot`. Its USB CLI accepts `PISO-PILOT-CREDENTIAL <credential>` for the assigned board and reports `PISO-PILOT-STATUS` without printing its credential. Verify the hardware identity over USB before provisioning. The pilot target requires a saved active grant to arm the acceptor and excludes browser OTA. The first ESP32-C3 was verified on shop Wi-Fi through activation and an offline reboot. A coin-funded session and a physical replacement still require testing.

The Android app still needs kit enrollment and proof that a discovered replacement is assigned to that kit. Verify reconnection to the same slot after the old board is unplugged. Check existing shop behavior with the release builds. Keep pilot builds out of the production update feeds. Evaluate hardware secure boot and protected credential storage before qualifying a shipping board.

Run `npm test` from the account-site directory to exercise the Postgres lifecycle and signed grant contract locally.
