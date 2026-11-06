#include "WifiFasServer.h"
#include "BoxPersona.h"
#include "MultiBoxCoordinator.h"
#include "Config.h"
#include <ArduinoJson.h>
#include <WebServer.h>
#include <WiFi.h>

static WebServer fasServer(2080);
static bool fasInitialized = false;

static const char FAS_HTML[] PROGMEM = R"rawliteral(<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>PisoWiFi &middot; Captive Portal</title>
<style>
:root{--bg:#09090b;--card:#18181b;--tx:#fafafa;--mut:#a1a1aa;--acc:#0f9f7a;--acc-hover:#13b98f;--ln:#27272a;--gold:#eab308;--red:#ef4444}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--tx);font-family:system-ui,-apple-system,sans-serif;min-height:100vh;display:flex;flex-direction:column;align-items:center;padding:16px}
.w{width:100%;max-width:440px;margin:auto}
.hdr{display:flex;align-items:center;justify-content:space-between;padding:12px 0 16px;border-bottom:1px solid var(--ln);margin-bottom:16px}
.logo{font-size:20px;font-weight:700;display:flex;align-items:center;gap:8px}
.logo span{color:var(--acc)}
.lang-btn{background:none;border:1px solid var(--ln);color:var(--mut);padding:4px 10px;border-radius:6px;font-size:12px;font-weight:600;cursor:pointer}
.card{background:var(--card);border:1px solid var(--ln);border-radius:12px;padding:20px;margin-bottom:14px;box-shadow:0 4px 12px rgba(0,0,0,0.3)}
.title{font-size:18px;font-weight:700;margin-bottom:4px}
.sub{font-size:14px;color:var(--mut);margin-bottom:16px}
.boxes{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:8px;margin-bottom:16px}
.box-btn{background:var(--bg);border:1px solid var(--ln);color:var(--tx);padding:10px;border-radius:8px;cursor:pointer;text-align:center;transition:all .2s}
.box-btn.active{border-color:var(--acc);background:rgba(15,159,122,0.1);color:var(--acc);font-weight:700}
.box-btn .status{font-size:11px;color:var(--mut)}
.box-btn.active .status{color:var(--acc)}
.btn{display:block;width:100%;padding:14px;background:var(--acc);color:#fff;border:none;border-radius:8px;font-size:16px;font-weight:700;cursor:pointer;text-align:center;text-decoration:none;transition:background .2s}
.btn:hover{background:var(--acc-hover)}
.btn.alt{background:none;border:1px solid var(--ln);color:var(--tx);margin-top:8px}
.btn:disabled{opacity:0.5;cursor:not-allowed}
.live-area{display:none;text-align:center;padding:10px 0}
.counter{font-size:48px;font-weight:800;color:var(--acc);margin:12px 0}
.time-bar{width:100%;height:8px;background:var(--ln);border-radius:4px;overflow:hidden;margin:12px 0}
.time-bar-fill{width:100%;height:100%;background:var(--acc);transition:width 1s linear}
.persona-status{font-size:15px;font-weight:600;color:var(--gold);margin-bottom:8px}
.success-check{font-size:42px;color:var(--acc);margin-bottom:8px}
.foot{text-align:center;font-size:12px;color:var(--mut);margin-top:auto;padding-top:20px}
</style>
</head>
<body>
<div class="w">
  <div class="hdr">
    <div class="logo">Piso<span>WiFi</span></div>
    <button class="lang-btn" id="lang-btn" onclick="toggleLang()">Filipino</button>
  </div>

  <div class="card" id="idle-card">
    <div class="title" id="t-buy">Insert Coin for Wi-Fi</div>
    <div class="sub" id="t-sub">Select your nearest coin box and tap Insert Coin.</div>
    
    <div class="boxes" id="box-list"></div>

    <button class="btn" id="start-btn" onclick="startCoinSession()">Insert Coin</button>
  </div>

  <div class="card live-area" id="live-card">
    <div class="persona-status" id="persona-status">Calling Esmeralda...</div>
    <div class="title" id="live-instruction">Insert coin into the slot</div>
    <div class="counter" id="pulse-display">&#8369; 0</div>
    <div class="sub" id="live-time-calc">0 minutes</div>
    <div class="time-bar"><div class="time-bar-fill" id="bar-fill"></div></div>
    <div class="sub" id="time-left">60 seconds left</div>

    <button class="btn" id="done-btn" onclick="doneCoinSession()">Done</button>
    <button class="btn alt" id="cancel-btn" onclick="cancelCoinSession()">Cancel</button>
  </div>

  <div class="card live-area" id="done-card">
    <div class="success-check">&#10003;</div>
    <div class="title" id="done-msg">Payment Accepted</div>
    <div class="sub" id="done-detail">You are now connected to the internet.</div>
    <a class="btn" id="redir-btn" href="#">Continue Browsing</a>
  </div>

  <div class="foot">TechNet PisoWiFi &middot; High Speed Hotspot</div>
</div>

<script>
var lang = 'en';
var activeSlot = 1;
var activePersona = 'Esmeralda';
var session = '';
var pollTimer = null;
var totalDuration = 60;
var remainingSec = 60;
var currentPulses = 0;
var audioCtx = null;

var params = new URLSearchParams(window.location.search);
var tok = params.get('tok') || '';
var redir = params.get('redir') || 'http://google.com';
var clientMac = params.get('client_mac') || params.get('mac') || '';

var I18N = {
  en: {
    buy: "Insert Coin for Wi-Fi",
    sub: "Select your nearest coin box and tap Insert Coin.",
    insert: "Insert Coin",
    calling: "Calling {name}...",
    ready: "{name} is ready! Insert coin now.",
    counted: "{name} counted {p} peso(s).",
    accepted: "{name} accepted payment!",
    connected: "You are now connected to the internet.",
    continue: "Continue Browsing",
    slotStatus: "Slot {slot}"
  },
  fil: {
    buy: "Magpasok ng Barya para sa Wi-Fi",
    sub: "Piliin ang pinakamalapit na coin box at pindutin ang Magpasok ng Barya.",
    insert: "Magpasok ng Barya",
    calling: "Tinatawagan si {name}...",
    ready: "Handa na si {name}! Magpasok na ng barya.",
    counted: "Binilang ni {name}: {p} piso.",
    accepted: "Natanggap ni {name} ang bayad!",
    connected: "Online ka na at nakakonekta sa internet.",
    continue: "Magpatuloy sa Pag-browse",
    slotStatus: "Slot {slot}"
  }
};

function toggleLang() {
  lang = (lang === 'en') ? 'fil' : 'en';
  document.getElementById('lang-btn').textContent = (lang === 'en') ? 'Filipino' : 'English';
  renderTexts();
}

function renderTexts() {
  var t = I18N[lang];
  document.getElementById('t-buy').textContent = t.buy;
  document.getElementById('t-sub').textContent = t.sub;
  document.getElementById('start-btn').textContent = t.insert;
}

function initAudio() {
  try {
    if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state === 'suspended') audioCtx.resume();
  } catch(e){}
}

function beep() {
  try {
    if (!audioCtx) return;
    var osc = audioCtx.createOscillator();
    var gain = audioCtx.createGain();
    osc.connect(gain);
    gain.connect(audioCtx.destination);
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.2, audioCtx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.01, audioCtx.currentTime + 0.15);
    osc.start();
    osc.stop(audioCtx.currentTime + 0.15);
  } catch(e){}
}

