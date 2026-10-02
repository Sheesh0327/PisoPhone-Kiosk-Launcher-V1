#!/bin/sh
# ThemeSpec for openNDS (login_option_enabled '3'): pay for Wi-Fi time with coins.
# Install as /usr/lib/opennds/theme_coinslot.sh. libopennds.sh does the heavy lifting (decoding the
# client's parameters, the Terms page, authentication via auth_log); this file only supplies the page
# sequence and talks to the local coin-slot listener (coinslot-listener.sh) on 127.0.0.1.
#
# Page sequence, driven by the "coinact" form variable:
#   (none)   welcome: rate and an "Insert coin" button
#   start    ask the listener to arm the coin slot, then show the waiting page
#   wait     coin window: running coin count and countdown, refreshes by itself
#   finish   stop accepting, wait for in-flight coins, then show the result
#   landing  (libopennds, landing=yes) turn the counted coins into Wi-Fi time via auth_log
# The minutes granted are always decided by the listener, never taken from the browser.

title="theme_coinslot"

COINSLOT_URL="${COINSLOT_URL:-http://127.0.0.1:8099}"

# ---------------------------------------------------------------------------
# Listener access
# ---------------------------------------------------------------------------
# coinslot <path>: call the listener, print its JSON answer (empty if it is not running).
coinslot() {
	if command -v curl >/dev/null 2>&1; then
		curl -sS -m 10 "$COINSLOT_URL$1" 2>/dev/null
	else
		wget -qO- -T 10 "$COINSLOT_URL$1" 2>/dev/null
	fi
}

# jget <field>: read one value from the flat JSON the listener returns.
jget() { sed -n 's/.*"'"$1"'" *: *"\{0,1\}\([^",}]*\).*/\1/p'; }

# The coin-slot session id is a hash of the client's secret openNDS id, so other clients cannot
# guess it and claim someone else's coins.
coinslot_sid() {
	printf '%s' "$hid" | sha256sum | cut -c1-32
}

# Percent-encode the characters base64 uses that are special in a URL (needed for the refresh link).
fas_urlsafe() {
	printf '%s' "$fas" | sed 's/+/%2B/g; s,/,%2F,g; s/=/%3D/g'
}

# ---------------------------------------------------------------------------
# Page frame
# ---------------------------------------------------------------------------
# libopennds.sh calls header() ONCE per request, before it calls display_terms / landing_page /
# generate_splash_sequence. So header() is also where this theme decides which page to show (it
# talks to the listener there) and whether the page should reload itself.
header() {
	gatewayurl=$(printf "${gatewayurl//%/\\x}")
	PAGE=""
	REFRESH=""
	if [ "$landing" != "yes" ] && [ "$terms" != "yes" ]; then
		choose_page
	fi
	refreshtag=""
	if [ -n "$REFRESH" ]; then
		refreshtag="<meta http-equiv=\"refresh\" content=\"${REFRESH%% *}; url=/opennds_preauth/?fas=$(fas_urlsafe)&coinact=${REFRESH##* }\">"
	fi
	echo "<!DOCTYPE html>
		<html>
		<head>
		<meta http-equiv=\"Cache-Control\" content=\"no-cache, no-store, must-revalidate\">
		<meta http-equiv=\"Pragma\" content=\"no-cache\">
		<meta http-equiv=\"Expires\" content=\"0\">
		<meta charset=\"utf-8\">
		<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
		$refreshtag
		<link rel=\"stylesheet\" type=\"text/css\" href=\"$gatewayurl/splash.css\">
		<title>$gatewayname</title>
		</head>
		<body>
		<div class=\"offset\">
		<med-blue>
			$gatewayname <br>
		</med-blue>
		<div class=\"insert\" style=\"max-width:100%;\">
	"
}

footer() {
	echo "
		<hr>
		<div style=\"font-size:0.5em;\">
			<br>
			Portal Version: $version
			<br><br>
		</div>
		</div>
		</div>
		</body>
		</html>
	"
	exit 0
}

# Button that posts back to the portal with the given coinact (and optionally landing=yes).
action_button() {
	# $1 = label, $2 = coinact value, $3 = "landing" to also set landing=yes
	landinginput=""
	[ "$3" = "landing" ] && landinginput="<input type=\"hidden\" name=\"landing\" value=\"yes\">"
	echo "
		<form action=\"/opennds_preauth/\" method=\"get\">
			<input type=\"hidden\" name=\"fas\" value=\"$fas\">
			<input type=\"hidden\" name=\"coinact\" value=\"$2\">
			$landinginput
			<input type=\"submit\" value=\"$1\">
		</form>
	"
}

