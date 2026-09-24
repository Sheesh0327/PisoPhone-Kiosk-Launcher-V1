#include "WebSocketProtocol.h"

String extractUrlParam(String url, String param) {
    int start = url.indexOf(param);
    if (start == -1) return "";
    start += param.length();
    int end = url.indexOf('&', start);
    if (end == -1) {
        end = url.indexOf(' ', start);
    }
    if (end == -1) {
        end = url.length();
    }
    return url.substring(start, end);
}

void sendWsText(WiFiClient& client, String text) {
    if (!client.connected()) return;
    size_t len = text.length();
    uint8_t header[10];
    size_t headerLen = 0;

    header[0] = 0x81; // FIN + text frame
    if (len <= 125) {
        header[1] = (uint8_t)len;
        headerLen = 2;
    } else if (len <= 65535) {
        header[1] = 126;
        header[2] = (uint8_t)((len >> 8) & 0xFF);
        header[3] = (uint8_t)(len & 0xFF);
        headerLen = 4;
    } else {
        header[1] = 127;
        for (int i = 0; i < 8; i++) {
            header[2 + i] = (uint8_t)((len >> ((7 - i) * 8)) & 0xFF);
        }
        headerLen = 10;
    }

    client.write(header, headerLen);
    client.write((const uint8_t*)text.c_str(), len);
    client.flush();
}

void sendWsPong(WiFiClient& client, const uint8_t* payload, size_t len) {
    if (!client.connected()) return;
    uint8_t header[2];
    header[0] = 0x8A; // FIN + Pong frame
    header[1] = (uint8_t)(len & 0x7F);
    client.write(header, 2);
    if (len > 0 && payload != NULL) {
        client.write(payload, len);
    }
    client.flush();
}

static bool readExactBytes(WiFiClient& client, uint8_t* buf, size_t count, unsigned long deadlineMs) {
    size_t readCount = 0;
    unsigned long start = millis();
    while (readCount < count) {
        if (!client.connected()) return false;
        int avail = client.available();
        if (avail > 0) {
            int toRead = (int)min((size_t)avail, count - readCount);
            int bytesRead = client.read(buf + readCount, toRead);
            if (bytesRead <= 0) return false;
            readCount += bytesRead;
        } else {
            if ((long)(millis() - (start + deadlineMs)) >= 0) {
                return false;
            }
            delay(1);
        }
    }
    return true;
}

