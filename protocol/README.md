# protocol: the box <-> phone message contract

The coin box and the phone app each have their own code for encrypting and signing the messages they send each other. This folder
holds the known answers both must reproduce, so a change to one side that breaks the other fails a test instead of failing in a shop.

## Layout
- `gen_vectors.py`: writes `fixtures/box_phone_v1.json`, using Python's `cryptography` library, independent of both implementations.
- `fixtures/box_phone_v1.json`: the committed vectors (`version`, `encrypt`, `hmac`, `accounts`). The `v1` in the name is the format version.

## The format (version 1)
- key = SHA-256 of the shared secret (UTF-8)
- encrypt = hex(iv) + hex(AES-256-CBC of the PKCS#7 padded plaintext), lower-case hex
- signature = hex(HMAC-SHA256 with the secret over the message), lower-case hex

What each message means and who sends it: `docs/ARCHITECTURE.md` (section 3). The router's own API: `docs/api/gateway-coinslot.md`.

## Who reads the vectors
| Side | Test |
|---|---|
| Coin box firmware | `esp32_firmware/host_tests/protocol_contract_test.cpp` (the header it needs is made from the JSON by `esp32_firmware/host_tests/gen_protocol_fixture.py`) |
| Phone app | `app/src/test/java/com/pisophone/kiosk/security/ProtocolContractUnitTest.kt` |

## Build
Run it only when the format changes on purpose, and then bump the version in the file name:
```
python3 protocol/gen_vectors.py
```
Needs Python 3 with the `cryptography` package.

## Test
CI checks that the committed file is what the script writes:
```
python3 protocol/gen_vectors.py && git diff --exit-code protocol
```
The two tests above then run with the firmware host tests (`sh esp32_firmware/host_tests/run.sh`) and the app unit tests
(`./gradlew :app:testDebugUnitTest`).

## Rule
A change to the message format changes both sides, the vectors and the two tests in one change (`CLAUDE.md` section 6).

## See also
`esp32_firmware/README.md`, `app/README.md`, `CONTRIBUTING.md`.
