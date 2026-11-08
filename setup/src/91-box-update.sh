
# ---- the coin box's firmware: the router installs new releases for it ----------------------------------------------------------------
# The box cannot reach the website itself, but the router can. The box accepts an image only when the owner signed it (it checks the
# signature and the checksum of every byte itself, and goes back to its old firmware if the new one does not start), so the router is
# only the messenger: it fetches firmware.json and the image, checks the size and checksum the owner published, hands both to the box
# and waits for it to come back on the new version.

# ver_newer <a> <b>: a is a higher x.y.z than b (both exactly three numbers; anything else is not "newer")
ver_newer() {
	for _v in "$1" "$2"; do
		case "$_v" in *[!0-9.]* | "" | .* | *. | *..*) return 1 ;; esac
		[ "$(echo "$_v" | tr -cd . | wc -c)" -eq 2 ] || return 1
	done
	_i=0
	while [ "$_i" -lt 3 ]; do
		_i=$((_i + 1))
		_x=$(echo "$1" | cut -d. -f"$_i"); _y=$(echo "$2" | cut -d. -f"$_i")
		[ "$_x" -gt "$_y" ] && return 0
		[ "$_x" -lt "$_y" ] && return 1
	done
	return 1
}

# fw_field <firmware.json> <chip> <field>: a value from that chip's entry
fw_field() {
	awk -v chip="$2" -v field="$3" '
		index($0, "\"" chip "\"") && index($0, "{") { on = 1; next }
		on && index($0, "}") { exit }
		on && index($0, "\"" field "\"") { v = $0; sub(/^[^:]*:[ \t]*/, "", v); sub(/[ \t,]*$/, "", v); gsub(/"/, "", v); print v; exit }
	' "$1"
}

# box_info: ask the box what it runs (BOX_FW, BOX_CHIP, BOX_IMG = the state of its newest image); false when it does not answer
box_info() {
	_bip="${1:-$BOX_IP}"
	_d=$(curl -s -m 10 -u "admin:$(conf_get BOX_ADMIN_PASS)" "http://$_bip/api/diagnostics" 2> /dev/null)
	BOX_FW=$(printf '%s' "$_d" | sed -n 's/.*"firmware": *"\([^"]*\)".*/\1/p' | head -n 1)
	BOX_CHIP=$(printf '%s' "$_d" | sed -n 's/.*"chip": *"\([^"]*\)".*/\1/p' | head -n 1)
	BOX_IMG=$(printf '%s' "$_d" | sed -n 's/.*"ota_image_state": *"\([^"]*\)".*/\1/p' | head -n 1)
	[ -n "$BOX_FW" ] && [ -n "$BOX_CHIP" ]
}

# box_phones_busy: how many rental phones have time running (the box restarts for a few seconds while it installs)
box_phones_busy() {
	_bip="${1:-$BOX_IP}"
	curl -s -m 10 -u "admin:$(conf_get BOX_ADMIN_PASS)" "http://$_bip/api/status" 2> /dev/null | grep -o '"active": *true' | wc -l
}

# piso-setup box-update [check | auto]
#   (nothing)  install the coin box's newest firmware now
#   check      only say what is available
#   auto       what the hourly timer runs: installs only while no customer is online and no phone has time running (and not at
#              all after "piso-setup auto-update off"); tells the owner (Telegram) only when something happened
cmd_box_update() {
	_mode="${1:-apply}"
	[ "$(id -u)" = 0 ] || [ -n "$PISO_TEST_NONROOT" ] || die "run as root"
	DRY=0
	if [ ! -r "$CONF" ] || [ -z "$(conf_get BOX_ADMIN_PASS)" ]; then
		[ -r "$CONF" ] && record_update_state box unknown "" "" "no coin box password on this router: run piso-setup pair first"
		die "no coin box is set up on this router: run piso-setup pair first"
	fi
	_lock="${TMPDIR:-/tmp}/piso-boxupdate.lock"
	if ! mkdir "$_lock" 2> /dev/null; then
		if kill -0 "$(cat "$_lock/pid" 2> /dev/null)" 2> /dev/null; then log "Another box update is running."; return 0; fi
		rm -rf "$_lock"; mkdir "$_lock" || die "could not take the box update lock"
	fi
	echo $$ > "$_lock/pid"
	_work="$(work_base)/piso-boxupdate.$$"
	trap 'rm -rf "$_work" "$_lock"' EXIT
	rm -rf "$_work"; (umask 077; mkdir -p "$_work") || die "could not create $_work"

	_base=$(conf_get UPDATE_URL); _base="${_base:-$UPDATE_URL_DEFAULT}"
	if ! fetch_url "$_base/firmware.json" "$_work/firmware.json" 65536; then
		log "No box firmware information at $_base (offline, or nothing published yet)."
		record_update_state box unknown "" "" "the website cannot be reached (offline, or nothing published yet)"
		[ "$_mode" = apply ] && notify "box update: could not reach $_base"
		return 0
	fi

	_boxes=$(boxes_ips)
	_num_boxes=$(echo "$_boxes" | wc -w)
	_is_multi=0
	[ "$_num_boxes" -gt 1 ] && _is_multi=1
	_overall_rc=0

	for _cur_bip in $_boxes; do
		if [ "$_is_multi" = 1 ]; then
			_pfx="box at $_cur_bip"
			_Pfx="The coin box at $_cur_bip"
		else
			_pfx="the coin box"
			_Pfx="The coin box"
		fi

		if ! box_info "$_cur_bip"; then
			log "$_Pfx does not answer, so its firmware cannot be checked now."
			record_update_state box unknown "" "" "$_pfx did not answer (switched off, out of range, busy, or the password is wrong)"
			[ "$_mode" = apply ] && notify "box update: $_pfx does not answer"
			[ "$_is_multi" = 1 ] && { _overall_rc=1; continue; } || return 0
		fi

		_new=$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' "$_work/firmware.json" | head -n 1)
		if ! ver_newer "$_new" "$BOX_FW"; then
			if [ "$_new" = "$BOX_FW" ]; then record_update_state box current "$_new" "$BOX_FW" ""
			elif ver_newer "$BOX_FW" "$_new"; then record_update_state box newer "$_new" "$BOX_FW" ""
			else record_update_state box unknown "" "$BOX_FW" "the website's firmware information has no usable version"; fi
			log "$_Pfx is up to date (firmware $BOX_FW)."
			[ "$_mode" = apply ] && notify "box update: $_pfx already runs the latest firmware ($BOX_FW)"
			continue
		fi

		_url=$(fw_field "$_work/firmware.json" "$BOX_CHIP" url); _sha=$(fw_field "$_work/firmware.json" "$BOX_CHIP" sha256)
		_size=$(fw_field "$_work/firmware.json" "$BOX_CHIP" size); _sig=$(fw_field "$_work/firmware.json" "$BOX_CHIP" sig)
		if [ -z "$_url" ] || [ -z "$_sha" ] || [ -z "$_size" ] || [ -z "$_sig" ]; then
			log "Box firmware $_new is published, but not for this box's chip ($BOX_CHIP)."
			record_update_state box unknown "$_new" "$BOX_FW" "the published firmware is not built for this box's chip ($BOX_CHIP)"
			[ "$_is_multi" = 1 ] && continue || return 0
		fi

		_note=$(sed -n 's/.*"changelog": *"\([^"]*\)".*/\1/p' "$_work/firmware.json" | tr -cd 'A-Za-z0-9 .,;:()/+_%-' | cut -c1-200)
		if [ "$_mode" = check ]; then
			record_update_state box available "$_new" "$BOX_FW" "$_note"
			if [ "$_is_multi" = 1 ]; then
				log "Box firmware $_new is available for $_cur_bip (the box runs $BOX_FW). $_note"
			else
				log "Box firmware $_new is available (the box runs $BOX_FW). $_note"
			fi
			continue
		fi

		_hold=""
		if [ "$BOX_IMG" = pending_verify ] || [ "$BOX_IMG" = new ]; then _hold="the box is still checking the firmware it was last given"
		elif [ "$_mode" = auto ]; then
			if [ "$(conf_get AUTO_UPDATE)" = 0 ]; then _hold="automatic updates are off: run piso-setup box-update to install it"
			elif [ "$(guests_online)" -gt 0 ]; then _hold="customers are online, so it installs at the next quiet moment"
			elif [ "$(box_phones_busy "$_cur_bip")" -gt 0 ]; then _hold="a phone has time running, so it installs at the next quiet moment"
			else
				case "$(conf_get BOX_UPDATE_TRIES)" in "$_new":[3-9] | "$_new":[1-9][0-9]*) _hold="installing $_new failed three times, so it is not tried again (run piso-setup box-update to try by hand)" ;; esac
			fi
		fi
		if [ -n "$_hold" ]; then
			if [ "$_is_multi" = 1 ]; then
				log "Box firmware $_new is available for $_cur_bip; $_hold."
			else
				log "Box firmware $_new is available; $_hold."
			fi
			case "$_hold" in "automatic updates are off"*) _hs=off ;; "installing "*) _hs=failed ;; *) _hs=waiting ;; esac
			record_update_state box "$_hs" "$_new" "$BOX_FW" "$_hold"
			if [ "$_mode" = auto ] && [ "$(conf_get BOX_UPDATE_NOTIFIED)" != "$_new" ]; then conf_set BOX_UPDATE_NOTIFIED "$_new"; notify "box update: firmware $_new is available ($_note): $_hold"; fi
			[ "$_is_multi" = 1 ] && continue || return 0
		fi

		# from here on nothing may cut the update short half way
		trap '' HUP INT TERM
		_tries=$(conf_get BOX_UPDATE_TRIES); case "$_tries" in "$_new":*) _tries="${_tries#*:}" ;; *) _tries=0 ;; esac
		conf_set BOX_UPDATE_TRIES "$_new:$((_tries + 1))"

		_bin_file="$_work/box-$BOX_CHIP.bin"
		if [ ! -f "$_bin_file" ]; then
			fetch_url "$_url" "$_bin_file" 3145728 || { log "Could not download box firmware $_new."; record_update_state box failed "$_new" "$BOX_FW" "the download failed"; notify "box update: could not download firmware $_new"; [ "$_is_multi" = 1 ] && { _overall_rc=1; continue; } || return 1; }
			if [ "$(wc -c < "$_bin_file" | tr -d ' ')" != "$_size" ] || [ "$(sha256sum "$_bin_file" | cut -d' ' -f1)" != "$_sha" ]; then
				log "Box update refused: the downloaded file is not the one published."; record_update_state box failed "$_new" "$BOX_FW" "refused: the downloaded file is not the one published"; notify "box update REFUSED, nothing was installed: the downloaded file is not the one published"; [ "$_is_multi" = 1 ] && { _overall_rc=1; continue; } || return 1
			fi
		fi

		if [ "$_is_multi" = 1 ]; then
			log "Installing box firmware $_new on $_cur_bip (the box runs $BOX_FW) $_note"
		else
			log "Installing box firmware $_new (the box runs $BOX_FW) $_note"
		fi

		_pw=$(conf_get BOX_ADMIN_PASS)
		_r=$(curl -s -m 20 -o "$_work/manifest.out" -w '%{http_code}' -u "admin:$_pw" -X POST "http://$_cur_bip/api/ota/manifest" \
			--data-urlencode "chip=$BOX_CHIP" --data-urlencode "version=$_new" --data-urlencode "sha256=$_sha" --data-urlencode "size=$_size" --data-urlencode "sig=$_sig" 2> /dev/null)
		if [ "$_r" != 200 ]; then
			_why=$(sed -n 's/.*"message": *"\([^"]*\)".*/\1/p' "$_work/manifest.out" 2> /dev/null | head -n 1)
			log "The coin box refused the update (HTTP $_r): $_why"; record_update_state box failed "$_new" "$BOX_FW" "the coin box refused the update (${_why:-HTTP $_r})"; notify "box update REFUSED by the box (${_why:-HTTP $_r}): it stays on firmware $BOX_FW"; [ "$_is_multi" = 1 ] && { _overall_rc=1; continue; } || return 1
		fi

		_up_file="$_bin_file"
		command -v cygpath > /dev/null 2>&1 && _up_file=$(cygpath -m "$_up_file")
		_r=$(curl -s -m 240 -o "$_work/upload.out" -w '%{http_code}' -u "admin:$_pw" -H 'Expect:' -F "update=@$_up_file;filename=firmware.bin" "http://$_cur_bip/update" 2> /dev/null)
		if [ "$_r" != 200 ]; then
			log "The coin box did not take the firmware (HTTP $_r): $(head -c 200 "$_work/upload.out" 2> /dev/null)"
			record_update_state box failed "$_new" "$BOX_FW" "the coin box did not take the firmware (HTTP $_r): $(head -c 100 "$_work/upload.out" 2> /dev/null)"
			notify "box update FAILED ($(head -c 120 "$_work/upload.out" 2> /dev/null | tr -cd 'A-Za-z0-9 .,;:()/+_%-')): the box stays on firmware $BOX_FW; it tries again at a quiet moment"
			[ "$_is_multi" = 1 ] && { _overall_rc=1; continue; } || return 1
		fi

		# the box restarts; give it time to come back on the new version
		_t=0
		while [ "$_t" -lt "${PISO_BOX_WAIT:-120}" ]; do
			sleep "${PISO_BOX_POLL:-5}"; _t=$((_t + ${PISO_BOX_POLL:-5}))
			box_info "$_cur_bip" && [ "$BOX_FW" = "$_new" ] && break
		done

		if [ "$BOX_FW" = "$_new" ]; then
			conf_set BOX_UPDATE_TRIES ""
			record_update_state box current "$_new" "$_new" ""
			log "Box firmware $_new is installed and running."
			notify "box update: coin box firmware $_new installed and running. $_note"
		else
			log "The coin box did not come back on firmware $_new (it answers as ${BOX_FW:-nothing})."
			record_update_state box failed "$_new" "$BOX_FW" "the coin box did not come back on firmware $_new (it answers as ${BOX_FW:-nothing})"
			notify "box update to firmware $_new did not take: the box is on ${BOX_FW:-no answer}; if it does not answer, power-cycle it (it goes back to its old firmware by itself if the new one does not start)"
			[ "$_is_multi" = 1 ] && { _overall_rc=1; continue; } || return 1
		fi
	done

	return "$_overall_rc"
}
