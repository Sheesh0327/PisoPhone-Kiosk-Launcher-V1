# Reference

Four short topics in one file: protecting the coin slot, phone slot licensing, print server pairing and the super-admin QR login. The router gateway API is `docs/api/gateway-coinslot.md`.

## Protecting the coin slot against fake pulses ("pitik lighter")

A lighter or piezo igniter held near a coin acceptor sends a burst of electrical noise down the acceptor's signal wire. The box can read
that as coins. Two things defend against it: how the box is built (the wiring, which matters most) and the box's own **spark protection**
(firmware 3.5.6 and newer). Neither is a guarantee, and a thief who can open the case and short the signal wire by hand can still fake
pulses: that needs a tamper-proof case and a lock, not software.

### What the box already did
- The coin acceptor is powered only while a customer's slot is armed, so a spark at any other time does nothing.
- Pulses in the first moments after the acceptor powers up are ignored.
- A pulse counts only if the line is still low when the box looks at it, and not more than once per 10 ms.

### Spark protection (new)
A real coin pulse holds the signal line low for a fixed time, 20 ms or more. A spark or a noise spike pulls it low for microseconds up to a
few milliseconds. The box now **measures how long every pulse holds the line low**.

- **Off (the default):** pulses are counted exactly as before. The box only measures and shows the widths.
- **On:** a pulse is counted only when the line stayed low for at least the **shortest real coin pulse** you set (default 12 ms,
  allowed 5 to 40 ms). Shorter ones are ignored.

Where: the box dashboard, **Settings > Hardware pins > Spark protection** and **Shortest real coin pulse (ms)**. Below the limit field the
box prints what it has measured since it started: how many pulses fell in each width range (under 2 ms, 2-8, 8-15, 15-30, 30-80, 80 and
longer) and the shortest and longest. The same numbers are in the diagnostics (`coin_pulse`), and the diagnostics log says
`N pulse(s) too short to be a coin` whenever it sees noise.

#### Setting it up on a box (do this once per box and coin acceptor)
1. With the protection **off**, insert a few real coins of each kind. Open the settings page and read the widths. Real coins land in one
   range (for example all in 15-30 ms) and the shortest should be near 20 ms. Pulse widths depend on the acceptor model and its
   settings, so measure yours.
2. Set **Shortest real coin pulse** a little under the shortest real pulse you measured (for example 12 ms if real pulses are 20 ms and
   up). It must not be as long as a real pulse, or real coins would be ignored.
3. Test the noise: with the protection **off**, arm the slot (a phone presses Insert Coin, or the Wi-Fi page) and click a lighter near the
   slot. Look at the diagnostics: pulses under 8 ms are noise, and with the protection off they may be counted as coins. Then switch the
   protection **on** and repeat: they must show as ignored and no coins must be credited.
4. Insert real coins again and check that every one is credited. If a real coin is ever missed, lower the limit or switch the protection
   off and tell the maker.

Until you have done steps 1 to 4 on your own box, leave the protection off: it changes how pulses are counted, and a limit that is too high
would lose real coins. Nothing here has been tested on real hardware by the maker yet (only the width rules are tested on a computer), so
treat the first box as a trial.

### Wiring: where the real protection is
Software cannot make up for a wire that picks up noise. In rough order of value:
1. **A short signal wire**, twisted with its ground (twisted pair), or shielded cable with the shield grounded at the box end only.
2. **Keep the signal wire away** from the acceptor's power wires, the relay and any mains wiring.
3. **A metal case, earthed**, with the acceptor and the box inside it; a spark outside a closed metal case does little.
4. **An optocoupler** between the acceptor's signal and the ESP32 input, so the noise cannot reach the board directly.
5. **A small RC filter** on the signal input (for example 10 kOhm in series and 100 nF to ground: about 1 ms, which removes microsecond
   spikes and leaves a 20 ms pulse alone), and a **TVS diode** or a ferrite bead on the wire.
6. **A separate, clean supply** for the acceptor, and a relay board with an opto-isolated input.
Check your own acceptor's manual for its output type and voltage before adding any part; do not connect a 12 V signal straight to the box.

### What can still go wrong
- A pulse train that is made on purpose with the right width (touching the signal wire to ground at the right rhythm) looks like a real coin.
  The only defences are a locked case and the box's other records: the revenue ledger against the box's coin count (`reconcile`) and the
  Telegram alerts, which show a total that does not match the cash.
- With the protection on and the limit set too high, real coins are ignored.

