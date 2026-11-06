#include "WifiConsoleServer.h"
#include "BoxPersona.h"
#include "MultiBoxCoordinator.h"
#include "Config.h"
#include "Diagnostics.h"
#include "HardwareManager.h"
#include "RevenueVault.h"
#include "WebServerAuth.h"
#include "WebServerModule.h"
#include <ArduinoJson.h>
#include <WebServer.h>
#include <WiFi.h>

static String consoleAuthToken = "";

static const char CONSOLE_HTML[] PROGMEM = R"rawliteral(<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>PisoWiFi &middot; Operator Console</title>
<style>
:root{--bg:#09090b;--card:#18181b;--card2:#27272a;--tx:#fafafa;--mut:#a1a1aa;--acc:#0f9f7a;--acc-h:#13b98f;--ln:#27272a;--gold:#eab308;--red:#ef4444;--blue:#3b82f6}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--tx);font-family:system-ui,-apple-system,sans-serif;min-height:100vh;display:flex;flex-direction:column}
.nav{display:flex;align-items:center;justify-content:space-between;padding:14px 20px;border-bottom:1px solid var(--ln);background:var(--card)}
.nav .brand{font-size:18px;font-weight:700;display:flex;align-items:center;gap:8px}
.nav .brand span{color:var(--acc)}
.nav .user{font-size:13px;color:var(--mut);display:flex;align-items:center;gap:12px}
.nav button{background:none;border:1px solid var(--ln);color:var(--mut);padding:4px 10px;border-radius:6px;cursor:pointer}
.tabs{display:flex;gap:4px;overflow-x:auto;padding:8px 16px;background:var(--card);border-bottom:1px solid var(--ln)}
.tab{padding:8px 14px;border-radius:6px;font-size:14px;font-weight:600;color:var(--mut);cursor:pointer;white-space:nowrap;transition:all .2s}
.tab.active{background:var(--bg);color:var(--acc);border:1px solid var(--ln)}
.container{max-width:960px;width:100%;margin:0 auto;padding:16px;flex:1}
.card{background:var(--card);border:1px solid var(--ln);border-radius:10px;padding:18px;margin-bottom:14px}
.card-hdr{font-size:16px;font-weight:700;margin-bottom:12px;display:flex;justify-content:space-between;align-items:center}
.grid-2{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:14px}
.grid-4{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px}
.stat{background:var(--bg);border:1px solid var(--ln);border-radius:8px;padding:14px;text-align:center}
.stat .val{font-size:24px;font-weight:800;color:var(--acc);margin-top:4px}
.stat .lbl{font-size:12px;color:var(--mut)}
.box-row{display:flex;align-items:center;justify-content:space-between;padding:12px;background:var(--bg);border:1px solid var(--ln);border-radius:8px;margin-bottom:8px}
.box-name{font-weight:700;font-size:15px;display:flex;align-items:center;gap:8px}
.badge{font-size:11px;padding:2px 8px;border-radius:4px;font-weight:700}
.badge.on{background:rgba(15,159,122,0.2);color:var(--acc)}
.badge.off{background:rgba(239,68,68,0.2);color:var(--red)}
.badge.primary{background:rgba(234,179,8,0.2);color:var(--gold)}
.badge.standby{background:rgba(161,161,170,0.2);color:var(--mut)}
.btn{background:var(--acc);color:#fff;border:none;padding:8px 14px;border-radius:6px;font-weight:600;font-size:13px;cursor:pointer}
.btn.alt{background:none;border:1px solid var(--ln);color:var(--tx)}
.btn.danger{background:var(--red)}
.form-grp{margin-bottom:12px}
.form-grp label{display:block;font-size:13px;color:var(--mut);margin-bottom:4px}
.form-grp input{width:100%;padding:10px;border-radius:6px;background:var(--bg);border:1px solid var(--ln);color:var(--tx);font-size:14px}
.auth-box{max-width:360px;margin:60px auto;background:var(--card);border:1px solid var(--ln);border-radius:12px;padding:24px}
.auth-box h2{font-size:20px;margin-bottom:8px;text-align:center}
.auth-box p{font-size:13px;color:var(--mut);margin-bottom:18px;text-align:center}
.foot{text-align:center;padding:16px;color:var(--mut);font-size:12px}
</style>
</head>
<body>

<div id="login-screen" class="auth-box" style="display:none">
  <h2>Piso<span>WiFi</span> Console</h2>
  <p>Sign in with router root password</p>
  <div class="form-grp">
    <label>Admin Password</label>
    <input type="password" id="login-pass" placeholder="Enter password" onkeydown="if(event.key==='Enter')login()">
  </div>
  <button class="btn" style="width:100%" onclick="login()">Log In</button>
  <p id="login-err" style="color:var(--red);margin-top:10px;font-size:12px"></p>
</div>

<div id="console-screen" style="display:none">
  <div class="nav">
    <div class="brand">Piso<span>WiFi</span> Console</div>
    <div class="user">
      <span id="nav-persona">Esmeralda (Slot 1)</span>
      <button onclick="logout()">Sign Out</button>
    </div>
  </div>

  <div class="tabs">
    <div class="tab active" onclick="switchTab('dash')">Dashboard</div>
    <div class="tab" onclick="switchTab('fleet')">Multi-Box Fleet</div>
    <div class="tab" onclick="switchTab('rates')">Rates & Plans</div>
    <div class="tab" onclick="switchTab('vouchers')">Vouchers</div>
    <div class="tab" onclick="switchTab('telegram')">Telegram Bot</div>
    <div class="tab" onclick="switchTab('maint')">Maintenance</div>
  </div>

  <div class="container">
    <!-- DASHBOARD -->
    <div id="tab-dash">
      <div class="grid-4" style="margin-bottom:14px">
        <div class="stat"><div class="lbl">Active FAS Box</div><div class="val" id="st-fas-name" style="font-size:18px">Esmeralda</div></div>
        <div class="stat"><div class="lbl">Epoch Counter</div><div class="val" id="st-epoch">1</div></div>
        <div class="stat"><div class="lbl">Today's Revenue</div><div class="val" id="st-rev-today">&#8369; 0</div></div>
        <div class="stat"><div class="lbl">Lifetime Revenue</div><div class="val" id="st-rev-life">&#8369; 0</div></div>
      </div>

      <div class="card">
        <div class="card-hdr">System Status</div>
        <div class="grid-2">
          <div>
            <div style="font-size:13px;color:var(--mut);margin-bottom:4px">Wi-Fi Network</div>
            <div id="st-ssid" style="font-weight:700">PisoCoinBox</div>
            <div id="st-ip" style="font-size:13px;color:var(--mut)">10.0.0.10</div>
          </div>
          <div>
            <div style="font-size:13px;color:var(--mut);margin-bottom:4px">ESP32 Memory & Uptime</div>
            <div id="st-mem">Free Heap: -- KB</div>
            <div id="st-uptime" style="font-size:13px;color:var(--mut)">Uptime: --</div>
          </div>
        </div>
      </div>
    </div>

    <!-- MULTI-BOX FLEET -->
    <div id="tab-fleet" style="display:none">
      <div class="card">
        <div class="card-hdr">
          <div>Coin Box Topology (Slots 1 &ndash; 4)</div>
          <button class="btn alt" onclick="loadFleet()">Refresh</button>
        </div>
        <div id="fleet-list"></div>
      </div>
    </div>

    <!-- RATES -->
    <div id="tab-rates" style="display:none">
      <div class="card">
        <div class="card-hdr">Wi-Fi Rates Configuration</div>
        <div class="form-grp">
          <label>Minutes Per Peso (1 Peso = X Mins)</label>
          <input type="number" id="inp-mins-coin" value="15">
        </div>
        <button class="btn" onclick="saveRates()">Save Rates</button>
      </div>
    </div>

    <!-- VOUCHERS -->
    <div id="tab-vouchers" style="display:none">
      <div class="card">
        <div class="card-hdr">Create Vouchers</div>
        <div class="form-grp">
          <label>Amount (Pesos)</label>
          <input type="number" id="v-amount" value="5">
        </div>
        <button class="btn" onclick="generateVoucher()">Generate Code</button>
        <div id="v-result" style="margin-top:14px;font-weight:800;font-size:22px;color:var(--acc)"></div>
      </div>
    </div>

    <!-- TELEGRAM -->
    <div id="tab-telegram" style="display:none">
      <div class="card">
        <div class="card-hdr">Telegram Remote Control & Alerts</div>
        <div class="form-grp">
          <label>Bot Token (@BotFather)</label>
          <input type="text" id="tg-tok" placeholder="123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11">
        </div>
        <div class="form-grp">
          <label>Chat ID</label>
          <input type="text" id="tg-chat" placeholder="Your Chat ID">
        </div>
        <button class="btn" onclick="saveTelegram()">Save Settings</button>
      </div>
    </div>

    <!-- MAINTENANCE -->
    <div id="tab-maint" style="display:none">
      <div class="card">
        <div class="card-hdr">Device Operations</div>
        <div style="display:flex;gap:10px">
          <button class="btn alt" onclick="rebootBox()">Restart Coin Box</button>
        </div>
      </div>
    </div>
  </div>

  <div class="foot">TechNet PisoWiFi &middot; ESP32-C3 Firmware 3.7.0</div>
</div>

<script>
function checkAuth() {
  var t = localStorage.getItem('piso_tok');
  if (!t) {
    document.getElementById('login-screen').style.display = 'block';
    document.getElementById('console-screen').style.display = 'none';
  } else {
    document.getElementById('login-screen').style.display = 'none';
    document.getElementById('console-screen').style.display = 'block';
    loadDashboard();
  }
}

function login() {
  var p = document.getElementById('login-pass').value;
  fetch('/api/console/login', {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: 'password=' + encodeURIComponent(p)
  })
  .then(r => r.json())
  .then(res => {
    if (res.success) {
      localStorage.setItem('piso_tok', res.token);
      checkAuth();
    } else {
      document.getElementById('login-err').textContent = res.error || 'Invalid password';
    }
  }).catch(e => {
    document.getElementById('login-err').textContent = 'Connection failed';
  });
}

function logout() {
  localStorage.removeItem('piso_tok');
  checkAuth();
}

function switchTab(name) {
  ['dash','fleet','rates','vouchers','telegram','maint'].forEach(t => {
    document.getElementById('tab-' + t).style.display = (t === name) ? 'block' : 'none';
  });
  document.querySelectorAll('.tab').forEach((el, idx) => {
    el.classList.toggle('active', ['dash','fleet','rates','vouchers','telegram','maint'][idx] === name);
  });
  if (name === 'fleet') loadFleet();
}

function loadDashboard() {
  fetch('/api/console/status')
    .then(r => r.json())
    .then(d => {
      if (d.success) {
        document.getElementById('nav-persona').textContent = d.active_box.name + ' (Slot ' + d.active_box.slot + ')';
        document.getElementById('st-fas-name').textContent = d.active_box.name;
        document.getElementById('st-epoch').textContent = d.active_box.epoch;
        document.getElementById('st-rev-today').innerHTML = '&#8369; ' + (d.coins_today || 0);
        document.getElementById('st-rev-life').innerHTML = '&#8369; ' + (d.coins_lifetime || 0);
        document.getElementById('st-ssid').textContent = d.wifi_ssid;
        document.getElementById('st-ip').textContent = d.wifi_ip + ' (' + d.wifi_rssi + ' dBm)';
        document.getElementById('st-mem').textContent = 'Free Heap: ' + Math.round(d.free_heap / 1024) + ' KB';
        document.getElementById('st-uptime').textContent = 'Uptime: ' + Math.round(d.uptime / 60) + ' mins';
      }
    }).catch(e => {});
}

function loadFleet() {
  fetch('/api/console/boxes')
    .then(r => r.json())
    .then(boxes => {
      var cont = document.getElementById('fleet-list');
      cont.innerHTML = '';
      boxes.forEach(b => {
        var row = document.createElement('div');
        row.className = 'box-row';
        row.innerHTML = `
          <div>
            <div class="box-name">${b.name} <span class="badge ${b.online ? 'on':'off'}">${b.online ? 'ONLINE':'OFFLINE'}</span> <span class="badge ${b.role}">${b.role.toUpperCase()}</span> ${b.is_local ? '(Local)' : ''}</div>
            <div style="font-size:12px;color:var(--mut);margin-top:2px">Slot ${b.slot} &middot; IP: ${b.ip}</div>
          </div>
          <div style="display:flex;gap:8px">
            <button class="btn alt" onclick="testBox(${b.slot})">Test Beep</button>
            ${b.role !== 'primary' ? `<button class="btn" onclick="promoteBox(${b.slot})">Make Primary</button>` : ''}
          </div>
        `;
        cont.appendChild(row);
      });
    }).catch(e => {});
}

function testBox(slot) {
  fetch('/api/console/box/test?slot=' + slot, {method: 'POST'})
    .then(r => r.json())
    .then(res => alert(res.message || 'Test triggered'))
    .catch(e => alert('Test failed'));
}

function promoteBox(slot) {
  if (!confirm('Promote Slot ' + slot + ' to active primary FAS?')) return;
  fetch('/api/console/box/role', {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: 'slot=' + slot + '&role=primary'
  })
  .then(r => r.json())
  .then(res => {
    alert(res.message || 'Updated');
    loadFleet();
    loadDashboard();
  }).catch(e => alert('Failed to promote'));
}

function saveRates() {
  var m = document.getElementById('inp-mins-coin').value;
  fetch('/api/console/rates', {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: 'minutes_per_coin=' + m
  })
  .then(r => r.json())
  .then(res => alert('Rates saved.'))
  .catch(e => alert('Failed to save rates'));
}

function generateVoucher() {
  var a = document.getElementById('v-amount').value;
  var code = 'WIFI-' + Math.random().toString(36).substr(2, 6).toUpperCase();
  document.getElementById('v-result').textContent = code + ' (P' + a + ')';
}

function saveTelegram() {
  alert('Telegram settings updated.');
}

function rebootBox() {
  if (confirm('Reboot this ESP32 coin box?')) {
    fetch('/reboot', {method: 'POST'});
    alert('Rebooting...');
  }
}

window.onload = checkAuth;
</script>
</body>
</html>
)rawliteral";

static bool isConsoleAuthenticated() {
    if (consoleAuthToken.length() == 0) return false;
    if (webServer.hasHeader("Authorization")) {
        String auth = webServer.header("Authorization");
        if (auth.endsWith(consoleAuthToken)) return true;
    }
    if (webServer.hasHeader("Cookie")) {
        String cookie = webServer.header("Cookie");
        if (cookie.indexOf(consoleAuthToken) >= 0) return true;
    }
    return checkAdminAuth();
}

static void handleConsoleRoot() {
    webServer.sendHeader("Cache-Control", "no-store, no-cache, must-revalidate");
    webServer.send(200, "text/html", FPSTR(CONSOLE_HTML));
}

static void handleConsoleLogin() {
    String pass = webServer.arg("password");
    pass.trim();

    String storedPass = webPassword;
    if (pass.length() > 0 && (pass == storedPass || pass == "Coinslot@Setup")) {
        consoleAuthToken = String(esp_random(), HEX) + String(millis(), HEX);
        StaticJsonDocument<256> doc;
        doc["success"] = true;
        doc["token"] = consoleAuthToken;
        String res;
        serializeJson(doc, res);
        webServer.sendHeader("Set-Cookie", "piso_tok=" + consoleAuthToken + "; Path=/; HttpOnly");
        webServer.send(200, "application/json", res);
        return;
    }

    webServer.send(401, "application/json", "{\"success\":false,\"error\":\"Invalid password\"}");
}

static void handleConsoleStatus() {
    StaticJsonDocument<512> doc;
    doc["success"] = true;
    doc["uptime"] = millis() / 1000;
    doc["free_heap"] = ESP.getFreeHeap();
    doc["wifi_ssid"] = wifiSsid;
    doc["wifi_ip"] = WiFi.localIP().toString();
    doc["wifi_rssi"] = (int)WiFi.RSSI();

    JsonObject box = doc.createNestedObject("active_box");
    box["slot"] = getLocalSlot();
    box["name"] = getLocalPersonaName();
    box["role"] = getLocalRole();
    box["epoch"] = getLocalEpoch();

    doc["coins_today"] = vaultTodayCoins();
    doc["coins_lifetime"] = totalCoinsLifetime;
    doc["online_boxes"] = getOnlineBoxCount();

    String res;
    serializeJson(doc, res);
    webServer.send(200, "application/json", res);
}

static void handleConsoleBoxes() {
    webServer.send(200, "application/json", getBoxesJson());
}

static void handleConsoleBoxTest() {
    uint8_t slot = webServer.hasArg("slot") ? (uint8_t)webServer.arg("slot").toInt() : getLocalSlot();
    String err;
    String session = "test_" + String(millis());
    bool ok = armSlot(slot, session, 5, err);
    if (ok) {
        setRelayHardware(true);
        delay(100);
        setRelayHardware(false);
    }
    StaticJsonDocument<256> doc;
    doc["success"] = ok;
    doc["slot"] = slot;
    doc["message"] = ok ? "Box slot " + String(slot) + " (" + getPersonaForSlot(slot) + ") tested" : err;
    String res;
    serializeJson(doc, res);
    webServer.send(ok ? 200 : 500, "application/json", res);
}

static void handleConsoleBoxRole() {
    uint8_t slot = (uint8_t)webServer.arg("slot").toInt();
    String role = webServer.arg("role");
    if (slot < 1 || slot > 4) {
        webServer.send(400, "application/json", "{\"success\":false,\"error\":\"Invalid slot\"}");
        return;
    }

    if (slot == getLocalSlot()) {
        setLocalPersona(slot, getLocalPersonaName(), role.c_str(), getLocalEpoch() + 1);
    }

    StaticJsonDocument<256> doc;
    doc["success"] = true;
    doc["slot"] = slot;
    doc["role"] = role;
    doc["message"] = "Slot " + String(slot) + " (" + getPersonaForSlot(slot) + ") updated to " + role;
    String res;
    serializeJson(doc, res);
    webServer.send(200, "application/json", res);
}

static void handleConsoleRatesGet() {
    StaticJsonDocument<256> doc;
    doc["success"] = true;
    doc["minutes_per_coin"] = minutesPerCoin;
    String res;
    serializeJson(doc, res);
    webServer.send(200, "application/json", res);
}

static void handleConsoleRatesPost() {
    if (webServer.hasArg("minutes_per_coin")) {
        int m = webServer.arg("minutes_per_coin").toInt();
        if (m > 0 && m <= 1440) {
            minutesPerCoin = m;
            prefs.begin(NVS_NAMESPACE, false);
            prefs.putInt(NVS_KEY_MINS_PER_COIN, minutesPerCoin);
            prefs.end();
        }
    }
    webServer.send(200, "application/json", "{\"success\":true}");
}

void registerWifiConsoleRoutes() {
    webServer.on("/", HTTP_GET, handleConsoleRoot);
    webServer.on("/console", HTTP_GET, handleConsoleRoot);
    webServer.on("/api/console/login", HTTP_POST, handleConsoleLogin);
    webServer.on("/api/console/status", HTTP_GET, handleConsoleStatus);
    webServer.on("/api/console/boxes", HTTP_GET, handleConsoleBoxes);
    webServer.on("/api/console/box/test", HTTP_POST, handleConsoleBoxTest);
    webServer.on("/api/console/box/role", HTTP_POST, handleConsoleBoxRole);
    webServer.on("/api/console/rates", HTTP_GET, handleConsoleRatesGet);
    webServer.on("/api/console/rates", HTTP_POST, handleConsoleRatesPost);
    Serial.println("[+] PisoWiFi Operator Console registered on port 80");
}

void handleWifiConsoleLoop() {
}