# ---------------------------------------------------------------------------
# Deciding what to show (runs from header(), before any page content)
# ---------------------------------------------------------------------------
# Sets PAGE (welcome | wait | counting | busy | error | result | unavailable) and REFRESH
# ("<seconds> <coinact>" when the page should reload itself).
choose_page() {
	sid=$(coinslot_sid)
	info=$(coinslot /info)
	rate=$(printf '%s' "$info" | jget rate)
	window=$(printf '%s' "$info" | jget window)
	if [ -z "$rate" ]; then
		PAGE="unavailable"
		return
	fi

	case "$coinact" in
		start)
			answer=$(coinslot "/start?sid=$sid")
			if [ "$(printf '%s' "$answer" | jget state)" = "error" ]; then
				err=$(printf '%s' "$answer" | jget error)
				if [ "$err" = "SLOT_BUSY" ]; then
					PAGE="busy"
					REFRESH="5 start"
				else
					PAGE="error"
				fi
				return
			fi
			choose_wait_page ;;
		wait)
			choose_wait_page ;;
		finish)
			coinslot "/finish?sid=$sid" >/dev/null
			status=$(coinslot "/status?sid=$sid")
			state=$(printf '%s' "$status" | jget state)
			if [ "$state" = "done" ] || [ "$state" = "none" ] || [ "$state" = "error" ]; then
				PAGE="result"
			else
				PAGE="counting"
				REFRESH="2 finish"
			fi ;;
		*)
			# Coins from an earlier window that never became access are offered first.
			status=$(coinslot "/status?sid=$sid")
			if [ "$(printf '%s' "$status" | jget state)" = "done" ] && [ "$(printf '%s' "$status" | jget claimed)" = "false" ] &&
				[ "$(printf '%s' "$status" | jget pulses)" -gt 0 ] 2>/dev/null; then
				PAGE="result"
			else
				PAGE="welcome"
			fi ;;
	esac
}

choose_wait_page() {
	status=$(coinslot "/status?sid=$sid")
	state=$(printf '%s' "$status" | jget state)
	pulses=$(printf '%s' "$status" | jget pulses)
	remaining=$(printf '%s' "$status" | jget remaining)
	case "$state" in
		starting | armed)
			PAGE="wait"
			REFRESH="2 wait" ;;
		done)
			PAGE="result" ;;
		error)
			PAGE="error"
			err=$(printf '%s' "$status" | jget error) ;;
		*)
			PAGE="welcome" ;;
	esac
}

# ---------------------------------------------------------------------------
# Page content (header() has already been printed by libopennds.sh)
# ---------------------------------------------------------------------------
generate_splash_sequence() {
	case "$PAGE" in
		wait) page_wait ;;
		counting) page_counting ;;
		busy) page_busy ;;
		error) page_error ;;
		result) page_result ;;
		unavailable) page_unavailable ;;
		*) page_welcome ;;
	esac
	footer
}

page_unavailable() {
	echo "<big-red>Coin payment is not available right now.</big-red>
		<br><italic-black>Please try again in a moment or ask the attendant.</italic-black>"
}

page_welcome() {
	echo "
		<big-red>Welcome!</big-red><br>
		<med-blue>You are connected to <br>$client_zone</med-blue><br>
		<italic-black>
			Pay with coins to get Wi-Fi access.<br>
			<b>1 coin = $rate minutes.</b> You will have $window seconds to insert coins.
		</italic-black>
		<hr>
	"
	action_button "Insert coin" start
	read_terms
}

page_busy() {
	echo "<big-red>The coin slot is busy.</big-red><br>
		<italic-black>Someone else is paying. This page will try again automatically.</italic-black>"
}

page_error() {
	echo "<big-red>Coin payment is not available right now.</big-red><br>
		<italic-black>($err) Please try again or ask the attendant.</italic-black>"
	action_button "Try again" start
}

page_wait() {
	echo "
		<big-red>Insert coin(s) now</big-red><br>
		<med-blue>${pulses:-0} coin(s) = $((${pulses:-0} * rate)) minutes</med-blue><br>
		<italic-black>Time left to insert coins: ${remaining:-$window} seconds</italic-black>
		<hr>
	"
	if [ "${pulses:-0}" -gt 0 ] 2>/dev/null; then
		action_button "Connect now" finish
	else
		action_button "Cancel" finish
	fi
}

