// Feature 056: a voice message from the admin page to one rented phone.
//
// The page records in the owner's browser and, when the paper plane is tapped, uploads the clip here. The box does not keep it and does not
// collect it: every piece of the upload is put into the one frame buffer of a voicerelay::Session, encrypted and signed there with the
// phone's box key, and written to the phone's open connection, so the box holds about one frame (1 KB) whatever the length. The rules
// (who may start, the frames, the faults) are in VoiceRelay.h and VoiceFrames.h and are tested on a computer; this file only moves bytes.
//
// Nothing here touches credit, expiry, deduction or the payment queue. It runs inside the web server's request, like the firmware upload.
#include "Product.h"

#if PISO_HAS_PHONES
#include "WebServerVoice.h"

#include <HTTPClient.h>
#include <WebServer.h>
#include <WiFi.h>
#include <esp_random.h>
#include <esp_task_wdt.h>

#include <new>
#include <string>

#include "Config.h"
#include "DeviceManager.h"
#include "Diagnostics.h"
#include "Security.h"
#include "VoiceRelay.h"
#include "WebServerAuth.h"
#include "WebServerModule.h"

namespace {

// The one message the box is passing on, if any. Uploads are handled one at a time by the web server, so this is never shared.
struct VoiceRun {
    voicerelay::Session* session = nullptr;
    WiFiClient client;
    bool active = false;   // the phone's connection and the frame buffer are held
    bool sawStart = false; // the upload really had a file in it
    bool finished = false; // the last frame went out and the phone said 200
    String error;          // the word the page reads; empty while all is well
    String ip;
    unsigned long startedMs = 0;
};
VoiceRun run;

bool isConfiguredPhone(const String& ip) {
    bool known = false;
    if (ip.length() > 0) {
        forEachConfiguredDevice([&](const DeviceConfig& cfg) {
            if (cfg.ip == ip) {
                known = true;
                return false;
            }
            return true;
        });
    }
    return known;
}

// Frees the frame buffer and the phone's connection. Always the last thing done with a message: nothing of it stays on the box.
void releaseRun() {
    delete run.session;
    run.session = nullptr;
    run.client.stop();
    run.active = false;
}

void clearRun() {
    releaseRun();
    run.sawStart = false;
    run.finished = false;
    run.error = "";
    run.ip = "";
}

// A message abandoned half way (a browser that vanished) must not keep the box "busy" for ever.
void expireStaleRun() {
    if (run.active && millis() - run.startedMs > voicerelay::kStaleMs) {
        diagLog("[VOICE] dropped a message that had been going for over %lu s\n", voicerelay::kStaleMs / 1000UL);
        clearRun();
    }
}

void replyError(const String& word) {
    webServer.sendHeader("Cache-Control", "no-store");
    webServer.send(voicerelay::httpStatusForWord(word.c_str()), "application/json",
                   "{\"success\":false,\"error\":\"" + word + "\"}");
}

size_t clipBytesArg() {
    long v = webServer.arg("len").toInt();
    return v > 0 ? (size_t)v : 0;
}

// The words the page reads, in the order the box can know them: a phone this box does not have, an app that cannot play it, then the
// box's own state (a bad length, another message passing, too little memory).
String preflight(const String& ip, size_t clipBytes) {
    if (!isConfiguredPhone(ip)) return "unknown_phone";
    if (!getTrackedVoice(ip)) return "old_app";
    voicerelay::Verdict v = voicerelay::admit(run.active, ESP.getMaxAllocHeap(), clipBytes);
    return v == voicerelay::Verdict::Ok ? String("") : String(voicerelay::verdictName(v));
}

bool phoneAnswers(const String& ip) {
    WiFiClient client;
    HTTPClient http;
    http.setConnectTimeout(1000);
    http.setTimeout(1000);
    http.setReuse(false);
    if (!http.begin(client, "http://" + ip + ":" + String(targetPort) + "/ping")) return false;
    int code = http.GET();
    http.end();
    return code == 200;
}

bool hexTo32(const String& hex, uint8_t out[32]) {
    if (hex.length() != 64) return false;
    for (int i = 0; i < 32; i++) {
        char pair[3] = {hex[i * 2], hex[i * 2 + 1], 0};
        char* end = nullptr;
        long v = strtol(pair, &end, 16);
        if (end != pair + 2) return false;
        out[i] = (uint8_t)v;
    }
    return true;
}

// One frame (or the request head) to the phone. The phone may be slow, never for longer than kFrameWriteMs.
bool writeAll(const uint8_t* data, size_t len) {
    size_t off = 0;
    unsigned long t0 = millis();
    while (off < len) {
        if (!run.client.connected()) return false;
        size_t w = run.client.write(data + off, len - off);
        if (w == 0) {
            if (millis() - t0 > voicerelay::kFrameWriteMs) return false;
            delay(2);
            continue;
        }
        off += w;
        t0 = millis();
        esp_task_wdt_reset();
    }
    return true;
}

struct PhoneSink {
    bool operator()(const uint8_t* frame, size_t len) const { return writeAll(frame, len); }
};
struct RandomIv {
    void operator()(uint8_t* iv) const { esp_fill_random(iv, voiceframes::kIvBytes); }
};

void failRun(const char* word) {
    if (run.error.length() == 0) run.error = word;
    releaseRun(); // the phone sees the connection close and throws away what it had
}

void startRun() {
    clearRun();
    run.sawStart = true;
    String ip = webServer.arg("ip");
    ip.trim();
    size_t clipBytes = clipBytesArg();
    String word = preflight(ip, clipBytes);
    if (word.length() > 0) {
        run.error = word;
        return;
    }

    // The signed request, as for every box message (payload and hmac in the URL); its hmac starts the chain of frames.
    String secret = getSharedSecret();
    String params = "action=voice_message&ts=" + String(getCurrentMasterTimeMs()) + "&nonce=" + generateTxId("vm-") +
                    "&bytes=" + String((unsigned long)clipBytes);
    String payload = aes_encrypt(params, secret);
    String hmac = calculateHMAC(payload, secret);
    uint8_t envelope[32];
    if (secret.length() == 0 || payload.length() == 0 || !hexTo32(hmac, envelope)) {
        run.error = "box_error";
        return;
    }

    run.session = new (std::nothrow) voicerelay::Session();
    if (!run.session) {
        run.error = "low_memory";
        return;
    }
    if (!run.session->begin(std::string(secret.c_str()), envelope, clipBytes)) {
        failRun("box_error");
        return;
    }

    IPAddress addr;
    if (!addr.fromString(ip) || !run.client.connect(addr, targetPort, 1500)) {
        failRun("no_answer");
        return;
    }
    run.client.setNoDelay(true);
    run.active = true;
    run.startedMs = millis();
    run.ip = ip;

    std::string head = voicerelay::requestHead(std::string(ip.c_str()) + ":" + std::to_string(targetPort),
                                               payload.c_str(), hmac.c_str(), voiceframes::wireBytesFor(clipBytes));
    if (!writeAll((const uint8_t*)head.data(), head.size())) failRun("no_answer");
}

void writeRun(const uint8_t* data, size_t len) {
    if (run.error.length() > 0 || !run.active || !run.session)
        return; // already failed: the rest of the upload is read and dropped
    esp_task_wdt_reset();
    if (millis() - run.startedMs > voicerelay::kTotalMs) {
        failRun("timeout");
        return;
    }
    PhoneSink sink;
    RandomIv iv;
    if (!run.session->feed(data, len, sink, iv)) failRun(voicerelay::errorWord(run.session->fault()));
}

void endRun() {
    if (run.error.length() > 0 || !run.active || !run.session) return;
    PhoneSink sink;
    RandomIv iv;
    if (!run.session->finish(sink, iv)) {
        failRun(voicerelay::errorWord(run.session->fault()));
        return;
    }
    // The phone answers when it has the last frame and has checked the whole message.
    String line = "";
    unsigned long t0 = millis();
    while (line.length() == 0 && millis() - t0 < voicerelay::kFrameWriteMs) {
        if (run.client.available()) {
            line = run.client.readStringUntil('\n');
        } else if (!run.client.connected()) {
            break;
        } else {
            delay(5);
            esp_task_wdt_reset();
        }
    }
    int code = voicerelay::statusOf(line.c_str());
    if (code == 200) {
        run.finished = true;
        diagLog("[VOICE] passed a message of %u bytes to %s in %lu ms\n", (unsigned)run.session->received(),
                run.ip.c_str(), millis() - run.startedMs);
        releaseRun();
    } else {
        failRun(code == 0 ? "no_answer" : "phone_refused");
    }
}

} // namespace

