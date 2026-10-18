# Phone slot licensing (PisoHub coin box firmware 3.10.0 and later)

Every coin box includes **2 free phone slots**. Each further slot needs one **license key**, sold at **P500**, up to **12 slots** in all
(so at most 10 keys per box). The price is a number in the dashboard text (`SlotLicense.h`, `PRICE_PHP`); what you charge is up to you.

## How it works
* A key is one line, `PSLOT1.<box code>.<key number>.<signature>`, signed by the owner key (`docs/KEYS.md`).
* It names **one box** (the box code is the box's MAC address, shown on its admin page under *Phone slots*), so it is no use on any other box.
* It has its own number, so it opens **one** slot, **once**: pasting it twice opens one slot; editing the number breaks the signature.
* The box checks the signature itself with the public key built into its software. No internet, no server, no account.
* Used key numbers are kept in the box's memory. They survive the operator's factory reset (like the sales totals); only writing the
  software again, or the owner's full wipe, erases them (then *Re-issue* gives the customer the same keys back, below).
* Phones already paired are never unpaired by the limit; it only refuses to pair one more.
* There is no password, QR code or admin setting that opens a slot without a key. The only way to more slots is a key you signed.

## Selling a slot
1. The customer opens their admin page, *Phone slots*, and reads you the **box code** (or sends a photo of that line).
2. They pay P500 per slot.
3. You open the setup program's *Owner tools > Slot licenses*, type the box code, how many keys and a note (name or receipt number). Or on the command line: `python3 scripts/make_slot_licenses.py --private ~/pisophone_license_key.pem --box AA:BB:CC:DD:EE:FF --count 2 --ledger ~/pisophone_licenses.csv`.
4. Send them the printed line(s). They paste one into *Phone slots > Add slot* (one key per paste). The slot opens at once.

Every key you make is written in `~/pisophone_licenses.csv` (date, box code, key number, note): that is your sales record and the proof
for a dispute. The program refuses to make more than 10 keys for one box.

## When the box was erased
If a customer re-flashes their board, the used-key list is gone, but the keys they bought still work: press *Re-issue the keys already sold to this box* in the Owner tools. It prints the keys already sold to that box (new signatures, the same key numbers),
and they paste them again. They never get more slots than the ledger says were sold.

## Boxes already in shops (grandfathering)
On its first start with licensing, a box records how many phones it has paired (up to 12) and never offers fewer slots than that. A shop that
had 6 phones keeps 6 slots and can still replace a broken phone; keys add slots only above what the box already has. A new box records 0 and has
the 2 free slots. The number is kept through the operator's factory reset (like the used keys); only the owner's full wipe starts it again.

## What a key can not do
* A key can not be moved to another box, so a customer can not resell it.
* Someone who copies the key from a chat message can use it only on the box it names.
* Anyone who can sign with your owner key can make keys. Keep the key offline (`docs/KEYS.md`). If it leaks, new software with a new
  public key is needed; keys signed with the old key stop working on updated boxes.

## Tests
`esp32_firmware/host_tests/slot_license_test.cpp` (the box's rules), `router/tests/test_setup_tools.py` (the setup program's key maker). Not yet tried on a real box: paste a key and check *Phone slots*.