String readWsText(WiFiClient& client) {
    if (!client.available()) return "";

    const unsigned long FRAME_DEADLINE_MS = 300;
    const size_t MAX_WS_MESSAGE_SIZE = 2048;
    String assembledText = "";
    bool inFragmentedMessage = false;

    unsigned long overallStart = millis();

    auto getRemainingDeadlineMs = [overallStart, FRAME_DEADLINE_MS]() -> unsigned long {
        unsigned long elapsed = millis() - overallStart;
        if (elapsed >= FRAME_DEADLINE_MS) return 0;
        return FRAME_DEADLINE_MS - elapsed;
    };

    while (client.connected()) {
        unsigned long remainingMs = getRemainingDeadlineMs();
        if (remainingMs == 0) {
            Serial.println("[WS] Strict frame deadline exceeded. Dropping connection.");
            client.stop();
            return "";
        }

        if (!client.available()) {
            if (!inFragmentedMessage) {
                return "";
            }
            delay(2);
            continue;
        }

        uint8_t header[2];
        if (!readExactBytes(client, header, 2, remainingMs)) {
            Serial.println("[WS] Failed to read frame header within deadline. Dropping.");
            client.stop();
            return "";
        }

        bool fin = (header[0] & 0x80) != 0;
        uint8_t rsv = header[0] & 0x70;
        uint8_t opcode = header[0] & 0x0F;
        bool isMasked = (header[1] & 0x80) != 0;
        uint64_t payloadLen = header[1] & 0x7F;

        // RFC 6455: RSV bits must be 0 unless negotiated
        if (rsv != 0) {
            Serial.println("[WS] Protocol error: non-zero RSV bits. Dropping.");
            client.stop();
            return "";
        }

        // Client-to-server frames MUST be masked
        if (!isMasked) {
            Serial.println("[WS] Protocol error: unmasked client frame. Dropping.");
            client.stop();
            return "";
        }

        // Extended payload length parsing
        if (payloadLen == 126) {
            uint8_t extLen[2];
            remainingMs = getRemainingDeadlineMs();
            if (remainingMs == 0 || !readExactBytes(client, extLen, 2, remainingMs)) {
                Serial.println("[WS] Failed to read 16-bit extended length within deadline. Dropping.");
                client.stop();
                return "";
            }
            payloadLen = ((uint64_t)extLen[0] << 8) | extLen[1];
        } else if (payloadLen == 127) {
            uint8_t extLen[8];
            remainingMs = getRemainingDeadlineMs();
            if (remainingMs == 0 || !readExactBytes(client, extLen, 8, remainingMs)) {
                Serial.println("[WS] Failed to read 64-bit extended length within deadline. Dropping.");
                client.stop();
                return "";
            }
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | extLen[i];
            }
        }

        // Control frame checks (RFC 6455: payload <= 125, FIN must be 1)
        bool isControl = (opcode >= 0x08);
        if (isControl) {
            if (!fin || payloadLen > 125) {
                Serial.println("[WS] Protocol error: invalid control frame. Dropping.");
                client.stop();
                return "";
            }
        }

        // Enforce maximum buffer limit
        if (payloadLen > MAX_WS_MESSAGE_SIZE || (assembledText.length() + payloadLen > MAX_WS_MESSAGE_SIZE)) {
            Serial.printf("[WS] Payload length %llu exceeds safe limit %u. Dropping.\n", payloadLen, (unsigned int)MAX_WS_MESSAGE_SIZE);
            client.stop();
            return "";
        }

        // Read 4-byte masking key
        uint8_t mask[4] = {0};
        remainingMs = getRemainingDeadlineMs();
        if (remainingMs == 0 || !readExactBytes(client, mask, 4, remainingMs)) {
            Serial.println("[WS] Failed to read mask key within deadline. Dropping.");
            client.stop();
            return "";
        }

        // Read payload bytes with unified deadline
        uint8_t* payloadBuf = nullptr;
        if (payloadLen > 0) {
            payloadBuf = (uint8_t*)malloc((size_t)payloadLen);
            if (!payloadBuf) {
                Serial.println("[WS] Out of memory for payload. Dropping.");
                client.stop();
                return "";
            }
            remainingMs = getRemainingDeadlineMs();
            if (remainingMs == 0 || !readExactBytes(client, payloadBuf, (size_t)payloadLen, remainingMs)) {
                Serial.println("[WS] Failed to read full payload within deadline. Dropping.");
                free(payloadBuf);
                client.stop();
                return "";
            }
            // Unmask payload
            for (size_t i = 0; i < (size_t)payloadLen; i++) {
                payloadBuf[i] ^= mask[i % 4];
            }
        }

        // Handle Control Frames without breaking fragmented text assembly
        if (opcode == 0x08) { // Connection Close
            Serial.println("[WS] Received Close frame.");
            if (payloadBuf) free(payloadBuf);
            client.stop();
            return "CLOSE";
        }
        if (opcode == 0x09) { // Ping -> Send Pong
            sendWsPong(client, payloadBuf, (size_t)payloadLen);
            if (payloadBuf) free(payloadBuf);
            if (!inFragmentedMessage) {
                return "PING";
            }
            continue;
        }
        if (opcode == 0x0A) { // Pong -> Ignore
            if (payloadBuf) free(payloadBuf);
            if (!inFragmentedMessage) {
                return "PONG";
            }
            continue;
        }

        // Handle Data Frames (0x01 = Text, 0x00 = Continuation)
        if (opcode == 0x01 || (opcode == 0x00 && inFragmentedMessage)) {
            if (opcode == 0x01 && inFragmentedMessage) {
                Serial.println("[WS] Protocol error: new text frame before fragment complete. Dropping.");
                if (payloadBuf) free(payloadBuf);
                client.stop();
                return "";
            }

            if (payloadBuf && payloadLen > 0) {
                assembledText.concat((const char*)payloadBuf, (unsigned int)payloadLen);
            }
            if (payloadBuf) free(payloadBuf);

            if (fin) {
                return assembledText;
            } else {
                inFragmentedMessage = true;
                continue;
            }
        } else {
            Serial.printf("[WS] Unsupported or unexpected opcode 0x%02X. Dropping.\n", opcode);
            if (payloadBuf) free(payloadBuf);
            client.stop();
            return "";
        }
    }

    return assembledText;
}
