# Print server pairing (router asks the print server for its key)

Today the owner copies `/etc/webprint/send.key` from the print server to `/etc/coinslot.d/send.key` on the router by hand. This is the
replacement: the owner types a short code, the router fetches the key itself, and nobody sees a key. **Status: specification.** The
router side (`tools/pisoportal`, console Settings, Print inbox, Connect print server) is built against this; the print server side is
a separate project and must implement `/api/inbox/pair` first. Until it does, the manual copy keeps working and is not removed.

## On the print server
The admin page shows **Pair a router**. Pressing it makes a code and shows it with the time left.

| Rule | Value |
|---|---|
| Code | 6 digits from a cryptographic random source, one code at a time (a new press replaces the old one) |
| Lifetime | 10 minutes, then it is gone |
| Use | once: a correct answer deletes the code |
| Who may ask | only a source address on the print server's trusted-router list (the same list that may call `/api/inbox/upload`) |
| Wrong guesses | 5 per source address per 10 minutes; the 5th wrong guess also deletes the code; answer `429` until the window passes |
| Compare | constant time |

## Calls
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

## On the router
- The console's Settings page has **Connect print server**: the address (prefilled with `SEND_HOST`, default `10.0.0.95`) and the code.
- `POST /api/print/pair {"host","code"}` (owner session): calls the print server, checks the key looks like one (16 or more printable
  characters, as `send.rs` already requires), writes `DATA_DIR/send.key` (mode 600, written to a temporary file then renamed), saves
  `SEND_HOST` when it differs, and answers `{"paired":true}`. Any failure leaves the old key untouched and gives the owner the plain
  reason (wrong code, too many tries, old print server, not reachable). The key is never returned to the console or logged.
- `DELETE /api/print/pair` removes the key file (**Disconnect**). The status the console already shows (`has_key`) turns it into
  "Connected" or "Not connected".
- The setup program's optional last step "Print server" makes the same call through the router.

## Tests (router side, with a fake print server)
Right code stores a working key (one upload works); wrong, expired, reused and `not_allowed` answers store nothing; a short or garbled
key stores nothing; an old print server (`404`) says so; the key never appears in a response or a log line; disconnect removes it.