page_counting() {
	echo "<big-red>Counting your coins...</big-red><br><italic-black>One moment please.</italic-black>"
}

page_result() {
	claim=$(coinslot "/claim?sid=$sid")
	coins=$(printf '%s' "$claim" | jget pulses)
	minutes=$(printf '%s' "$claim" | jget minutes)
	if [ "${coins:-0}" -gt 0 ] 2>/dev/null; then
		echo "
			<big-red>Thank you!</big-red><br>
			<med-blue>$coins coin(s) = $minutes minutes of Wi-Fi</med-blue><br>
			<italic-black>Tap Connect to start your session.</italic-black>
			<hr>
		"
		action_button "Connect" connect landing
	else
		echo "
			<big-red>No coins were detected.</big-red><br>
			<italic-black>You were not charged and no access was granted.</italic-black>
			<hr>
		"
		action_button "Try again" start
	fi
}

# libopennds.sh calls landing_page() when the form was submitted with landing=yes.
landing_page() {
	originurl=$(printf "${originurl//%/\\x}")
	gatewayurl=$(printf "${gatewayurl//%/\\x}")

	configure_log_location
	. $mountpoint/ndscids/ndsinfo

	sid=$(coinslot_sid)
	claim=$(coinslot "/claim?sid=$sid")
	coins=$(printf '%s' "$claim" | jget pulses)
	minutes=$(printf '%s' "$claim" | jget minutes)

	ndsstatus="failed"
	if [ "${minutes:-0}" -gt 0 ] 2>/dev/null; then
		# The session length comes from the coins the listener counted, never from the browser.
		sessiontimeout="$minutes"
		quotas="$sessiontimeout $upload_rate $download_rate $upload_quota $download_quota"
		userinfo="$userinfo, coins=$coins, minutes=$minutes"
		auth_log
		# Only after access was really granted are the coins acknowledged (removed) on the box.
		[ "$ndsstatus" = "authenticated" ] && coinslot "/confirm?sid=$sid" >/dev/null
	fi

	if [ "$ndsstatus" = "authenticated" ]; then
		echo "
			<p>
				<big-red>You are connected for $minutes minutes.</big-red>
				<hr>
				<italic-black>
					You can use your browser, email and other apps as you normally would.
				</italic-black>
				(Your device originally requested $originurl)
			</p>
			<form>
				<input type=\"button\" VALUE=\"Continue\" onClick=\"location.href='http://$gatewayfqdn/?$randquery'\" >
			</form>
		"
	else
		echo "
			<p>
				<big-red>We could not start your session.</big-red>
				<hr>
				<italic-black>
					Your coins are still safe. Tap Try again; if it keeps failing ask the attendant.
				</italic-black>
			</p>
		"
		action_button "Try again" start
	fi
	read_terms
	footer
}

read_terms() {
	echo "
		<form action=\"/opennds_preauth/\" method=\"get\">
			<input type=\"hidden\" name=\"fas\" value=\"$fas\">
			<input type=\"hidden\" name=\"terms\" value=\"yes\">
			<input type=\"submit\" value=\"Read Terms of Service   \" >
		</form>
	"
}

display_terms() {
	# Edit to suit your site. You are responsible for making these compliant with your local law.
	echo "
		<b style=\"color:red;\">Terms of Service</b><br>
		<b>Access is paid for in coins and lasts for the time shown when you connect. Coins are not refundable
		once a session has started. Do not misuse the connection. The owners may end a session at any time.</b>
		<hr>
		<form>
			<input type=\"button\" VALUE=\"Continue\" onClick=\"history.go(-1);return true;\">
		</form>
	"
	footer
}

#### end of functions ####

#################################################
# Main entry point of this Theme: parameters set here override those in libopennds.sh
#################################################

randquery="$(date | sha256sum | awk '{printf "%s", $1}')"

# Session length is set per customer in landing_page() from the coins counted.
sessiontimeout="0"
upload_rate="0"
download_rate="0"
upload_quota="0"
download_quota="0"
quotas="$sessiontimeout $upload_rate $download_rate $upload_quota $download_quota"

ndscustomparams=""
ndscustomimages=""
ndscustomfiles=""
ndsparamlist="$ndsparamlist $ndscustomparams $ndscustomimages $ndscustomfiles"

# Extra form variable used by this theme (kept unique: the parser matches names as substrings).
additionalthemevars="coinact"
fasvarlist="$fasvarlist $additionalthemevars"

userinfo="$title"
