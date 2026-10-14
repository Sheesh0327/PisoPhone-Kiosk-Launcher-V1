# Protecting the coin slot against fake pulses ("pitik lighter")

A lighter or piezo igniter held near a coin acceptor sends a burst of electrical noise down the acceptor's signal wire. The box can read
that as coins. Two things defend against it: how the box is built (the wiring, which matters most) and the box's own **spark protection**
(firmware 3.5.6 and newer). Neither is a guarantee, and a thief who can open the case and short the signal wire by hand can still fake
pulses: that needs a tamper-proof case and a lock, not software.

## What the box already did
- The coin acceptor is powered only while a customer's slot is armed, so a spark at any other time does nothing.
- Pulses in the first moments after the acceptor powers up are ignored.
- A pulse counts only if the line is still low when the box looks at it, and not more than once per 10 ms.

## Spark protection (new)
A real coin pulse holds the signal line low for a fixed time, 20 ms or more. A spark or a noise spike pulls it low for microseconds up to a
few milliseconds. The box now **measures how long every pulse holds the line low**.

- **Off (the default):** pulses are counted exactly as before. The box only measures and shows the widths.
- **On:** a pulse is counted only when the line stayed low for at least the **shortest real coin pulse** you set (default 12 ms,
  allowed 5 to 40 ms). Shorter ones are ignored.

Where: the box dashboard, **Settings > Hardware pins > Spark protection** and **Shortest real coin pulse (ms)**. Below the limit field the
box prints what it has measured since it started: how many pulses fell in each width range (under 2 ms, 2-8, 8-15, 15-30, 30-80, 80 and
longer) and the shortest and longest. The same numbers are in the diagnostics (`coin_pulse`), and the diagnostics log says
`N pulse(s) too short to be a coin` whenever it sees noise.

### Setting it up on a box (do this once per box and coin acceptor)
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

## Wiring: where the real protection is
Software cannot make up for a wire that picks up noise. In rough order of value:
1. **A short signal wire**, twisted with its ground (twisted pair), or shielded cable with the shield grounded at the box end only.
2. **Keep the signal wire away** from the acceptor's power wires, the relay and any mains wiring.
3. **A metal case, earthed**, with the acceptor and the box inside it; a spark outside a closed metal case does little.
4. **An optocoupler** between the acceptor's signal and the ESP32 input, so the noise cannot reach the board directly.
5. **A small RC filter** on the signal input (for example 10 kOhm in series and 100 nF to ground: about 1 ms, which removes microsecond
   spikes and leaves a 20 ms pulse alone), and a **TVS diode** or a ferrite bead on the wire.
6. **A separate, clean supply** for the acceptor, and a relay board with an opto-isolated input.
Check your own acceptor's manual for its output type and voltage before adding any part; do not connect a 12 V signal straight to the box.

## What can still go wrong
- A pulse train that is made on purpose with the right width (touching the signal wire to ground at the right rhythm) looks like a real coin.
  The only defences are a locked case and the box's other records: the revenue ledger against the box's coin count (`reconcile`) and the
  Telegram alerts, which show a total that does not match the cash.
- With the protection on and the limit set too high, real coins are ignored.