function loadBoxes() {
  fetch('/api/boxes')
    .then(r => r.json())
    .then(data => {
      var cont = document.getElementById('box-list');
      cont.innerHTML = '';
      data.forEach(b => {
        if (!b.online && !b.is_local) return;
        var btn = document.createElement('div');
        btn.className = 'box-btn' + (b.slot === activeSlot ? ' active' : '');
        btn.id = 'slot-btn-' + b.slot;
        btn.onclick = () => selectSlot(b.slot, b.name);
        btn.innerHTML = '<div>' + b.name + '</div><div class="status">Slot ' + b.slot + (b.is_local ? ' &middot; Here' : '') + '</div>';
        cont.appendChild(btn);
        if (b.is_local) {
          activeSlot = b.slot;
          activePersona = b.name;
        }
      });
    }).catch(e => console.log(e));
}

function selectSlot(slot, name) {
  activeSlot = slot;
  activePersona = name;
  document.querySelectorAll('.box-btn').forEach(b => b.classList.remove('active'));
  var el = document.getElementById('slot-btn-' + slot);
  if (el) el.classList.add('active');
}

function startCoinSession() {
  initAudio();
  session = 's_' + Math.random().toString(36).substr(2, 9);
  currentPulses = 0;
  remainingSec = 60;

  document.getElementById('idle-card').style.display = 'none';
  document.getElementById('live-card').style.display = 'block';

  var t = I18N[lang];
  document.getElementById('persona-status').textContent = t.calling.replace('{name}', activePersona);

  fetch('/api/coin/start', {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: 'slot=' + activeSlot + '&session=' + session + '&mac=' + clientMac
  })
  .then(r => r.json())
  .then(res => {
    if (res.success) {
      document.getElementById('persona-status').textContent = t.ready.replace('{name}', activePersona);
      startPolling();
    } else {
      alert(res.error || 'Failed to open coin slot');
      cancelCoinSession();
    }
  }).catch(err => {
    alert('Communication error with coin box');
    cancelCoinSession();
  });
}

