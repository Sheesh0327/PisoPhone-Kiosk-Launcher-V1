#!/bin/sh
#Copyright (C) BlueWave Projects and Services 2015-2025
#This software is released under the GNU GPL license.
#
# Status page of the "flash coin" portal: what a connected customer sees at http://<router>/ (openNDS "statuspath").
# It is the green status page of the paper-voucher theme, reading the same roll as flash_coin.sh through
# flash_coin_lib.sh. Install as /usr/lib/opennds/flash_coin_status.sh:
#   uci set opennds.@opennds[0].statuspath='/usr/lib/opennds/flash_coin_status.sh'
# A paused customer is disconnected (the paused time is kept on the roll) and resumes from the portal, so this page
# only offers Pause; Resume is a button on the portal page.
status=$1
clientip=$2
b64query=$3

. "${FLASH_LIB:-/usr/lib/opennds/flash_coin_lib.sh}"
max_pauses=1

do_ndsctl () {
	local timeout=4

	for tic in $(seq $timeout); do
		ndsstatus="ready"
		ndsctlout=$(eval ndsctl "$ndsctlcmd")

		for keyword in $ndsctlout; do

			if [ $keyword = "locked" ]; then
				ndsstatus="busy"
				sleep 1
				break
			fi
		done

		if [ "$ndsstatus" = "ready" ]; then
			break
		fi
	done
}

# Pause: the roll keeps the frozen time (flash_pause in flash_coin_lib.sh does the work and disconnects the device).
do_pause () {
	flash_info > /dev/null 2>&1
	flash_pause "$mac" "${pausepesos:-10}"
}
# Sets roll_state (none|running|paused|expired) and, when there is a line, pauses_used / pause_ok / plan / pesos / code.
read_pause_state () {
	pauses_used=0; pause_ok=0; roll_plan=""; roll_code=""; roll_state="none"
	flash_peek "$mac" || return 0
	roll_state="$P_STATE"; roll_plan="$R_PLAN"; roll_code="$R_CODE"; pauses_used="$R_PU"
	flash_info > /dev/null 2>&1
	[ "$P_STATE" = running ] && [ "$R_PLAN" = endurance ] && [ "$R_PESOS" -ge "${pausepesos:-10}" ] && [ "$R_PU" -lt "$max_pauses" ] && pause_ok=1
	return 0
}

get_client_zone () {
	failcheck=$(echo "$clientif" | grep "get_client_interface")

	if [ -z $failcheck ]; then
		client_if=$(echo "$clientif" | awk '{printf $1}')
		client_meshnode=$(echo "$clientif" | awk '{printf $2}' | awk -F ':' '{print $1$2$3$4$5$6}')
		local_mesh_if=$(echo "$clientif" | awk '{printf $3}')

		if [ ! -z "$client_meshnode" ]; then
			client_zone="MeshZone: $client_meshnode"
		else
			client_zone="LocalZone: $client_if"
		fi
	else
		client_zone=""
	fi
}

