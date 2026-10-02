#ifndef WEB_SERVER_GATEWAY_H
#define WEB_SERVER_GATEWAY_H

// HTTP API for the network coin-slot gateway. Requests are authenticated with a one-time nonce and
// an HMAC (see GatewayAuth.h); only /api/gateway/config uses the normal admin login.
void handleGatewayChallenge();
void handleGatewayArm();
void handleGatewayStatus();
void handleGatewayRelease();
void handleGatewayAck();
void handleGatewayConfig();

#endif // WEB_SERVER_GATEWAY_H