function startPolling() {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(pollStatus, 1000);
}

function pollStatus() {
  fetch('/api/coin/status?slot=' + activeSlot + '&session=' + session)
    .then(r => r.json())
    .then(res => {
      if (res.success) {
        if (res.pulses > currentPulses) {
          currentPulses = res.pulses;
          beep();
          var t = I18N[lang];
          document.getElementById('persona-status').textContent = t.counted.replace('{name}', activePersona).replace('{p}', currentPulses);
        }
        document.getElementById('pulse-display').innerHTML = '&#8369; ' + res.pulses;
        document.getElementById('live-time-calc').textContent = (res.pulses * 15) + ' mins';
        remainingSec = res.time_left;
        document.getElementById('time-left').textContent = remainingSec + 's left';
        var pct = Math.max(0, (remainingSec / totalDuration) * 100);
        document.getElementById('bar-fill').style.width = pct + '%';

        if (remainingSec <= 0) {
          doneCoinSession();
        }
      }
    }).catch(e => {});
}

function doneCoinSession() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  document.getElementById('done-btn').disabled = true;

  fetch('/api/coin/done', {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: 'slot=' + activeSlot + '&session=' + session + '&mac=' + clientMac + '&tok=' + tok + '&redir=' + encodeURIComponent(redir)
  })
  .then(r => r.json())
  .then(res => {
    document.getElementById('live-card').style.display = 'none';
    document.getElementById('done-card').style.display = 'block';

    var t = I18N[lang];
    if (res.online) {
      document.getElementById('done-msg').textContent = t.accepted.replace('{name}', activePersona);
      document.getElementById('done-detail').textContent = t.connected + ' (' + res.minutes + ' mins added)';
      document.getElementById('redir-btn').href = redir;
      setTimeout(() => { window.location.href = redir; }, 3000);
    } else {
      document.getElementById('done-msg').textContent = 'Session Finished';
      document.getElementById('done-detail').textContent = 'No coins detected. You were not charged.';
      document.getElementById('redir-btn').textContent = 'Back to Start';
      document.getElementById('redir-btn').href = '/';
    }
  }).catch(e => {
    alert('Payment settlement completed.');
    window.location.href = redir;
  });
}

function cancelCoinSession() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  fetch('/api/coin/cancel', {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: 'slot=' + activeSlot + '&session=' + session
  });
  document.getElementById('live-card').style.display = 'none';
  document.getElementById('idle-card').style.display = 'block';
}

window.onload = function() {
  loadBoxes();
  renderTexts();
};
</script>
</body>
</html>
)rawliteral";

static void handleFasRoot() {
    fasServer.sendHeader("Cache-Control", "no-store, no-cache, must-revalidate");
    fasServer.send(200, "text/html", FPSTR(FAS_HTML));
}

static void handleFasPing() {
    fasServer.send(200, "text/plain", "ok");
}

static void handleFasBoxes() {
    fasServer.sendHeader("Cache-Control", "no-store");
    fasServer.send(200, "application/json", getBoxesJson());
}

static void handleFasCoinStart() {
    uint8_t slot = fasServer.hasArg("slot") ? (uint8_t)fasServer.arg("slot").toInt() : getLocalSlot();
    String session = fasServer.arg("session");
    session.trim();
    if (session.length() == 0) {
        session = "s_" + String(millis());
    }

    String err;
    if (armSlot(slot, session, 60, err)) {
        StaticJsonDocument<256> doc;
        doc["success"] = true;
        doc["slot"] = slot;
        doc["session"] = session;
        doc["box_name"] = getPersonaForSlot(slot);
        String res;
        serializeJson(doc, res);
        fasServer.send(200, "application/json", res);
    } else {
        StaticJsonDocument<256> doc;
        doc["success"] = false;
        doc["error"] = err;
        String res;
        serializeJson(doc, res);
        fasServer.send(err == "SLOT_BUSY" ? 409 : 500, "application/json", res);
    }
}

