#!/usr/bin/env bash
# ==============================================================================
# PisoPhone Universal Multi-Coin Slot Access Helper (Bash / cURL)
# ==============================================================================
# Configurable ESP32 Static IP:
#   Option 1: Pass IP as first parameter or use --ip flag
#   Option 2: Set ESP32_IP environment variable
#   Default:  192.168.1.10
# ==============================================================================

ESP32_IP="${ESP32_IP:-192.168.1.10}"
PORT="${ESP32_PORT:-80}"
ACTION="${1:-status}"
SESSION_ID="${2:-bash_session_$$}"
TIMEOUT_SEC="${3:-60}"

# Handle --ip argument override if provided
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ip)
      ESP32_IP="$2"
      shift 2
      ;;
    --port)
      PORT="$2"
      shift 2
      ;;
    --action)
      ACTION="$2"
      shift 2
      ;;
    --session)
      SESSION_ID="$2"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SEC="$2"
      shift 2
      ;;
    *)
      if [ -z "$POS_ACTION" ]; then
        POS_ACTION="$1"
      fi
      shift
      ;;
  esac
done

if [ -n "$POS_ACTION" ]; then
  ACTION="$POS_ACTION"
fi

BASE_URL="http://${ESP32_IP}:${PORT}"

echo "======================================================="
echo " PisoPhone Coin Slot Client"
echo " Controller Address: ${BASE_URL}"
echo " Action:             ${ACTION}"
echo " Session ID:         ${SESSION_ID}"
echo "======================================================="

case "$ACTION" in
  status)
    echo "[*] Querying coin slot status..."
    curl -s -X GET "${BASE_URL}/api/coinslot/status" | jq . 2>/dev/null || curl -s -X GET "${BASE_URL}/api/coinslot/status"
    echo ""
    ;;

  activate|arm)
    echo "[*] Arming coin slot (Timeout: ${TIMEOUT_SEC}s)..."
    curl -s -X POST "${BASE_URL}/api/coinslot/activate" \
         -d "session_id=${SESSION_ID}&timeout=${TIMEOUT_SEC}" | jq . 2>/dev/null || curl -s -X POST "${BASE_URL}/api/coinslot/activate" -d "session_id=${SESSION_ID}&timeout=${TIMEOUT_SEC}"
    echo ""
    ;;

  disarm|deactivate)
    echo "[*] Manually disarming coin slot immediately to make it available for other users..."
    curl -s -X POST "${BASE_URL}/api/coinslot/disarm" \
         -d "session_id=${SESSION_ID}" | jq . 2>/dev/null || curl -s -X POST "${BASE_URL}/api/coinslot/disarm" -d "session_id=${SESSION_ID}"
    echo ""
    ;;

  *)
    echo "Usage: $0 [status|activate|disarm] [SESSION_ID] [TIMEOUT_SEC] [--ip ESP32_IP] [--port PORT]"
    echo ""
    echo "Examples:"
    echo "  $0 status"
    echo "  $0 activate user_123 60 --ip 192.168.1.50"
    echo "  $0 disarm user_123 --ip 192.168.1.50"
    exit 1
    ;;
esac