## Phone slot licensing (PisoHub coin box firmware 3.10.0 and later)

Every coin box includes **2 free phone slots**. Each further slot needs one **license key**, sold at **P500**, up to **12 slots** in all
(so at most 10 keys per box). The price is a number in the dashboard text (`SlotLicense.h`, `PRICE_PHP`); what you charge is up to you.

### How it works
* A key is one line, `PSLOT1.<box code>.<key number>.<signature>`, signed by the owner key (`docs/KEYS.md`).
* It names **one box** (the box code is the box's MAC address, shown on its admin page under *Phone slots*), so it is no use on any other box.
* It has its own number, so it opens **one** slot, **once**: pasting it twice opens one slot; editing the number breaks the signature.
* The box checks the signature itself with the public key built into its software. No internet, no server, no account.
* Used key numbers are kept in the box's memory. They survive the operator's factory reset (like the sales totals); only writing the
  software again, or the owner's full wipe, erases them (then *Re-issue* gives the customer the same keys back, below).
* Phones already paired are never unpaired by the limit; it only refuses to pair one more.
* There is no password, QR code or admin setting that opens a slot without a key. The only way to more slots is a key you signed.

### Selling a slot
1. The customer opens their admin page, *Phone slots*, and reads you the **box code** (or sends a photo of that line).
2. They pay P500 per slot.
3. You open the setup program's *Owner tools > Slot licenses*, type the box code, how many keys and a note (name or receipt number). Or on the command line: `python3 scripts/make_slot_licenses.py --private ~/pisophone_license_key.pem --box AA:BB:CC:DD:EE:FF --count 2 --ledger ~/pisophone_licenses.csv`.
4. Send them the printed line(s). They paste one into *Phone slots > Add slot* (one key per paste). The slot opens at once.

Every key you make is written in `~/pisophone_licenses.csv` (date, box code, key number, note): that is your sales record and the proof
for a dispute. The program refuses to make more than 10 keys for one box.

### When the box was erased
If a customer re-flashes their board, the used-key list is gone, but the keys they bought still work: press *Re-issue the keys already sold to this box* in the Owner tools. It prints the keys already sold to that box (new signatures, the same key numbers),
and they paste them again. They never get more slots than the ledger says were sold.

### Boxes already in shops (grandfathering)
On its first start with licensing, a box records how many phones it has paired (up to 12) and never offers fewer slots than that. A shop that
had 6 phones keeps 6 slots and can still replace a broken phone; keys add slots only above what the box already has. A new box records 0 and has
the 2 free slots. The number is kept through the operator's factory reset (like the used keys); only the owner's full wipe starts it again.

### What a key can not do
* A key can not be moved to another box, so a customer can not resell it.
* Someone who copies the key from a chat message can use it only on the box it names.
* Anyone who can sign with your owner key can make keys. Keep the key offline (`docs/KEYS.md`). If it leaks, new software with a new
  public key is needed; keys signed with the old key stop working on updated boxes.

### Tests
`esp32_firmware/host_tests/slot_license_test.cpp` (the box's rules), `router/tests/test_setup_tools.py` (the setup program's key maker). Not yet tried on a real box: paste a key and check *Phone slots*.

## Print server pairing (router asks the print server for its key)

Today the owner copies `/etc/webprint/send.key` from the print server to `/etc/coinslot.d/send.key` on the router by hand. This is the
replacement: the owner types a short code, the router fetches the key itself, and nobody sees a key. **Status: specification.** The
router side (`tools/pisoportal`, console Settings, Print inbox, Connect print server) is built against this; the print server side is
a separate project and must implement `/api/inbox/pair` first. Until it does, the manual copy keeps working and is not removed.

### On the print server
The admin page shows **Pair a router**. Pressing it makes a code and shows it with the time left.

| Rule | Value |
|---|---|
| Code | 6 digits from a cryptographic random source, one code at a time (a new press replaces the old one) |
| Lifetime | 10 minutes, then it is gone |
| Use | once: a correct answer deletes the code |
| Who may ask | only a source address on the print server's trusted-router list (the same list that may call `/api/inbox/upload`) |
| Wrong guesses | 5 per source address per 10 minutes; the 5th wrong guess also deletes the code; answer `429` until the window passes |
| Compare | constant time |

### Calls
`GET /api/inbox/ping` (no code needed, trusted addresses only) answers `{"inbox":true,"pairing":true}`. The router uses it to find the
print server (default `10.0.0.95`) and to tell an old print server (`404`) from a new one.

`POST /api/inbox/pair` with `{"code":"123456"}` (JSON, at most 100 bytes):

| Answer | Meaning |
|---|---|
| `200 {"key":"<the content of send.key>"}` | Paired. Sent with `Cache-Control: no-store`, only on this answer, never logged |
| `403 {"error":"bad_code"}` | Wrong, expired, used or never made: **the same answer for all four**, so a guesser learns nothing |
| `403 {"error":"not_allowed"}` | The source address is not a trusted router |
| `429 {"error":"slow_down"}` | Too many wrong guesses |
| `404` | An older print server without pairing |

The key is the print server's existing router key: pairing does not rotate it, so a print server shared by several shops keeps working.

### On the router
- The console's Settings page has **Connect print server**: the address (prefilled with `SEND_HOST`, default `10.0.0.95`) and the code.
- `POST /api/print/pair {"host","code"}` (owner session): calls the print server, checks the key looks like one (16 or more printable
  characters, as `send.rs` already requires), writes `DATA_DIR/send.key` (mode 600, written to a temporary file then renamed), saves
  `SEND_HOST` when it differs, and answers `{"paired":true}`. Any failure leaves the old key untouched and gives the owner the plain
  reason (wrong code, too many tries, old print server, not reachable). The key is never returned to the console or logged.
- `DELETE /api/print/pair` removes the key file (**Disconnect**). The status the console already shows (`has_key`) turns it into
  "Connected" or "Not connected".
- The setup program's optional last step "Print server" makes the same call through the router.

### Tests (router side, with a fake print server)
Right code stores a working key (one upload works); wrong, expired, reused and `not_allowed` answers store nothing; a short or garbled
key stores nothing; an old print server (`404`) says so; the key never appears in a response or a log line; disconnect removes it.

## Super admin: the owner's admin QR code

Super admin (revenue collection, the vault and the revenue split, installing an older firmware, the full factory wipe) is opened
with **one QR code that only you hold**. There is no password to set, publish or rotate, and no website feed.

### How it works
- `python3 scripts/make_admin_qr.py` makes a key (the first time) and draws the QR code from it. The code holds the **private key**
  (`PISOADMIN1.<d>.<x>.<y>`); keep the picture and the key file (`~/pisophone-superadmin.key`) private.
- The boxes carry only the matching **public key** (`esp32_firmware/include/SuperAdminPubKey.h`), built into the firmware.
- On the box's login page (any browser that has no sign-in yet), **Scan admin QR** opens the phone's camera. The page reads the
  photo with the vendored jsQR, asks the box for a one-time challenge, signs `pisophone-admin-v1|<nonce>|<box id>` with the key
  from the QR code (ECDSA P-256, in `web/adminqr.js`: the box's pages are plain HTTP, where the browser's own crypto is not
  available) and sends **only the signature**. The key never leaves the phone, and a signature is good for one box and one
  challenge for one minute.
- The box checks the signature with its public key and starts a **session**: a random cookie, valid for 15 idle minutes and one
  hour at most. The session is both the dashboard sign-in and super admin; the admin password still works for the normal admin.
  Five wrong tries lock a phone out for a minute, like wrong passwords. "Lock now" in vendor mode, or Sign out, ends it.
- The box's page is reached the usual way: join the hidden kiosk Wi-Fi (`PisoKiosk-xxxx`; its name and password are in the setup summary) with your phone (its password is in the setup summary)
  and open `http://10.0.0.10`.

### Setting it up (once)
1. `python3 scripts/make_admin_qr.py` (needs `pip install cryptography qrcode pillow`). It reuses `~/pisophone-superadmin.key` if
   you made one before, or makes it. It writes `pisophone-admin-qr.png`: print it or keep it on your phone.
2. If it made a **new** key it also rewrote `SuperAdminPubKey.h`: commit that, merge to `main`, and update every box once
   (dashboard **Update > Install update**). A box only trusts the key it was built with.

### If the QR code leaks or is lost
`python3 scripts/make_admin_qr.py --new`, commit the new header, update every box. Until a box is updated it still trusts the old
code. (An old code on a box that has the new key is simply refused.)

### Test
Checklist items S6 and C3b in `docs/REAL_WORLD_TESTING.md`; the logic is covered by `esp32_firmware/host_tests/admin_qr_test.cpp`
and the browser side (signing, reading a photo) by `scripts/tests/test_admin_qr.py`.