static void handleFasCoinStatus() {
    uint8_t slot = fasServer.hasArg("slot") ? (uint8_t)fasServer.arg("slot").toInt() : getLocalSlot();
    String session = fasServer.arg("session");
    int pulses = 0, remaining = 0;
    String state, err;

    if (getSlotStatus(slot, session, pulses, remaining, state, err)) {
        StaticJsonDocument<256> doc;
        doc["success"] = true;
        doc["slot"] = slot;
        doc["session"] = session;
        doc["pulses"] = pulses;
        doc["time_left"] = remaining;
        doc["state"] = state;
        doc["box_name"] = getPersonaForSlot(slot);
        String res;
        serializeJson(doc, res);
        fasServer.send(200, "application/json", res);
    } else {
        StaticJsonDocument<256> doc;
        doc["success"] = false;
        doc["error"] = err;
        String res;
        serializeJson(doc, res);
        fasServer.send(500, "application/json", res);
    }
}

static void handleFasCoinDone() {
    uint8_t slot = fasServer.hasArg("slot") ? (uint8_t)fasServer.arg("slot").toInt() : getLocalSlot();
    String session = fasServer.arg("session");
    String mac = fasServer.arg("mac");
    String redir = fasServer.arg("redir");
    mac.trim();
    if (mac.length() == 0) {
        mac = fasServer.client().remoteIP().toString();
    }

    int pulses = 0, remaining = 0;
    String state, err;
    getSlotStatus(slot, session, pulses, remaining, state, err);

    StaticJsonDocument<512> doc;
    if (pulses > 0) {
        // Compute minutes: default 15 minutes per peso, or tier calculation
        int minutes = pulses * minutesPerCoin;
        if (minutes < 15) minutes = pulses * 15;

        // Grant access on OpenWrt router via CGI
        String grantErr;
        bool granted = grantRouterAccess(mac, minutes, 0, 0, grantErr);
        
        // Acknowledge coins in box vault
        int acked = ackSlot(slot, session);

        doc["success"] = true;
        doc["online"] = granted;
        doc["slot"] = slot;
        doc["box_name"] = getPersonaForSlot(slot);
        doc["pulses"] = pulses;
        doc["minutes"] = minutes;
        doc["acked"] = acked;
        doc["redir"] = redir.length() > 0 ? redir : "http://google.com";
    } else {
        releaseSlot(slot, session);
        doc["success"] = true;
        doc["online"] = false;
        doc["pulses"] = 0;
        doc["minutes"] = 0;
    }

    String res;
    serializeJson(doc, res);
    fasServer.send(200, "application/json", res);
}

static void handleFasCoinCancel() {
    uint8_t slot = fasServer.hasArg("slot") ? (uint8_t)fasServer.arg("slot").toInt() : getLocalSlot();
    String session = fasServer.arg("session");
    releaseSlot(slot, session);
    fasServer.send(200, "application/json", "{\"success\":true}");
}

void setupWifiFasServer() {
    if (fasInitialized) return;

    fasServer.on("/", HTTP_GET, handleFasRoot);
    fasServer.on("/index.html", HTTP_GET, handleFasRoot);
    fasServer.on("/ping", HTTP_GET, handleFasPing);
    fasServer.on("/api/boxes", HTTP_GET, handleFasBoxes);
    fasServer.on("/api/coin/start", HTTP_POST, handleFasCoinStart);
    fasServer.on("/api/coin/status", HTTP_GET, handleFasCoinStatus);
    fasServer.on("/api/coin/done", HTTP_POST, handleFasCoinDone);
    fasServer.on("/api/coin/cancel", HTTP_POST, handleFasCoinCancel);

    fasServer.begin();
    fasInitialized = true;
    Serial.printf("[+] WifiFasServer running on port 2080 (Slot %u, %s)\n",
                  (unsigned)getLocalSlot(), getLocalPersonaName());
}

void handleWifiFasServer() {
    if (fasInitialized) {
        fasServer.handleClient();
    }
}