void handleApiVoiceReady() {
    if (!checkAdminAuth()) return;
    expireStaleRun();
    String ip = webServer.arg("ip");
    ip.trim();
    String word = preflight(ip, clipBytesArg());
    if (word.length() > 0) {
        replyError(word);
        return;
    }
    if (!phoneAnswers(ip)) {
        replyError("no_answer");
        return;
    }
    webServer.sendHeader("Cache-Control", "no-store");
    webServer.send(200, "application/json", "{\"success\":true}");
}

void handleApiVoiceUpload() {
    if (!checkAdminAuth()) return;
    HTTPUpload& upload = webServer.upload();
    if (upload.status == UPLOAD_FILE_START) {
        expireStaleRun();
        startRun();
    } else if (upload.status == UPLOAD_FILE_WRITE) {
        writeRun(upload.buf, upload.currentSize);
    } else if (upload.status == UPLOAD_FILE_END) {
        endRun();
    } else if (upload.status == UPLOAD_FILE_ABORTED) {
        failRun("cut_off");
    }
}

void handleApiVoiceDone() {
    if (!checkAdminAuth()) {
        clearRun();
        return;
    }
    bool ok = run.sawStart && run.finished && run.error.length() == 0;
    String word =
        ok ? String("") : (run.error.length() > 0 ? run.error : String(run.sawStart ? "cut_off" : "bad_clip"));
    if (!ok) diagLog("[VOICE] not passed on: %s\n", word.c_str());
    clearRun(); // whatever happened, nothing of the message and no connection stays
    if (ok) {
        webServer.sendHeader("Cache-Control", "no-store");
        webServer.send(200, "application/json", "{\"success\":true}");
    } else {
        replyError(word);
    }
}

#endif // PISO_HAS_PHONES
