"""The 18 page states, the snapshot script and the helpers test_page_golden.py and test_page_branded.py share."""
import os
import re

from harness import HERE

GOLDEN = os.path.join(HERE, "golden", "page_states.json")

# what the portal sends the page in each state (the keys the page's view() reads)
IDLE = {"t": "state", "s": "idle", "left": 0, "plan": "", "vc": False, "vs": [], "sd": False, "cooldown": 0, "busy": 0}
PAID = dict(IDLE, left=7200, plan="endurance", used_mb=0, slow=0, pm=1, pn=0, pl=2, pw=0)
ARMED = {"t": "state", "s": "armed", "plan": "endurance", "pulses": 0, "minutes": 0, "remaining": 20, "total": 30, "v": False}
FINAL = {"t": "state", "s": "final", "plan": "endurance", "online": True, "left": 10800, "pulses": 5, "minutes": 180}
CASES = {
    "idle_new_customer": IDLE,
    "idle_with_voucher_choice": dict(IDLE, vc=True),
    "idle_with_time_left": PAID,
    "idle_another_customer_paying": dict(IDLE, busy=7),
    "idle_cooldown": dict(IDLE, cooldown=30),
    "paused": dict(PAID, s="paused", pm=1, pl=1, by=0),
    "starting": {"t": "state", "s": "starting", "plan": "hyper", "v": False},
    "armed_no_coin": ARMED,
    "armed_with_coins": dict(ARMED, pulses=5, minutes=180),
    "closing": {"t": "state", "s": "closing", "plan": "endurance", "pulses": 5, "v": False},
    "final_online": FINAL,
    "final_paid_not_online_yet": dict(FINAL, online=False),
    "final_voucher": dict(FINAL, vcode="ABCDE-FGHJK", vexp=1900000000),
    "empty_window": {"t": "state", "s": "empty"},
    "busy": {"t": "state", "s": "busy", "retry": 7},
    "cooldown": {"t": "state", "s": "cooldown", "retry": 30},
    "mismatch": {"t": "state", "s": "mismatch", "plan": "hyper", "left": 600},
    "error": {"t": "state", "s": "error", "e": "NO_ANSWER"},
}

SNAP = """() => {
  const seen = e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
  const controls = [...document.querySelectorAll('button,a.btn,summary,label,input[type=text],input:not([type])')].filter(seen)
      .map(e => (e.id || e.getAttribute('for') || '') + '|' + (e.innerText || e.placeholder || '').trim());
  return {text: document.body.innerText, controls};
}"""


# The page shows "Use by <day> <time>" two hours after the moment it is opened, so what it says depends on when a test runs (AM or PM, the
# weekday). The golden file was recorded at this moment; the browsers of the tests that compare with it are set to it.
FIXED_NOW = "2026-10-30T03:14:00Z"


def at_fixed_time(ctx):
    ctx.clock.set_fixed_time(FIXED_NOW)
    return ctx


def tidy(text):
    text = re.sub(r"\d{1,2}:\d{2}(:\d{2})?", "#:##", text)          # running clocks
    return [ln.strip() for ln in text.splitlines() if ln.strip()]


def answering(reply):
    """A fake /ws that answers whatever the page says (its hello) with one state."""
    return lambda ws: ws.on_message(lambda _m: ws.send(reply))