htmlentityencode() {
	entitylist="
		s/\"/\&quot;/g
		s/>/\&gt;/g
		s/</\&lt;/g
		s/%/\&#37;/g
		s/'/\&#39;/g
		s/\`/\&#96;/g
	"
	local buffer="$1"

	for entity in $entitylist; do
		entityencoded=$(echo "$buffer" | sed "$entity")
		buffer=$entityencoded
	done

	entityencoded=$(echo "$buffer" | awk '{ gsub(/\$/, "\\&#36;"); print }')
}

parse_variables() {
	# Only ever assign into variables this script actually reads downstream.
	# The previous version did `eval $var=...` where $var came straight from
	# the URL's query-string key - an attacker-controlled key (not just the
	# value) being eval'd is arbitrary command execution on the router. This
	# whitelist can't be talked into assigning, let alone running, anything
	# outside these two names.
	for var in $queryvarlist; do
		case "$var" in
			advanced|action) : ;;
			*) continue ;;
		esac

		evalstr=$(echo "$query" | awk -F"$var=" '{print $2}' | awk -F', ' '{print $1}')
		evalstr=$(printf "${evalstr//%/\\x}")

		htmlentityencode "$evalstr"
		evalstr=$entityencoded

		if [ -z "$evalstr" ]; then
			continue
		fi

		case "$var" in
			advanced) advanced=$evalstr ;;
			action) action=$evalstr ;;
		esac
		evalstr=""
	done
	query=""
}

parse_parameters() {
	if [ "$status" = "status" ]; then
		ndsctlcmd="json $clientip"
		do_ndsctl

		if [ "$ndsstatus" = "ready" ]; then
			param_str=$ndsctlout

			for param in gatewayname gatewayaddress gatewayfqdn mac version ip client_type clientif session_start session_end \
				last_active token state upload_rate_limit_threshold download_rate_limit_threshold \
				upload_packet_rate upload_bucket_size download_packet_rate download_bucket_size \
				upload_quota download_quota upload_this_session download_this_session upload_session_avg download_session_avg
			do
				val=$(echo "$param_str" | grep "\"$param\":" | awk -F'"' '{printf "%s", $4}')

				if [ "$val" = "null" ]; then
					val="Unlimited"
				fi

				if [ -z "$val" ]; then
					eval $param=$(echo "Unavailable")
				else
					eval $param=$(echo "\"$val\"")
				fi
			done

			gatewayname_dec=$(printf "${gatewayname//%/\\x}")
			htmlentityencode "$gatewayname_dec"
			gatewaynamehtml=$entityencoded

			get_client_zone

			sessionstart=$(date -d @$session_start)

			if [ "$session_end" = "Unlimited" ]; then
				sessionend=$session_end
			else
				sessionend=$(date -d @$session_end)
			fi

			lastactive=$(date -d @$last_active)
		fi
	else
		mountpoint=$(${LIBOPENNDS:-/usr/lib/opennds/libopennds.sh} tmpfs)
		. $mountpoint/ndscids/ndsinfo
	fi
}

header() {
	header="<!DOCTYPE html>
		<html>
		<head>
		<meta http-equiv=\"Cache-Control\" content=\"no-cache, no-store, must-revalidate\">
		<meta http-equiv=\"Pragma\" content=\"no-cache\">
		<meta http-equiv=\"Expires\" content=\"0\">
		<meta charset=\"utf-8\">
		<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
		<link rel=\"shortcut icon\" href=\"$url/$imagepath\" type=\"image/x-icon\">
		<title>$gatewaynamehtml Client Session Status</title>
		<style>
			:root { --bg: #0f1715; --surface: #16221f; --primary: #38ef7d; --primary-grad: linear-gradient(135deg, #11998e 0%, #38ef7d 100%); --text: #e0e0e0; --error: #ff4b4b; --mono: 'Cascadia Mono', Consolas, 'SF Mono', Menlo, 'JetBrains Mono', 'Fira Code', ui-monospace, monospace; }
			body { font-family: system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text); margin: 0; padding: 20px; display: flex; justify-content: center; align-items: center; min-height: 90vh; }
			.offset { width: 100%; max-width: 480px; background: var(--surface); padding: 30px; border-radius: 16px; box-shadow: 0 8px 32px rgba(0,0,0,0.5); border: 1px solid rgba(56, 239, 125, 0.1); }
			.title { color: var(--primary); font-size: 1.5rem; font-weight: 800; text-align: center; text-transform: uppercase; text-shadow: 0 0 10px rgba(56, 239, 125, 0.4); margin-bottom: 5px; }
			.subtitle { color: rgba(255,255,255,0.6); font-size: 0.9rem; text-align: center; margin-bottom: 25px; letter-spacing: 1px; }
			.insert { background: rgba(0,0,0,0.4); border: 1px solid rgba(255,255,255,0.05); border-radius: 8px; padding: 20px; margin-bottom: 20px; }
			.stat-line { display: flex; justify-content: space-between; border-bottom: 1px solid rgba(255,255,255,0.05); padding: 10px 0; font-size: 0.85rem; align-items: center; }
			.stat-line:last-child { border-bottom: none; }
			.stat-label { color: rgba(255,255,255,0.5); font-weight: 600; text-transform: uppercase; }
			.stat-value { color: var(--primary); font-family: var(--mono); font-weight: bold; text-align: right; }
			.timer-box { background: rgba(0,0,0,0.6); border: 1px solid rgba(56, 239, 125, 0.2); border-radius: 8px; padding: 15px; text-align: center; margin-bottom: 20px; }
			.timer-val { font-size: clamp(1.5rem, 7vw, 2.2rem); font-family: var(--mono); font-weight: 900; color: var(--primary); text-shadow: 0 0 15px rgba(56, 239, 125, 0.5); margin-top: 5px; white-space: nowrap; }
			input[type=\"submit\"], input[type=\"button\"], button { width: 100%; padding: 14px; background: var(--primary-grad); color: #000; border: none; border-radius: 8px; cursor: pointer; font-weight: 800; font-size: 1rem; text-transform: uppercase; margin-top: 10px; transition: 0.3s; }
			input[type=\"submit\"]:hover, input[type=\"button\"]:hover { opacity: 0.9; transform: translateY(-2px); box-shadow: 0 4px 15px rgba(56, 239, 125, 0.3); }
			.checkbox-container { display: flex; align-items: center; justify-content: center; gap: 10px; margin: 15px 0; color: #ccc; font-size: 0.9rem; }
			input[type=\"checkbox\"] { accent-color: var(--primary); width: 18px; height: 18px; cursor: pointer; }
			hr { border: 0; border-top: 1px solid rgba(255,255,255,0.1); margin: 25px 0; }
			.pause-banner { background: rgba(255,255,255,0.03); border: 1px solid rgba(56, 239, 125, 0.15); border-radius: 8px; padding: 12px 15px; margin-bottom: 15px; font-size: 0.85rem; color: rgba(255,255,255,0.7); text-align: center; }
			.pause-banner.is-paused { border-color: rgba(255, 75, 75, 0.3); color: var(--error); font-weight: 600; }
			.pause-notice { text-align: center; font-size: 0.8rem; color: rgba(255,255,255,0.5); margin-bottom: 10px; }
			input.pause-btn { background: transparent; border: 1px solid rgba(255,255,255,0.15); color: rgba(255,255,255,0.7); }
			input.pause-btn:hover { opacity: 1; border-color: var(--primary); color: var(--primary); box-shadow: none; transform: none; }
			input.resume-btn { background: var(--primary-grad); color: #000; }
		</style>
		</head>
		<body>
		<div class=\"offset\">
		<div class=\"title\">⚡ Session Status ⚡</div>
		<div class=\"subtitle\">$gatewaynamehtml</div>
		<div class=\"insert\">
	"
	echo "$header"
}

footer() {
	year=$(date +'%Y')
	echo "
		<div style=\"text-align: center; margin-top: 20px; font-size: 0.75rem; color: rgba(255,255,255,0.3); border-top: 1px solid rgba(255,255,255,0.05); padding-top: 20px;\">
			<div style=\"font-weight: 800; letter-spacing: 2px; color: var(--primary); text-shadow: 0 0 8px rgba(56, 239, 125, 0.3); margin-bottom: 5px; text-transform: uppercase;\">
				$gatewaynamehtml
			</div>
			<span>Sys.Ver $version // $year</span>
		</div>
		</div>
		</body>
		</html>
	"
}

body() {
	if [ "$ndsstatus" = "busy" ]; then
		pagebody="
			<div style=\"text-align: center; color: var(--primary); font-size: 1.1rem; font-weight: bold; margin-bottom: 20px;\">⚙️ SYSTEM BUSY</div>
			<div style=\"color: #aaa; text-align: center; margin-bottom: 20px;\">The portal is currently processing requests. Please refresh.</div>
			<form>
				<input type=\"button\" VALUE=\"REFRESH STATUS\" onClick=\"history.go(0);return true;\">
			</form>
		"
	elif [ "$status" = "status" ]; then

		if [ "$upload_rate_limit_threshold" = "Unlimited" ] || [ "$upload_packet_rate" = "Unlimited" ]; then
			upload_packet_rate="(Not Checked)"
			upload_bucket_size="(Not Set)"
		fi

		if [ "$download_rate_limit_threshold" = "Unlimited" ] || [ "$download_packet_rate" = "Unlimited" ]; then
			download_packet_rate="(Not Checked)"
			download_bucket_size="(Not Set)"
		fi

		checked="$advanced"

		# ── Pause block ──────────────────────────────────────────────────────
		pauses_left=$(( max_pauses - pauses_used ))
		[ $pauses_left -lt 0 ] && pauses_left=0
		if [ -n "$pause_notice" ]; then
			pause_notice_html="<div class=\"pause-notice\">$pause_notice</div>"
		else
			pause_notice_html=""
		fi

		if [ "$roll_state" = "none" ]; then
			pause_block=""
		elif [ "$pause_ok" = "1" ]; then
			pause_block="
				$pause_notice_html
				<div class=\"pause-banner\">Pauses remaining: $pauses_left / $max_pauses</div>
				<form action=\"$url/\" method=\"get\">
					<input type=\"hidden\" name=\"action\" value=\"pause\">
					<input type=\"submit\" class=\"pause-btn\" value=\"PAUSE SESSION\" >
				</form>
			"
		elif [ "$roll_plan" = "endurance" ] && [ "$pauses_left" -le 0 ]; then
			pause_block="
				$pause_notice_html
				<div class=\"pause-banner\">No pauses remaining ($max_pauses / $max_pauses used)</div>
			"
		else
			pause_block="$pause_notice_html"
		fi

		buttons="
			</div>
			<form action=\"$url/opennds_auth/\" method=\"get\">
				<input type=\"submit\" value=\"ADD TIME\" >
			</form>
			<form action=\"$url/opennds_deny/\" method=\"get\">
				<input type=\"submit\" class=\"pause-btn\" value=\"DISCONNECT\" >
			</form>
			<hr>
			<form action=\"$url/\" method=\"get\">
				<div class=\"checkbox-container\">
					<input type=\"checkbox\" id=\"adv\" value=\"checked\" name=\"advanced\" $checked >
					<label for=\"adv\" style=\"cursor: pointer;\">Show Advanced Diagnostics</label>
				</div>
				<input type=\"submit\" value=\"Refresh Status\" >
			</form>
		"

		# Time left comes from the roll (the paid session), not from openNDS' session_end: a top-up or a fair-use re-grant
		# never shows a different time here.
		if [ "$roll_state" = "running" ]; then end_epoch="$R_END"; else end_epoch="$session_end"; fi
		script_block="
			<div class=\"timer-box\">
				<div style=\"font-size: 0.8rem; color: #888; text-transform: uppercase; font-weight: bold;\">Time Remaining</div>
				<div id=\"live-timer\" class=\"timer-val\">CALCULATING...</div>
			</div>
			<script>
				var endEpoch = '$end_epoch';
				var timerEl = document.getElementById('live-timer');
				var endNum = parseInt(endEpoch, 10);

				if (endEpoch === 'Unlimited') {
					if (timerEl) timerEl.innerHTML = 'UNLIMITED';
				} else if (endEpoch === '' || endEpoch === 'Unavailable' || isNaN(endNum) || endNum <= 0) {
					if (timerEl) timerEl.innerHTML = '—';
				} else {
					var end = endNum * 1000;
					var x = setInterval(function() {
						var now = new Date().getTime();
						var dist = end - now;
						if (dist < 0) {
							clearInterval(x);
							if(timerEl) timerEl.innerHTML = 'EXPIRED';
							setTimeout(function(){ location.reload(); }, 2000);
						} else {
							var d = Math.floor(dist / (1000 * 60 * 60 * 24));
							var h = Math.floor((dist % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
							var m = Math.floor((dist % (1000 * 60 * 60)) / (1000 * 60));
							var s = Math.floor((dist % (1000 * 60)) / 1000);
							var timeStr = '';
							if (d > 0) timeStr += d + 'D ';
							if (d > 0 || h > 0) timeStr += h + 'H ';
							timeStr += (m < 10 ? '0'+m : m) + 'm ' + (s < 10 ? '0'+s : s) + 's';
							if(timerEl) timerEl.innerHTML = timeStr;
						}
					}, 1000);
				}
			</script>
		"
		plan_line=""
		[ -n "$roll_plan" ] && plan_line="<div class=\"stat-line\"><span class=\"stat-label\">Plan</span><span class=\"stat-value\">$roll_plan</span></div>"
		code_line=""
		[ -n "$roll_code" ] && code_line="<div class=\"stat-line\"><span class=\"stat-label\">Your Code</span><span class=\"stat-value\">$roll_code</span></div>"

		if [ "$advanced" = "checked" ]; then
			pagebody="
				$script_block
				$plan_line
				$code_line
				<div class=\"stat-line\"><span class=\"stat-label\">IP Address</span><span class=\"stat-value\">$ip</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">MAC Address</span><span class=\"stat-value\">$mac</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">Client Type</span><span class=\"stat-value\">$client_type</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">Interface</span><span class=\"stat-value\">$clientif</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">Session Start</span><span class=\"stat-value\">$sessionstart</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">Session End</span><span class=\"stat-value\">$sessionend</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">DL Limit</span><span class=\"stat-value\">$download_rate_limit_threshold Kb/s</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">UL Limit</span><span class=\"stat-value\">$upload_rate_limit_threshold Kb/s</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">DL This Session</span><span class=\"stat-value\">$download_this_session KB</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">UL This Session</span><span class=\"stat-value\">$upload_this_session KB</span></div>
			"
		else
			pagebody="
				$script_block
				$plan_line
				$code_line
				<div class=\"stat-line\"><span class=\"stat-label\">IP Address</span><span class=\"stat-value\">$ip</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">MAC Address</span><span class=\"stat-value\">$mac</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">DL This Session</span><span class=\"stat-value\">$download_this_session KB</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">UL This Session</span><span class=\"stat-value\">$upload_this_session KB</span></div>
			"
		fi

		pagebody="$pagebody$pause_block$buttons"

	elif [ "$status" = "err511" ]; then

		pagebody="
			<div style=\"text-align: center; color: var(--error); font-size: 1.2rem; font-weight: bold; margin-bottom: 20px;\">❌ CONNECTION REQUIRED</div>
			<div style=\"color: #aaa; text-align: center; margin-bottom: 20px;\">To access the internet, click or tap the Continue button to log in.</div>
			<form action=\"$url/login\" method=\"get\" target=\"_blank\">
			<input type=\"submit\" value=\"CONTINUE TO LOGIN\" >
			</form>
		"

	else
		exit 1
	fi

	echo "$pagebody"
}

if [ -z "$clientip" ]; then
	exit 1
fi

${LIBOPENNDS:-/usr/lib/opennds/libopennds.sh} download "/usr/lib/opennds/download_resources.sh" "" "" "0" "" &>/dev/null

if [ -e "/etc/opennds/htdocs/ndsremote/logo.png" ]; then
	imagepath="ndsremote/logo.png"
else
	imagepath="images/splash.jpg"
fi

if [ "$status" = "status" ] || [ "$status" = "err511" ]; then
	parse_parameters

	if [ -z "$gatewayfqdn" ] || [ "$gatewayfqdn" = "disable" ] || [ "$gatewayfqdn" = "disabled" ]; then
		url="http://$gatewayaddress"
	else
		url="http://$gatewayfqdn"
	fi

	querystr=""

	if [ ! -z "$b64query" ]; then
		ndsctlcmd="b64decode $b64query"
		do_ndsctl
		querystr=$ndsctlout

		querystr=${querystr:1:1024}
		queryvarlist=""

		for element in $querystr; do
			htmlentityencode "$element"
			element=$entityencoded
			varname=$(echo "$element" | awk -F'=' '$2!="" {printf "%s", $1}')
			queryvarlist="$queryvarlist $varname"
		done

		query=$querystr
		parse_variables
	fi

	# Handle a Pause button click (action=pause in the query string).
	pause_notice=""
	if [ "$ndsstatus" = "ready" ] && [ "$status" = "status" ]; then
		if [ "$action" = "pause" ]; then
			do_pause
			case $? in
				0) pause_notice="Session paused. Join this Wi-Fi again and tap Resume to continue." ;;
				2) pause_notice="Nothing to pause." ;;
				5) pause_notice="System busy - please try again." ;;
				6) pause_notice="Already paused." ;;
				7) pause_notice="No pauses remaining." ;;
				9) pause_notice="Pause is only available for Endurance time of enough pesos." ;;
			esac
		fi
	fi

	# Always read the roll fresh, so a page reload still shows the right button.
	if [ "$status" = "status" ] && [ "$ndsstatus" = "ready" ]; then
		read_pause_state
	fi

	header
	body
	footer
	exit 0
else
	exit 1
fi
