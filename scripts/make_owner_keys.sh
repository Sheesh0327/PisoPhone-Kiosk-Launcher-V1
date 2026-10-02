#!/bin/sh
# Creates YOUR signing key (licenses and firmware updates) on YOUR computer. Run it locally, never in CI or a
# cloud session: the private key must exist only on machines you control.
#
#   sh scripts/make_owner_keys.sh                 # key goes to ~/pisophone_license_key.pem
#   sh scripts/make_owner_keys.sh /path/key.pem   # or somewhere else (not inside this repository)
#
# It refuses to overwrite an existing key, keeps the key out of the repository, writes the PUBLIC key into
# esp32_firmware/include/LicensePubKey.h, and proves the key works by signing and checking a test license.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="${1:-$HOME/pisophone_license_key.pem}"
HEADER="${PUBKEY_HEADER:-$ROOT/esp32_firmware/include/LicensePubKey.h}"

case "$(cd "$(dirname "$KEY")" 2>/dev/null && pwd)/$(basename "$KEY")" in
  "$ROOT"/*) echo "Refusing: $KEY is inside the repository. Pick a folder outside it." >&2; exit 1 ;;
esac
python3 -c "import cryptography" 2>/dev/null || { echo "Needs: pip install cryptography" >&2; exit 1; }
[ ! -e "$KEY" ] || { echo "Refusing: $KEY already exists. Move it away first; replacing it makes every issued license invalid." >&2; exit 1; }

python3 "$ROOT/scripts/generate_license.py" keygen --private "$KEY" --header "$HEADER"
chmod 600 "$KEY"

# Self-test: sign a license for a dummy box and make sure a token is produced.
TOKEN=$(python3 "$ROOT/scripts/generate_license.py" issue --private "$KEY" --mac AA:BB:CC:DD:EE:FF --slots 1 | tail -n 1)
case "$TOKEN" in PISOLIC1.*) echo "Self-test passed (signed a test license)." ;; *) echo "Self-test FAILED" >&2; exit 1 ;; esac

cat <<EOT

Done. Next steps:
  1. BACK UP $KEY now, in two separate offline places (a USB stick in a drawer, a password manager attachment).
     Lose it and no box can ever take a new license or update; leak it and anyone can forge both.
  2. Commit ONLY the public key:   git add esp32_firmware/include/LicensePubKey.h && git commit -m "Install owner public key"
  3. Rebuild and flash every box:  cd esp32_firmware && pio run -e esp32-c3-dev -t upload
  4. Issue a license:              python3 scripts/generate_license.py issue --private $KEY --code <box request code> --slots N
  5. Sign a firmware build:        python3 scripts/sign_firmware.py --private $KEY --chip esp32c3 --image <firmware.bin>
EOT
